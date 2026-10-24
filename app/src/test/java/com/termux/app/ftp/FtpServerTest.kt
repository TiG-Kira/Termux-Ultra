package com.termux.app.ftp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class FtpServerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun session(test: ((String) -> String) -> Unit) {
        ServerSocket(0).use { listener ->
            val worker = thread(isDaemon = true) {
                FtpClientHandler(listener.accept(), "user", "password", temporary.root.absolutePath).handle()
            }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                assertTrue(reader.readLine().startsWith("220"))
                val command: (String) -> String = { line ->
                    writer.write(line + "\r\n"); writer.flush(); reader.readLine()
                }
                assertTrue(command("USER user").startsWith("331"))
                assertTrue(command("PASS password").startsWith("230"))
                test(command)
                command("QUIT")
            }
            worker.join(3000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun cannotRemoveRootOrNonEmptyDirectory() {
        val keep = temporary.newFolder("keep")
        File(keep, "data").writeText("important")
        session { command ->
            assertTrue(command("RMD /").startsWith("550"))
            assertTrue(command("RMD /keep").startsWith("550"))
        }
        assertEquals("important", File(keep, "data").readText())
    }

    @Test fun changingUserRevokesAuthentication() {
        session { command ->
            assertTrue(command("USER wrong").startsWith("530"))
            assertTrue(command("PWD").startsWith("530"))
        }
    }

    @Test fun occupiedPortIsNotReportedAsRunning() {
        ServerSocket(0).use { listener ->
            val server = FtpServer(listener.localPort, "user", "password", temporary.root.absolutePath)
            try {
                server.start()
                fail("Bind must fail")
            } catch (_: java.net.BindException) {
                assertFalse(server.isRunning())
            } finally {
                server.stop()
            }
        }
    }
}
