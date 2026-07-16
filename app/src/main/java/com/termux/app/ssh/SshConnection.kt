package com.termux.app.ssh

data class SshConnection(
    @JvmField val id: String,
    @JvmField val name: String,
    @JvmField val host: String,
    @JvmField val port: Int = 22,
    @JvmField val username: String,
    @JvmField val password: String = "",
    @JvmField val privateKeyPath: String = ""
)
