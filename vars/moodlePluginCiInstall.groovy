def call(String command = '') {

    def db = "${DB}"
    if (!db) {
        error("DB environment variable must be set. Are you running moodlePluginCiInstall outside of the container?")
    }

    def tag = "${TAG}"
    if (tag && (command.contains('--plugin ')) {
        // We need to extract the plugin location.
        Matcher matcher = command  =~ /(?:--plugin )(.*)(?:(?: --)|$)/
        matcher.find()
        def path = matcher.group(1)
        // The files will be one directory level down.
        cp -r ../$path $path
    }

    // The DB env variable can probably be used directly by moodle-plugin-ci, but this lets us check the user hasn't
    // tried to pass it more easily.
    def installParams = [
        "db-type": db,
        "db-user": "jenkins",
        "db-pass": "jenkins",
        "db-host": "127.0.0.1"
    ]

    installParams.each { key, val ->
        if (command.contains('--' + key)) {
            error("The following parameters cannot be passed: db-type, db-user, db-pass, db-host")
        }
    }

    def installCommand = ['moodle-plugin-ci install']
    installParams.each { key, val ->
        installCommand << "--${key} ${val}"
    }
    installCommand << command

    sh installCommand.join(' ')

}