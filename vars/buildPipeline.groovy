import support.Helper


def call(Closure body) {
    
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def nextDevelVersion = '';
    def nextReleaseVersion = '';
    def release = false;
    def publish = false;
    def snapshot = false;
    def pr = false;
    def skip = false;
    def runSonar = true;
    def sonarProject = "";
    def projectName = pipelineParams.name;
	def jiraProject = pipelineParams.jiraProject;
    def ignoreSonarErrors = false;
    def sonarProjectName;
       
	def helper = new Helper(this, projectName)
	
    pipeline {

        agent any
        tools { 
            maven 'MVN' 
            jdk 'JDK11' 
        }
        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '5'))
            office365ConnectorWebhooks([[
                startNotification: true,
                notifySuccess: true,
                notifyAborted: true,
                notifyNotBuilt: true,
                notifyUnstable: true,
                notifyFailure: true,
                notifyBackToNormal: true
            ]])
        }
        stages {
            stage('Init') {
                steps {
                    script {
                        helper.init()

                        release = helper.isRelease();
                        snapshot = helper.isSnapshot();
                        publish = helper.isPublish();
                        pr = helper.isPr();
                        
                        // next devel version with -SNAPSHOT
                        nextDevelVersion = helper.getNextVersion()
                        // version without -SNAPSHOT (if any)
                        nextReleaseVersion = helper.getReleaseVersion()

                        // check if we should skip this build
                        skip = helper.isSkip();
                        // sonar related 
                        sonarProject = helper.getSonarProject()
                        sonarProjectName = helper.getSonarProjectId();
                        if(pipelineParams.ignoreSonarErrors != null){
                           ignoreSonarErrors = pipelineParams.ignoreSonarErrors 
                        }
                    }
                    echo "Project info: nextDevelVersion=${nextDevelVersion} releaseVersion=${nextReleaseVersion} publish=${publish} release build=${release} snapshot=${snapshot} skip=${skip}, projectName=${projectName} sonarProject=${sonarProject}"
                    sh "export"
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
                            // Remove -SNAPSHOT from poms as this is a release 
                            sh "mvn -B versions:set versions:set-property -Dproperty=revision -DgenerateBackupPoms=false -DnewVersion=${nextReleaseVersion} -DprocessAllModules=true -Pittests -Pcicd"
                        }
                    }

                    stage('Build and Test') {
                        steps {
                            lock('tests') {
                                script{
                                    def extraBuildArgs = ""
                                    if (fileExists('suppressions.xml')) {
                                        extraBuildArgs += " -DsuppressionFile=suppressions.xml"
                                    }
                                    sh "mvn -B clean verify -U -Dmaven.test.failure.ignore=true -Pcicd -Dsnapshot.build=${snapshot} ${extraBuildArgs}"
                                    helper.junitReport()
                                }
                            }
                        }
                    }
                    stage('Run component tests') {
                        when {
                            expression { pr == false }
                        }
                        steps {
                            lock('tests') {
                                script {
                                    try {
                                        sh "docker compose down | true"
                                        sh "docker compose rm | true"
                                        sh "docker volume prune -a -f | true"
                                        sh "mvn install -Pdocker-build -DskipTests=true"
                                        sh "SPRING_PROFILES=docker,jenkins docker compose up -d"
                                        sleep(60)
                                        sh "mvn -B verify -pl testing/smg-it-tests -am -Dmaven.test.failure.ignore=true -Ddependency-check.skip=true -Pit-tests -Pcicd -Dspring.profiles.active=jenkins"
                                        helper.junitReport()
                                    } finally {
                                        sh "docker compose down | true"
                                        sh "docker compose rm | true"
                                        sh "docker volume prune -a -f | true"
                                    }
                                }
                            }
                        }
                    }
                    stage('SonarQube analysis') {
                        when {
                            expression { runSonar == true }
                        }
                        steps {
                             script {
                                withSonarQubeEnv('sonar') {
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
                           script {
                               // Deploy jars in maven repository and images in registry
                               //echo "We don't deloy yet, do a local install"
                               try {
                                    sh "mvn -B deploy -DskipTests=true"
                               } catch (Exception e) {
                                    echo e.getMessage()
                               }
                               //sh "mvn -B install -DskipTests=true"
                           }
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

                                withCredentials([gitUsernamePassword(credentialsId: 'alpapad-jenkins-ap', gitToolName: 'Default')]) {
                                    sh "git ls-files -m | grep pom.xml  | xargs git add"
                                    sh "git commit -m \"${releaseMsg}\" --allow-empty"
                                    sh "git tag -f -a v${nextReleaseVersion} -m \"${releaseMsg}\""

                                    sh "mvn -B versions:set versions:set-property -Dproperty=revision -DgenerateBackupPoms=false -DnewVersion=${nextDevelVersion} -DprocessAllModules=true -Pittests -Pcicd"
    
                                    sh "git ls-files -m | grep pom.xml  | xargs git add"
                                    sh "git commit -m \"${bumpMsg}\" --allow-empty"
                                    //sh "git push origin HEAD:develop --tags"
                                    sh "git push origin HEAD:${env.BRANCH_NAME} --tags"
                                    //git push origin HEAD:refs/tags/alex2
                                }
                                // Update relevant jira issues with fix versions and build versions
                                helper.updateJira(jiraProject,nextReleaseVersion)
                                office365ConnectorSend message:"Released: SMG ${nextReleaseVersion}(${env.BUILD_NUMBER}) (<${env.BUILD_URL}|Open>) ${releaseMsg}" 
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    helper.testReport()
                }
            }
        }
    }
    
}
