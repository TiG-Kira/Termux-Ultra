package com.termux.app.ftp

import java.io.File

internal object FtpPathPolicy {
    fun resolve(root: File, path: String): File {
        val canonicalRoot = root.canonicalFile
        val candidate = File(root, path.trimStart('/'))
        val file = candidate.canonicalFile
        require(file == canonicalRoot || file.path.startsWith(canonicalRoot.path + File.separator)) {
            "FTP path escapes root"
        }
        return candidate.absoluteFile
    }
}
