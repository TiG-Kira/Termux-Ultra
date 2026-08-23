package com.termux.app.ssh

data class SshConnection(
    @JvmField val id: String,
    @JvmField val name: String,
    @JvmField val host: String,
    @JvmField val port: Int = 22,
    @JvmField val username: String,
    @JvmField val password: String = "",
    @JvmField val privateKeyPath: String = "",
    @JvmField val connectionType: String = "other",
    @JvmField val deviceType: String = "",
    @JvmField val dongleId: String = ""
) {
    companion object {
        const val TYPE_OTHER = "other"
        const val TYPE_LOCAL = "local"
        const val TYPE_OPENPILOT = "openpilot"
        const val TYPE_COMMA = "comma"

        const val DEVICE_INTERNAL = "internal"
        const val DEVICE_EXTERNAL = "external"
    }
}
