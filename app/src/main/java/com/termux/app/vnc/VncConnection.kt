package com.termux.app.vnc

data class VncConnection(
    @JvmField val id: String,
    @JvmField val name: String,
    @JvmField val host: String,
    @JvmField val port: Int,
    @JvmField val password: String,
    @JvmField val isFromTermux: Boolean = false,
    @JvmField val sessionName: String = ""
)