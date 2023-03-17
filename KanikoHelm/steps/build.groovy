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

    // Docker Hub stuff
    def dockerHubUser = config.dockerHubUser
    def tenantID = config.tenantID
    def tagVersion = config.tagVersion //Could be changed to use the build number

    // Kubernetes stuff
    def jenkinsServiceAccount = config.jenkinsServiceAccount
    def dockerHubCredentialsSecret = config.dockerHubCredentialsSecret
    def secretKey = config.secretKey
    def hostDNS = config.hostDNS

    //defining the default values for the log status
    String success_status = "success"
    String failed_status = "failed"

    Boolean wasBuildSuccessful = false
    try {
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
              - name: ${dockerHubCredentialsSecret}
                mountPath: /kaniko/.docker
          volumes:
          - name: ${dockerHubCredentialsSecret}
            projected:
              sources:
              - secret:
                  name: ${dockerHubCredentialsSecret}
                  items:
                    - key: ${secretKey}
                      path: ${secretKey}
        """
            ) 
        
        
            {
            node(labelKaniko) {
                stage('Build Kaniko') {
                    sh "git clone ${repo}"
                    if (additionalRepo != null) {
                        sh "git clone ${additionalRepo}"
                        sh "cp -r ${additionalRepoFolder}/* ${repoFolder}"
                        sh "ls -la ${repoFolder}"
                    }
                    container(name: 'kaniko', shell: '/busybox/sh') {
                        withEnv(['PATH+EXTRA=/busybox']) {
                          sh """#!/busybox/sh
                          /kaniko/executor -f ${repoFolder}/Dockerfile -c ${repoFolder} --destination=${dockerHubUser}/${tenantID}:${tagVersion}
                          """
                        }
                    }
                }
            }
        }
        sendLogs(success_status, "Build Project", null)
        wasBuildSuccessful = true
    } catch (Exception e) {
        sendLogs(failed_status, "Build Project", e.toString())
    }
    
    try {
        if (!wasBuildSuccessful) {
            throw new Exception("Build failed, skipping deploy")
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
                    sh "git clone ${repo}"
                    if (additionalRepo != null) {
                        sh "git clone ${additionalRepo}"
                        sh "cp -r ${additionalRepoFolder}/* ${repoFolder}"
                        sh "ls -la ${repoFolder}"
                    }
                    container(name: 'helm', shell: '/bin/ash') {
                        helmCommand = "helm upgrade --install ${tenantID} ./${repoFolder}/${chartFolder} --set image.tag=${tagVersion} --set image.repository=${dockerHubUser}/${tenantID}"
                        if (hostDNS != null) {
                            helmCommand += " --set ingress.host=${tenantID}.${hostDNS}"
                        }
                        sh helmCommand
                    }
                }
            }
        }
        sendLogs(success_status, "Deploy Project", null)
    } catch (Exception e) {
        sendLogs(failed_status, "Deploy Project", e.toString())
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
def sendLogs(String status, String step, String error) {
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
    //curl
}