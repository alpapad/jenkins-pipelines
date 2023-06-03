package support

class Helper implements Serializable {
    private final def script
    private final def env1
    
    private def  projectVersion = null
    
    Helper(def script) {
        this.script = script
    }

    boolean isPr() {
       return this.script.env.BRANCH_NAME != null && this.script.env.BRANCH_NAME.startsWith("PR-");
    }

    boolean isTag(){
        return this.script.env.TAG_NAME != null && !this.script.env.TAG_NAME.isEmpty()
    }

    boolean isBranch(){
        return this.script.env.BRANCH_NAME != null && !this.script.env.BRANCH_NAME.isEmpty()
    }
    
    boolean isRelease(){
        return (this.script.env.BRANCH_NAME == "develop" || this.script.env.BRANCH_NAME == "master")
    }

    boolean isPublish(){
        return this.script.env.BRANCH_NAME == "develop" || this.script.env.BRANCH_NAME == "master" || this.isTag()
    }
    
    boolean isSnapshot(){
        return !(this.isRelease() || this.isPublish())
    }
    
    boolean hasNoFailures(){
       return  (this.script.currentBuild.result == null || this.script.currentBuild.result == 'SUCCESS')
    }

    String getReleaseMessage(def nextReleaseVersion) {
        return "[ci skip] New release for v${nextReleaseVersion}"
    }

    String geBumpMessage(def nextDevelVersion) {
        return "[ci skip] Version bump ${nextDevelVersion}"
    }

    String getGroupId() {
        return this.script.sh(script: 'mvn help:evaluate -Dexpression=project.groupId -q -DforceStdout', returnStdout: true).trim()
    }

    String getArtifactId() {
        return this.script.sh(script: 'mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout', returnStdout: true).trim()
    }

    String getNextVersion() {
        if(projectVersion == null) {
            projectVersion = this.script.sh(script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout', returnStdout: true).trim()
            projectVersion = projectVersion.replaceAll('-SNAPSHOT', '').trim()
        }
    
        def versionParts = projectVersion.tokenize('.');  
        def mayorVersionPart = versionParts[0];  
        def minorVersionPart = versionParts[1];  
        def fixVersionPart = versionParts[2]; 
        
        int fixVersion = fixVersionPart.toInteger()  + 1;  
        
        def developmentVersion = mayorVersionPart  + "." + minorVersionPart + "." + fixVersion + '-SNAPSHOT';  
    
        return developmentVersion
    }

    String getReleaseVersion() {
        if(projectVersion == null) {
            projectVersion = this.script.sh(script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout', returnStdout: true).trim()
            projectVersion = projectVersion.replaceAll('-SNAPSHOT', '').trim()
        }
        return projectVersion
    }

    String getVersion() {
         return this.script.sh(script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout', returnStdout: true).trim()
    }

    boolean isSkip() {
        def result = this.script.sh (script: "git log -1 | grep '.*\\[ci skip\\].*'", returnStatus: true)
        if(result == 0){
            this.script.currentBuild.result = 'NOT_BUILT'
            return true;
        }
        return false;

    }

    String getSonarProject(def name) {
        if(name == null || name.isEmpty()){
           name = this.getGroupId() + "-" + this.getArtifactId()
        }
        def branchName = this.script.env.BRANCH_NAME;

        if(this.isTag()){
            branchName = "tag/" + this.script.env.TAG_NAME;
        }

        return "-Dsonar.branch.name=\"${branchName}\" -Dsonar.projectName=\"${name}\" -Dsonar.projectKey=\"${name}\""
    }

    String getSonarProjectId(def name) {
        if(name == null || name.isEmpty()){
           name = this.getGroupId() + "-" + this.getArtifactId()
        }
        return name
    }
	
	def qualityGate(def project, def branch, def timeOut, def ignoreSonarErrors = false){
		if(! ignoreSonarErrors) {
			this.steps.timeout(time: timeOut, unit: 'MINUTES') {
				def qg = this.steps.waitForQualityGate()
				def STAGE_NAME = this.steps.STAGE_NAME;
				if (qg.status != 'OK') {
					this.steps.unstable(message: "${STAGE_NAME} Unstable due to quality gate failure: ${qg.status}")
				}
			}
		}
  }
  
	def testReport(){
		this.script.jacoco(execPattern: '**/*.exec')

		if(this.isPublish()){
			this.script.tar file: 'reports-unit-tests.tar', archive: true, overwrite: true, glob: '**/surefire-reports/*'
			this.script.tar file: 'reports-integration-tests.tar', archive: true, overwrite: true, glob: '**/failsafe-reports/*'
		
			this.script.tar file: 'reports-javadoc.tar', archive: true, overwrite: true, glob: './target/site/apidocs/**/*'
			this.script.tar file: 'reports-jacoco.tar', archive: true, overwrite: true, glob: './target/site/jacoco/**/*'
		}

		this.script.junit checksName: 'Unit tests', allowEmptyResults: true, skipMarkingBuildUnstable: false, testResults: '**/surefire-reports/*.xml'
		this.script.junit checksName: 'IT tests', allowEmptyResults: true, skipMarkingBuildUnstable: false, testResults: '**/failsafe-reports/*.xml'

		try{
			this.script.dependencyCheckPublisher pattern: 'target/dependency-check-report.xml'
		} catch(Exception ex) {
			steps.println "Publish dependency check findings got exception " + ex
		}
   }
}

