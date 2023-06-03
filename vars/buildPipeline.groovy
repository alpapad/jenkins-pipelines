import support.Helper


def call(Closure body) {
    
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def helper = new Helper(this)
    def nextDevelVersion = '';
    def nextReleaseVersion = '';
    def release = false;
    def publish = false;
    def snapshot = false;
    def skip = false;
    def sonarProject = "";
    def projectName = pipelineParams.name;
    def ignoreSonarErrors = false;
    def sonarProjectName;
       
    pipeline {

        agent any
        tools { 
            maven 'MVN3_8' 
            jdk 'JDK11' 
        }
        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '5'))
        }
        stages {
            stage('Init') {
                steps {
                    script {
                        release = helper.isRelease();
                        snapshot = helper.isSnapshot();
                        publish = helper.isPublish();
                        
                        nextDevelVersion = helper.getNextVersion()
                        nextReleaseVersion = helper.getReleaseVersion()

                        skip = helper.isSkip();
                        sonarProject = helper.getSonarProject(projectName)
                        sonarProjectName = helper.getSonarProjectId(projectName);
                        
                        if(pipelineParams.ignoreSonarErrors != null){
                           ignoreSonarErrors = pipelineParams.ignoreSonarErrors 
                        }
                    }
                    sh "echo ${nextDevelVersion} ${nextReleaseVersion} publish=${publish} release=${release} snapshot=${snapshot} skip=${skip}, projectName=${projectName} sonarProject=${sonarProject}"
                }
            }
            stage("Build and Publish") {
                when {
                    expression { skip == false }
                }
                stages {
                    stage('Prepare Release') {
                        when {
                            expression { release == true }
                        }
                        steps {
                            sh "mvn -B versions:set -DnewVersion=${nextReleaseVersion} -DprocessAllModules=true -Pittests"
                        }
                    }

                    stage('Build and Test') {
                        steps {
                            script{
                                def extraBuildArgs = ""
                                if (fileExists('suppressions.xml')) {
                                    extraBuildArgs += " -DsuppressionFile=suppressions.xml"
                                }

                                sh "mvn -B clean verify -U -Dmaven.test.failure.ignore=true -Pcicd -Dsnapshot.build=${snapshot} ${extraBuildArgs}"
                                helper.testReport()
                            }
                        }
                    }
                    stage('SonarQube analysis') {
                        steps {
                             script {
                                withSonarQubeEnv('Sonarqube') {
                                    sh "mvn -B -Pcicd sonar:sonar ${sonarProject} -Dsonar.exclusions=**/pom.xml  -Dsonar.dependencyCheck.jsonReportPath=target/dependency-check-report.json -Dsonar.dependencyCheck.xmlReportPath=target/dependency-check-report.xml -Dsonar.dependencyCheck.htmlReportPath=target/dependency-check-report.html"
                                }
                                sleep (25)
                                helper.qualityGate(sonarProjectName, env.BRANCH_NAME, 2, ignoreSonarErrors)
                            }
                        }
                    }

                    stage('Publish') {
                        when {
                            expression { publish == true && helper.hasNoFailures()}
                        }
                        steps {
                            sh "mvn -B deploy -DskipTests=true"
                        }
                    }

                    stage("Release") {
                        when {
                            expression { release == true && helper.hasNoFailures()}
                        }
                        steps {
                            script {
                                releaseMsg  = helper.getReleaseMessage(nextReleaseVersion);
                                bumpMsg  = helper.geBumpMessage(nextDevelVersion);
                                currentBuild.displayName = "v" + nextReleaseVersion +  " (" + currentBuild.number + ")"

                                sh 'git config advice.addEmptyPathspec false'
                                sh "mvn -B versions:commit -Pittests"
                                withCredentials([
                                    gitUsernamePassword(credentialsId: 'GIT_CREDENTIALS', gitToolName: 'Default')
                                ]) {
                                    sh "git ls-files -m | grep pom.xml  | xargs git add"
                                    sh "git commit -m \"${releaseMsg}\" --allow-empty"
                                    sh "git tag -a v${nextReleaseVersion} -m \"${releaseMsg}\""
                                    sh "git push origin HEAD:develop --tags"
    
                                    sh "mvn -B versions:set -DnewVersion=${nextDevelVersion} -DprocessAllModules=true"
    
                                    sh "git ls-files -m | grep pom.xml  | xargs git add"
                                    sh "git commit -m \"${bumpMsg}\" --allow-empty"
                                    sh "git push origin HEAD:develop --tags"
                                   }
                            }
                        }
                    }
                }
            }
        }
    }
    
}
