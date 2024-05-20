def call(Map pipelineParams = [:], Closure body) {

    def tag = pipelineParams.tag ?: ''

    def buildTag = buildTag(tag)

    try {
        runContainers(pipelineParams, body)
    } finally {
        if (pipelineParams.clean != false) {
            // We want the workspace to clean unless it has been explicitly told not to.
            // If it is unset the value will be null, and therefor not evaluate to false.
            moodlePluginCleanWorkspace()
        }

        // Use "|| true" to prevent cleanup failing job.
        if (pipelineParams.withBehatServers) {
            sh "docker stop ${buildTag}-selenium || true"
        }

        sh "docker network rm ${buildTag} || true"
        // No prune is very important or all intermediate images will be removed on first build!
        sh "docker rmi --no-prune ${buildTag} || true"
    }

}

private def buildTag(String tag) {
        // Docker does not like upper case letters in tags.
        def buildTag = "${BUILD_TAG}".toLowerCase()

        // The BUILD_TAG documentation says slashes are replaced by dashes but this seems to be wrong (in Jenkins 2.263.4)
        buildTag = buildTag.replace('%2f', '-')
        buildTag = buildTag + '-' + tag
        return buildTag.toLowerCase()
}

private def runContainers(Map pipelineParams = [:], Closure body) {

    def php = pipelineParams.php ?: '7.4'
    def db = pipelineParams.db ?: 'mysql'
    def ciVersion = pipelineParams.ciVersion ?: '3';
    def withBehatServers = pipelineParams.withBehatServers
    def tag = pipelineParams.tag ?: ''
    def path = tag ? "${tag}/" : ''

    if (withBehatServers) {
        if (!(withBehatServers in ['chrome', 'firefox'])) {
            error('withBehatServers must be chrome or firefox')
        }
    }

    // Validate the database value before building the image.
    def installDb = null
    switch (db) {
        case 'mysql':
            installDb = 'mysqli'
            break
        case 'postgres':
            installDb = 'pgsql'
            break
        default:
            error("Unknown db type ${db}. Supported types: mysqli, postgres")
    }

    def dockerFileContents = libraryResource 'uk/ac/strath/myplace/Dockerfile'
    def phpIniFileContents = libraryResource 'uk/ac/strath/myplace/php/php-config.ini'

    def buildTag = buildTag(tag)

    // Create Dockerfile in its own directory to prevent unnecessary context being sent.
    def dockerDir = "${buildTag}-docker"
    def image = null
    dir(dockerDir) {
        writeFile(file: 'Dockerfile', text: dockerFileContents)
        writeFile(file: 'php/php-config.ini', text: phpIniFileContents)
        def jenkinsUserId = sh(script: 'id -u', returnStdout: true).trim()
        image = docker.build(buildTag, "--build-arg JENKINS_USERID=${jenkinsUserId} .")
    }

    // Create composer and npm cache directories if they don't exist.
    sh 'mkdir -p ${WORKSPACE}/.npm && chmod 777 ${WORKSPACE}/.npm \
            && mkdir -p ${WORKSPACE}/.composer && chmod 777 ${WORKSPACE}/.composer'

    sh "docker network create ${buildTag}"

    if (withBehatServers) {
        // We have checked earlier that it's either chrome or firefox.
        def seleniumImage = 'selenium/standalone-chrome:3'
        if (withBehatServers == 'firefox') {
            seleniumImage = 'selenium/standalone-firefox:3.141.59'
        }
        sh "docker run -d --rm --name=${buildTag}-selenium --network=${buildTag} --network-alias=selenium --shm-size=2g ${seleniumImage}"
    }

    // Nasty hack to get around the fact that we can't use withEnv to change the PATH on a container
    // (or any other method as far as I can see)
    // https://issues.jenkins.io/browse/JENKINS-49076
    def originalDockerPath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    def pathOnDocker = "${WORKSPACE}/${path}ci/bin:${originalDockerPath}"

    image.inside("-e PATH=${pathOnDocker} --network ${buildTag} --network-alias=moodle") {

        // Start database.
        switch (db) {
            case 'mysql':
                sh 'sudo service mysql start'
                break
            case 'postgres':
                sh 'sudo service postgresql start'
                break
            default:
                error("Unknown db type ${db}. Supported types: mysql, postgres")
        }

        sh "sudo update-alternatives --set php /usr/bin/php${php}"

        // Set composer and npm directories to allow caching of downloads between jobs.
        def installEnv = ["npm_config_cache=${WORKSPACE}/.npm", "COMPOSER_CACHE_DIR=${WORKSPACE}/.composer"]

        if (withBehatServers) {
            installEnv << "MOODLE_BEHAT_WDHOST=http://selenium:4444/wd/hub"
            installEnv << "MOODLE_BEHAT_WWWROOT=http://moodle:8000"
        }

        withEnv(installEnv) {
            sh ". \$NVM_DIR/nvm.sh >/dev/null && nvm use default && \
                composer create-project -n --no-dev --prefer-dist moodlehq/moodle-plugin-ci ${path}ci ^${ciVersion}"
        }

        // Preload env file with variables to work around withEnv not apparently being picked up by symfony.
        // This shouldn't be necessary so we should get rid of it once we understand the problem.
        def envFile = "${WORKSPACE}/${path}ci/.env"
        def envContent = "MOODLE_BEHAT_WDHOST=http://selenium:4444/wd/hub\n"
        envContent << "MOODLE_BEHAT_WWWROOT=http://moodle:8000"

        if (withBehatServers) {
            sh "php -S 0.0.0.0:8000 -t ${WORKSPACE}${path}/moodle &"
        }

        // Workaround for the withEnv below not appearing to work.
        envContent << "\nMOODLE_START_BEHAT_SERVERS=YES"

        writeFile(file: envFile, text: envContent)
        // DB variable is required by the moodlePluginCiInstall step.

        // The script has a flag to prevent the servers starting but appears to override it with an environment
        // variable if the plugin has behat tests (in TestSuiteInstaller::getBehatInstallProcesses())
        withEnv(["DB=${installDb}", "MOODLE_START_BEHAT_SERVERS=false", "TAG=${tag}"]) {
            dir(tag) {
                body()
            }
        }

    }

}
