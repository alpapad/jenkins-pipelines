package support

class Helper implements Serializable {
	private final def script

	private def currentVersion
	private def projectVersion
	private def artifactId
	private def groupId
	private def projectName
	
	Helper(def script, def projectName) {
		this.script = script
		this.projectName = projectName
	}

	def init() {
		this.currentVersion = this.script.sh(script: 'mvn -B help:evaluate -Dexpression=project.version -q -DforceStdout', returnStdout: true).trim()

		this.projectVersion = this.currentVersion.replaceAll('-SNAPSHOT', '').trim()

		this.artifactId = this.script.sh(script: 'mvn -B help:evaluate -Dexpression=project.artifactId -q -DforceStdout', returnStdout: true).trim()

		this.groupId = this.script.sh(script: 'mvn -B help:evaluate -Dexpression=project.groupId -q -DforceStdout', returnStdout: true).trim()
		
		if(this.projectName == null || this.projectName.isEmpty()){
			this.projectName = this.groupId + "-" + this.artifactId
		}
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
		return (this.script.env.BRANCH_NAME == "develop" || this.script.env.BRANCH_NAME == "master" || this.script.env.BRANCH_NAME == "hotfix")
	}

	boolean isPublish(){
		return this.isRelease() || this.isTag()
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
		return this.groupId
	}

	String getArtifactId() {
		return this.artifactId
	}

	String getNextVersion() {
		def versionParts = this.projectVersion.tokenize('.');
		int fixVersion = versionParts[versionParts.size -1].toInteger()  + 1;
		versionParts[versionParts.size -1] = "${fixVersion}"
		return "${versionParts.join('.')}-SNAPSHOT"
	}

	String getReleaseVersion() {
		return this.projectVersion
	}

	String getVersion() {
		return this.currentVersion
	}

	// If last commit message starts with [ci skip], we skip the build
	boolean isSkip() {
		def result = this.script.sh (script: "git log -1 | grep '.*\\[ci skip\\].*'", returnStatus: true)
		if(result == 0){
			this.script.currentBuild.result = 'NOT_BUILT'
			return true;
		}
		return false;
	}

	String getSonarProject() {
		def branchName = this.script.env.BRANCH_NAME;

		if(this.isTag()){
			branchName = "tag/" + this.script.env.TAG_NAME;
		}

		if(this.isPr()) {
			def prBranch = this.script.env.CHANGE_BRANCH
			def prKey = this.script.env.CHANGE_ID
			def prBase = this.script.env.CHANGE_TARGET
			
			return "-Dsonar.pullrequest.key=\"${prKey}\" -Dsonar.pullrequest.branch=\"${prBranch}\" -Dsonar.pullrequest.base=\"${prBase}\" -Dsonar.projectName=\"${this.projectName}\" -Dsonar.projectKey=\"${this.projectName}\""
		} else {
			return "-Dsonar.branch.name=\"${branchName}\" -Dsonar.projectName=\"${this.projectName}\" -Dsonar.projectKey=\"${this.projectName}\""
		}
	}

	String getSonarProjectId() {
		return this.projectName
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
		this.script.allure results: [
			[path: 'target/allure-results']
		]
	}
	
	def updateJira(def projectKey, def buildVersion) {
		def versionParts = this.projectVersion.tokenize('.');
		versionParts.remove(versionParts.size -1);

		def jiraRelease = "${versionParts.join('.')}"
		
		// Ensure the release version exists in the project
		this.script.jiraProjectRelease projectKey: projectKey, releaseVersion: jiraRelease
		
		// Find all issues either from commit messages or from the branch name itslef. Limit them to those of the 
		// specific project
		def jiraIssues = this.script.jiraListIssues branchName: this.script.env.BRANCH_NAME, projectKey: projectKey
		
		
		jiraIssues.each { issue ->
			this.script.echo "Found issue ${issue}, setting build version: ${buildVersion}, release: ${jiraRelease}"
			// Update issue in project by setting the fixVersion and the build version (custom field)
			this.script.jiraFixVersion projectKey: projectKey, buildVersionFieldId: 'customfield_11765', issueKey: issue, buildVersion: buildVersion, releaseVersion: jiraRelease
			//this.script.jiraAddComment comment: "{panel:bgColor=#97FF94}{code}Code was added to address this issue in build ${build}{code} {panel}", idOrKey: issue, site: jiraServer
			//def fixedInBuild = [fields: [customfield_10121: build]] // This is a custom field named "Fixed in Build"
			//this.script.jiraEditIssue idOrKey: issue, issue: fixedInBuild, site: jiraServer
		}
	}
}

