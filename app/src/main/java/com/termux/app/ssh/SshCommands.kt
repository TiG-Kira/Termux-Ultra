package com.termux.app.ssh

internal fun buildSshCommand(connection: SshConnection): String {
    val installCheck = "command -v ssh >/dev/null 2>&1 || pkg install -y openssh; command -v sshpass >/dev/null 2>&1 || pkg install -y sshpass"

    return when (connection.connectionType) {
        SshConnection.TYPE_LOCAL -> buildLocalSshCommand(connection, installCheck)
        SshConnection.TYPE_OPENPILOT -> buildOpenPilotSshCommand(connection, installCheck)
        SshConnection.TYPE_COMMA -> buildCommaSshCommand(connection, installCheck)
        else -> buildStandardSshCommand(connection, installCheck)
    }
}

private fun buildLocalSshCommand(connection: SshConnection, installCheck: String): String {
    val port = connection.port
    val user = connection.username
    val password = connection.password

    var sshCmd = "ssh -o StrictHostKeyChecking=accept-new"
    if (port != 22) {
        sshCmd += " -p $port"
    }

    if (password.isNotEmpty()) {
        sshCmd = "sshpass -p '${password.replace("'", "'\\''")}' $sshCmd"
    }

    sshCmd += " -l ${sshQuote(user)} -- localhost"

    return "$installCheck; $sshCmd"
}

private fun buildOpenPilotSshCommand(connection: SshConnection, installCheck: String): String {
    val host = connection.host
    val port = connection.port
    val user = connection.username
    val password = connection.password
    val keyPath = connection.privateKeyPath

    var sshCmd = "ssh -o StrictHostKeyChecking=accept-new"
    if (port != 22) {
        sshCmd += " -p $port"
    }
    if (keyPath.isNotEmpty()) {
        sshCmd += " -i ${sshQuote(keyPath)}"
    }

    if (password.isNotEmpty()) {
        sshCmd = "sshpass -p '${password.replace("'", "'\\''")}' $sshCmd"
    }

    sshCmd += " -l ${sshQuote(user)} -- ${sshQuote(host)}"

    return "$installCheck; $sshCmd"
}

private fun buildCommaSshCommand(connection: SshConnection, installCheck: String): String {
    val host = connection.host
    val port = connection.port
    val user = connection.username
    val keyPath = connection.privateKeyPath
    val isExternal = connection.deviceType == SshConnection.DEVICE_EXTERNAL

    if (isExternal) {
        val dongleId = connection.dongleId.ifEmpty { connection.host }

        var sshCmd = "ssh -o StrictHostKeyChecking=accept-new"
        if (keyPath.isNotEmpty()) {
            sshCmd += " -i ${sshQuote(keyPath)}"
        }

        val proxyPart = if (keyPath.isNotEmpty()) {
            "ssh -o StrictHostKeyChecking=accept-new -i ${sshQuote(keyPath)} -W %h:%p -l ${sshQuote(user)} -- ssh.comma.ai"
        } else {
            "ssh -o StrictHostKeyChecking=accept-new -W %h:%p -l ${sshQuote(user)} -- ssh.comma.ai"
        }

        sshCmd += " -o ${sshQuote("ProxyCommand=$proxyPart")}"

        sshCmd += " -l ${sshQuote(user)} -- ${sshQuote(dongleId)}"

        return "$installCheck; $sshCmd"
    } else {
        var sshCmd = "ssh -o StrictHostKeyChecking=accept-new"
        if (port != 22) {
            sshCmd += " -p $port"
        }
        if (keyPath.isNotEmpty()) {
            sshCmd += " -i ${sshQuote(keyPath)}"
        }

        sshCmd += " -l ${sshQuote(user)} -- ${sshQuote(host)}"

        return "$installCheck; $sshCmd"
    }
}

private fun buildStandardSshCommand(connection: SshConnection, installCheck: String): String {
    val host = connection.host
    val port = connection.port
    val user = connection.username
    val password = connection.password
    val keyPath = connection.privateKeyPath

    var sshCmd = "ssh -o StrictHostKeyChecking=accept-new"
    if (port != 22) {
        sshCmd += " -p $port"
    }
    if (keyPath.isNotEmpty()) {
        sshCmd += " -i ${sshQuote(keyPath)}"
    }

    if (password.isNotEmpty()) {
        sshCmd = "sshpass -p '${password.replace("'", "'\\''")}' $sshCmd"
    }

    sshCmd += " -l ${sshQuote(user)} -- ${sshQuote(host)}"

    return "$installCheck; $sshCmd"
}
