package com.termux.app.ftp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FtpPathPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resolvesRootAndChild() {
        val root = temporary.newFolder("home")
        assertEquals(root.canonicalFile, FtpPathPolicy.resolve(root, "/"))
        assertEquals(File(root, "data").canonicalFile, FtpPathPolicy.resolve(root, "/data"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentTraversal() {
        FtpPathPolicy.resolve(temporary.newFolder("home"), "/../secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrefixSibling() {
        FtpPathPolicy.resolve(temporary.newFolder("home"), "/../home-other/secret")
    }
}
