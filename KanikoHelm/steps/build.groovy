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
    def tagVersion = config.tagVersion

    // Kubernetes stuff
    def jenkinsServiceAccount = config.jenkinsServiceAccount
    def dockerHubCredentialsSecret = config.dockerHubCredentialsSecret
    def secretKey = config.secretKey
    
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
                    sh "helm upgrade --install ${tenantID} ./${repoFolder}/${chartFolder} --set image.tag=${tagVersion} --set image.repository=${dockerHubUser}/${tenantID}"
                }
            }
        }
    }
}