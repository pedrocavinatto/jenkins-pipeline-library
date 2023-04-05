void call(){

    def labelKaniko = "kaniko-${UUID.randomUUID().toString()}"
    def labelHelm = "helm-${UUID.randomUUID().toString()}"

    // Git stuff
    def repo = config.git_repo
    def chartFolder = config.chartFolder
    def additionalRepo = config.git_additional_repo
    def repoFolder = repo.substring(repo.lastIndexOf('/') + 1, repo.lastIndexOf('.'))
    def additionalRepoFolder = ""
    if (additionalRepo != null) {
        additionalRepoFolder = additionalRepo.substring(additionalRepo.lastIndexOf('/') + 1, additionalRepo.lastIndexOf('.'))
    }
    def solutionName = config.solutionName
    if (solutionName == null) {
        solutionName = "web" //Default value
    }

    // Registry stuff
    def registryString = config.registryString
    def tenantID = config.tenantID
    def tagVersion = config.tagVersion //Could be changed to use the build number

    // Kubernetes stuff
    def jenkinsServiceAccount = config.jenkinsServiceAccount
    def registryCredentialsSecret = config.registryCredentialsSecret
    def secretKey = config.secretKey
    def hostDNS = config.hostDNS
    def clusterIssuer = config.clusterIssuer
    def tlsEnabled = config.tlsEnabled
    if (tlsEnabled == null) {
        tlsEnabled = false //Default value
    }

    //Logging parameter
    def loggingEndpoint = config.loggingEndpoint

    //defining the default values for the log status
    String success_status = "success"
    String failed_status = "failed"

    Boolean wasBuildSuccessful = false
    podTemplate(name: 'kaniko', label: labelKaniko, serviceAccount: jenkinsServiceAccount, yaml: """
    kind: Pod
    metadata:
      name: kaniko
    spec:
      containers:
      - name: kaniko
        image: gcr.io/kaniko-project/executor:debug
        imagePullPolicy: Always
        command:
        - /busybox/cat
        tty: true
        volumeMounts:
          - name: ${registryCredentialsSecret}
            mountPath: /kaniko/.docker
        volumes:
        - name: ${registryCredentialsSecret}
          projected:
            sources:
            - secret:
                name: ${registryCredentialsSecret}
                items:
                  - key: ${secretKey}
                    path: ${secretKey}
    """
        ) 
    
        {
        node(labelKaniko) {
            stage('Build Kaniko') {
                try {
                    sh "git clone ${repo}"
                    if (additionalRepo != null) {
                        sh "git clone ${additionalRepo}"
                        sh "cp -r ${additionalRepoFolder}/* ${repoFolder}"
                        sh "ls -la ${repoFolder}"
                    }
                    container(name: 'kaniko', shell: '/busybox/sh') {
                        withEnv(['PATH+EXTRA=/busybox']) {
                        sh """#!/busybox/sh
                        /kaniko/executor -f ${repoFolder}/Dockerfile -c ${repoFolder} --destination=${registryString}/${tenantID}:${tagVersion}
                        """
                        }
                    }
                    sendLogs(success_status, "Build Project", null, loggingEndpoint)
                    wasBuildSuccessful = true
                } catch (Exception e) {
                    sendLogs(failed_status, "Build Project", e.toString(), loggingEndpoint)
                }
            }
        }
    }
    
    podTemplate(name: 'helm', label: labelHelm, serviceAccount: jenkinsServiceAccount, yaml: """
    kind: Pod
    metadata:
      name: helm
    spec:
      containers:
      - name: helm
        image: alpine/helm
        imagePullPolicy: Always
        command:
        - cat
        tty: true
    """
        ) 
    
        {
        node(labelHelm) {
            stage('Deploy project with Helm') {
                try {
                    if (!wasBuildSuccessful) {
                        throw new Exception("Build failed, skipping deploy")
                    }

                    sh "git clone ${repo}"
                    if (additionalRepo != null) {
                        sh "git clone ${additionalRepo}"
                        sh "cp -r ${additionalRepoFolder}/* ${repoFolder}"
                        sh "ls -la ${repoFolder}"
                    }
                    container(name: 'helm', shell: '/bin/ash') {
                        helmCommand = """helm upgrade --install --dry-run ${tenantID} ./${repoFolder}/${chartFolder} \
                        --set image.tag=${tagVersion} \
                        --set image.repository=${registryString}/${tenantID} \
                        --set solutionName="${solutionName}" \
                        --set ingress.enabled=true"""
                        if (hostDNS != null) {
                            helmCommand += " --set ingress.host=${tenantID}.${hostDNS}"
                        }
                        if (hostDNS != null && config.tlsEnabled) {
                            helmCommand += " --set ingress.tls.enabled=true --set ingress.tls.secretName=${tenantID}-cert --set ingress.tls.hosts[0]=${tenantID}.${hostDNS}"
                        }
                        if (clusterIssuer != null) {
                            helmCommand += " --set ingress.clusterIssuer=${clusterIssuer}"
                        }
                        sh helmCommand
                    }
                    sendLogs(success_status, "Deploy Project", null, loggingEndpoint)
                } catch (Exception e) {
                    sendLogs(failed_status, "Deploy Project", e.toString(), loggingEndpoint)
                }
            }
        }
    }
}

//Logging stuff
//Class that will be used as JSON skeleton
class LogStatus {
    String job_url
    String build_url
    String status
    String step
    String tenant_id
    Integer build_number
    String error
}

def getJobUrl() {
    String build_url = "${BUILD_URL}"
    return build_url.substring(0, build_url.length() - ("${BUILD_NUMBER}".toString() + "/").length())
}

//sending request for logging purposes
def sendLogs(String status, String step, String error, String loggingEndpoint) {
    def log = new LogStatus(
        job_url: getJobUrl(),
        status: status,
        step: step,
        tenant_id: "${config.tenantID}",
        build_number: Integer.parseInt("${BUILD_NUMBER}"),
        build_url: "${BUILD_URL}",
        error: error
    )
    
    def json = new groovy.json.JsonBuilder( log ).toPrettyString()
    
    echo json
    
    if (loggingEndpoint != null && loggingEndpoint.trim() != "") {
        echo "Sending log to ${loggingEndpoint}"

        String sendLogReq = """curl --request POST \
        --url '${loggingEndpoint}' \
        --header 'Accept: application/json' \
        --header 'Content-Type: application/json' \
        --data '${json}'"""
        
        sh sendLogReq
    }
}