package com.termux.app.ftp

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FtpServer(
    private val port: Int,
    private val username: String,
    private val password: String,
    private val rootDir: String
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null

    fun start() {
        if (running.get()) return
        running.set(true)
        serverThread = thread {
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                while (running.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        thread {
                            FtpClientHandler(clientSocket, username, password, rootDir).handle()
                        }
                    } catch (e: Exception) {
                        if (running.get()) e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverThread = null
        serverSocket = null
    }

    fun isRunning(): Boolean = running.get()
}

class FtpClientHandler(
    private val clientSocket: Socket,
    private val username: String,
    private val password: String,
    private val rootDir: String
) {
    private var currentDir = "/"
    private var authenticated = false
    private var dataPort: Int = 0
    private var dataServerSocket: ServerSocket? = null
    private var dataSocket: Socket? = null
    private var passiveMode = false
    private var userOk = false

    fun handle() {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), "UTF-8"))
            val writer = PrintWriter(OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"), true)
            sendResponse(writer, 220, "FTP Server Ready")

            var line: String? = reader.readLine()
            while (line != null) {
                val command = line ?: break
                val cmd = command.uppercase().substringBefore(" ")
                val args = command.substringAfter(" ", "").trim()
                handleCommand(writer, cmd, args)
                line = reader.readLine()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                dataServerSocket?.close()
                dataSocket?.close()
                clientSocket.close()
            } catch (e: Exception) {}
        }
    }

    private fun handleCommand(writer: PrintWriter, cmd: String, args: String) {
        when (cmd) {
            "USER" -> handleUser(writer, args)
            "PASS" -> handlePass(writer, args)
            "QUIT" -> handleQuit(writer)
            "PWD" -> handlePwd(writer)
            "XPWD" -> handlePwd(writer)
            "CWD" -> handleCwd(writer, args)
            "XCWD" -> handleCwd(writer, args)
            "CDUP" -> handleCdup(writer)
            "XCUP" -> handleCdup(writer)
            "LIST" -> handleList(writer, args)
            "NLST" -> handleNlst(writer, args)
            "RETR" -> handleRetr(writer, args)
            "STOR" -> handleStor(writer, args)
            "DELE" -> handleDele(writer, args)
            "MKD" -> handleMkd(writer, args)
            "XMKD" -> handleMkd(writer, args)
            "RMD" -> handleRmd(writer, args)
            "XRMD" -> handleRmd(writer, args)
            "SIZE" -> handleSize(writer, args)
            "SYST" -> sendResponse(writer, 215, "UNIX Type: L8")
            "TYPE" -> sendResponse(writer, 200, "Type set to I")
            "PASV" -> handlePasv(writer)
            "EPSV" -> handleEpsv(writer)
            "PORT" -> handlePort(writer, args)
            "EPRT" -> handlePort(writer, args)
            "NOOP" -> sendResponse(writer, 200, "OK")
            "FEAT" -> handleFeat(writer)
            "OPTS" -> handleOpts(writer, args)
            "REST" -> sendResponse(writer, 350, "Restarting at 0. Ready for transfer.")
            "ABOR" -> sendResponse(writer, 226, "Abort successful")
            "STAT" -> sendResponse(writer, 211, "Status OK")
            else -> sendResponse(writer, 502, "Command not implemented")
        }
    }

    private fun handleUser(writer: PrintWriter, args: String) {
        userOk = args == username
        if (userOk) {
            sendResponse(writer, 331, "User name okay, need password")
        } else {
            sendResponse(writer, 530, "Not logged in")
        }
    }

    private fun handlePass(writer: PrintWriter, args: String) {
        if (!userOk) {
            sendResponse(writer, 503, "Login with USER first")
            return
        }
        if (args == password) {
            authenticated = true
            sendResponse(writer, 230, "User logged in")
        } else {
            sendResponse(writer, 530, "Not logged in")
        }
    }

    private fun handleQuit(writer: PrintWriter) {
        sendResponse(writer, 221, "Goodbye")
        try {
            clientSocket.close()
        } catch (e: Exception) {}
    }

    private fun handlePwd(writer: PrintWriter) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        sendResponse(writer, 257, "\"$currentDir\" is current directory")
    }

    private fun handleCwd(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        val target = if (args.isEmpty()) "/" else args
        val newDir = if (target.startsWith("/")) normalizePath(target) else normalizePath("$currentDir/$target")
        val file = File(rootDir + newDir)
        if (file.exists() && file.isDirectory) {
            currentDir = newDir
            sendResponse(writer, 250, "Directory successfully changed")
        } else {
            sendResponse(writer, 550, "Failed to change directory")
        }
    }

    private fun handleCdup(writer: PrintWriter) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        if (currentDir == "/") {
            sendResponse(writer, 250, "Directory successfully changed")
            return
        }
        val parent = normalizePath(currentDir).let { if (it == "/") "/" else File(it).parent?.let { p -> if (p.isEmpty() || p == "\\") "/" else p.replace("\\", "/") } ?: "/" }
        val file = File(rootDir + parent)
        if (file.exists() && file.isDirectory) {
            currentDir = if (parent == "") "/" else parent
            sendResponse(writer, 250, "Directory successfully changed")
        } else {
            sendResponse(writer, 550, "Failed to change directory")
        }
    }

    private fun handlePasv(writer: PrintWriter) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        try {
            dataServerSocket?.close()
            dataServerSocket = ServerSocket(0, 1)
            dataPort = dataServerSocket!!.localPort
            val localAddr = clientSocket.inetAddress.hostAddress?.replace(".", ",") ?: "127,0,0,1"
            val p1 = dataPort / 256
            val p2 = dataPort % 256
            sendResponse(writer, 227, "Entering Passive Mode ($localAddr,$p1,$p2)")
            passiveMode = true
        } catch (e: Exception) {
            sendResponse(writer, 425, "Can't open data connection")
        }
    }

    private fun handleEpsv(writer: PrintWriter) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        try {
            dataServerSocket?.close()
            dataServerSocket = ServerSocket(0, 1)
            dataPort = dataServerSocket!!.localPort
            sendResponse(writer, 229, "Entering Extended Passive Mode (|||$dataPort|)")
            passiveMode = true
        } catch (e: Exception) {
            sendResponse(writer, 425, "Can't open data connection")
        }
    }

    private fun handlePort(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        sendResponse(writer, 502, "PORT not supported, use PASV")
    }

    private fun handleFeat(writer: PrintWriter) {
        writer.println("211-Features:")
        writer.println(" PASV")
        writer.println(" EPSV")
        writer.println(" SIZE")
        writer.println(" MDTM")
        writer.println(" UTF8")
        writer.println("211 End")
        writer.flush()
    }

    private fun handleOpts(writer: PrintWriter, args: String) {
        if (args.uppercase(Locale.ROOT).startsWith("UTF8")) {
            sendResponse(writer, 200, "UTF8 option accepted")
        } else {
            sendResponse(writer, 501, "Option not recognized")
        }
    }

    private fun openDataConnection(writer: PrintWriter): Socket? {
        return try {
            if (passiveMode && dataServerSocket != null) {
                val socket = dataServerSocket!!.accept()
                dataSocket = socket
                socket
            } else {
                null
            }
        } catch (e: Exception) {
            sendResponse(writer, 425, "Can't open data connection")
            null
        }
    }

    private fun closeDataConnection() {
        try {
            dataSocket?.close()
        } catch (e: Exception) {}
        try {
            dataServerSocket?.close()
        } catch (e: Exception) {}
        dataSocket = null
        dataServerSocket = null
        passiveMode = false
    }

    private fun getRealPath(args: String): String {
        val target = if (args.isEmpty()) currentDir else args
        return if (target.startsWith("/")) normalizePath(target) else normalizePath("$currentDir/$target")
    }

    private fun handleList(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        sendResponse(writer, 150, "Here comes the directory listing")
        val dataConn = openDataConnection(writer)
        try {
            if (dataConn != null) {
                val dirPath = getRealPath(args)
                val dir = File(rootDir + dirPath)
                val files = if (dir.exists() && dir.isDirectory) {
                    dir.listFiles() ?: emptyArray()
                } else {
                    emptyArray()
                }
                val out = PrintWriter(OutputStreamWriter(dataConn.getOutputStream(), "UTF-8"), false)
                val dateFormat = SimpleDateFormat("MMM dd yyyy", Locale.ENGLISH)
                for (f in files) {
                    val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                    val size = f.length()
                    val dateStr = dateFormat.format(Date(f.lastModified()))
                    val name = f.name
                    out.println("$perms 1 ftp ftp $size $dateStr $name")
                }
                out.flush()
                try {
                    dataConn.shutdownOutput()
                } catch (e: Exception) {}
                sendResponse(writer, 226, "Directory send OK")
            } else {
                sendResponse(writer, 425, "Can't open data connection")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                sendResponse(writer, 451, "Requested action aborted")
            } catch (e2: Exception) {}
        } finally {
            closeDataConnection()
        }
    }

    private fun handleNlst(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        sendResponse(writer, 150, "Here comes the directory listing")
        val dataConn = openDataConnection(writer)
        try {
            if (dataConn != null) {
                val dirPath = getRealPath(args)
                val dir = File(rootDir + dirPath)
                val files = if (dir.exists() && dir.isDirectory) {
                    dir.listFiles() ?: emptyArray()
                } else {
                    emptyArray()
                }
                val out = PrintWriter(OutputStreamWriter(dataConn.getOutputStream(), "UTF-8"), false)
                for (f in files) {
                    out.println(f.name)
                }
                out.flush()
                try {
                    dataConn.shutdownOutput()
                } catch (e: Exception) {}
                sendResponse(writer, 226, "Directory send OK")
            } else {
                sendResponse(writer, 425, "Can't open data connection")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                sendResponse(writer, 451, "Requested action aborted")
            } catch (e2: Exception) {}
        } finally {
            closeDataConnection()
        }
    }

    private fun handleRetr(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        val filePath = getRealPath(args)
        val file = File(rootDir + filePath)
        if (!file.exists() || !file.isFile) {
            sendResponse(writer, 550, "Failed to open file")
            return
        }
        sendResponse(writer, 150, "Opening BINARY mode data connection for ${file.name}")
        val dataConn = openDataConnection(writer)
        try {
            if (dataConn != null) {
                val input = FileInputStream(file)
                val output = dataConn.getOutputStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
                input.close()
                sendResponse(writer, 226, "Transfer complete")
            } else {
                sendResponse(writer, 425, "Can't open data connection")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                sendResponse(writer, 451, "Requested action aborted")
            } catch (e2: Exception) {}
        } finally {
            closeDataConnection()
        }
    }

    private fun handleStor(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        val filePath = getRealPath(args)
        val file = File(rootDir + filePath)
        file.parentFile?.mkdirs()
        sendResponse(writer, 150, "Ok to send data")
        val dataConn = openDataConnection(writer)
        try {
            if (dataConn != null) {
                val input = dataConn.getInputStream()
                val output = FileOutputStream(file)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
                output.close()
                input.close()
                sendResponse(writer, 226, "Transfer complete")
            } else {
                sendResponse(writer, 425, "Can't open data connection")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                sendResponse(writer, 451, "Requested action aborted")
            } catch (e2: Exception) {}
        } finally {
            closeDataConnection()
        }
    }

    private fun handleDele(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        val filePath = getRealPath(args)
        val file = File(rootDir + filePath)
        if (file.exists() && file.isFile && file.delete()) {
            sendResponse(writer, 250, "Delete operation successful")
        } else {
            sendResponse(writer, 550, "Delete operation failed")
        }
    }

    private fun handleMkd(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        val dirPath = getRealPath(args)
        val dir = File(rootDir + dirPath)
        if (dir.mkdirs()) {
            sendResponse(writer, 257, "\"$dirPath\" directory created")
        } else {
            sendResponse(writer, 550, "Create directory operation failed")
        }
    }

    private fun handleRmd(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        val dirPath = getRealPath(args)
        val dir = File(rootDir + dirPath)
        if (dir.exists() && dir.isDirectory && dir.deleteRecursively()) {
            sendResponse(writer, 250, "Remove directory operation successful")
        } else {
            sendResponse(writer, 550, "Remove directory operation failed")
        }
    }

    private fun handleSize(writer: PrintWriter, args: String) {
        if (!authenticated) {
            sendResponse(writer, 530, "Not logged in")
            return
        }
        val filePath = getRealPath(args)
        val file = File(rootDir + filePath)
        if (file.exists() && file.isFile) {
            sendResponse(writer, 213, file.length().toString())
        } else {
            sendResponse(writer, 550, "Could not get file size")
        }
    }

    private fun sendResponse(writer: PrintWriter, code: Int, message: String) {
        writer.println("$code $message")
    }

    private fun normalizePath(path: String): String {
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
        val stack = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else {
                stack.add(part)
            }
        }
        return "/" + stack.joinToString("/")
    }
}
