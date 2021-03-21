#!groovy

node("executor") {
    checkout scm

   def commitHash  = sh(returnStdout: true, script: 'git rev-parse HEAD | cut -c-7').trim()
   def imageTag = "${env.BUILD_NUMBER}-${commitHash}"

    def sbt = "sbt -Dsbt.log.noformat=true"
    def pennsieveNexusCreds = usernamePassword(
        credentialsId: "pennsieve-nexus-ci-login",
        usernameVariable: "PENNSIEVE_NEXUS_USER",
        passwordVariable: "PENNSIEVE_NEXUS_PW"
    )

    stage("Build") {
        withCredentials([pennsieveNexusCreds]) {
            sh "$sbt clean compile"
        }
    }

    stage("Test") {
        withCredentials([pennsieveNexusCreds]) {
            try {
                sh "$sbt coverageOn test"
            } finally {
                junit '**/target/test-reports/*.xml'
            }
        }
    }

    stage("Test Coverage") {
        withCredentials([pennsieveNexusCreds]) {
            sh "$sbt coverageReport"
        }
    }

    stage("Publish") {
        withCredentials([pennsieveNexusCreds]) {
            sh "$sbt clean docker"
        }

        sh "docker tag pennsieve/upload-service:latest pennsieve/upload-service:$imageTag"
        sh "docker push pennsieve/upload-service:latest"
        sh "docker push pennsieve/upload-service:$imageTag"
    }

    if (["main"].contains(env.BRANCH_NAME)) {
        stage("Deploy") {
            build job: "service-deploy/pennsieve-non-prod/us-east-1/dev-vpc-use1/dev/upload-service",
            parameters: [
                string(name: 'IMAGE_TAG', value: imageTag),
                string(name: 'TERRAFORM_ACTION', value: 'apply')
            ]
        }

        stage("E2E") {
            build job: "pennsieve/end-to-end-tests/main"
        }
    }
}
