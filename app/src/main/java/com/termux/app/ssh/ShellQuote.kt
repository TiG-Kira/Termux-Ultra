package com.termux.app.ssh

/** Quote one shell argument, including embedded quotes, spaces and command substitutions. */
internal fun sshQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
