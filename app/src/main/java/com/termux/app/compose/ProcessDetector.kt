package com.termux.app.compose

import android.content.Context
import android.graphics.Color as AndroidColor
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile

/**
 * 运行时进程检测器：用于检测 QEMU 虚拟机数量、proot 容器是否运行等。
 *
 * 主要检测方式：
 *  - **QEMU 进程数**：通过 Termux shell 执行 `pgrep -c -f '[q]emu-system-x86_64'`，
 *    与 QemuVmActivity 虚拟机页面卡片上的"运行中"计数完全一致（包含容器内 QEMU 进程）。
 *    `[q]emu-system-x86_64` 正则技巧避免 pgrep 自身被计数。
 *  - **proot 容器**：直接遍历 /proc/<pid> 目录，检查 cmdline 中是否含 `proot -r <rootfs>`，
 *    用于判断 Linux 容器是否正在运行。
 */
object ProcessDetector {

    /** 挂起函数 */
    suspend fun countRunningQemu(context: Context): Int {
        return withContext(Dispatchers.IO) { countRunningQemuBlocking(context) }
    }

    /**
     * 阻塞函数：统计当前正在运行的 QEMU 虚拟机数量。
     *
     * 实现：使用 `pgrep -c -f '[q]emu-system-x86_64'` 命令，与 QemuVmActivity 虚拟机页面卡片上的
     * "运行中"计数完全一致。这种方式能正确检测到：
     *  - Termux 原生安装的 qemu-system-x86_64 进程
     *  - proot 容器内启动的 qemu-system-x86_64 进程（命令行中包含 qemu-system-x86_64 字符串）
     *
     * `[q]emu-system-x86_64` 中的方括号是正则技巧：让 pgrep 自身的命令行不匹配该模式，
     * 避免 pgrep 把自己算进结果里。
     *
     * 同步保证：本方法被 [TermuxService.buildNotification] (LiveUpdate 通知) 与
     * [QemuVmActivity] 虚拟机页面同时使用，保证两者显示的数量完全一致。
     */
    @JvmStatic
    fun countRunningQemuBlocking(context: Context): Int {
        return try {
            val shell = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh"
            val home = TermuxConstants.TERMUX_HOME_DIR_PATH
            val envClient = TermuxShellEnvironmentClient()
            val env = envClient.buildEnvironment(context, false, home)
            // 进程名可能是 qemu-system-x86_64 (Termux 原生) 或带路径的容器版本 /usr/bin/qemu-system-x86_64，
            // 用 pgrep -f 配合 [q]emu-system 过滤掉检测命令本身
            val process = Runtime.getRuntime().exec(
                arrayOf(
                    shell, "-c",
                    "(pgrep -c -f '[q]emu-system-x86_64' || echo 0) | tail -n 1"
                ),
                env,
                File(home)
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val countStr = reader.readLine()?.trim() ?: "0"
            reader.close()
            process.waitFor()
            countStr.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** 挂起函数 */
    suspend fun isProotContainerRunning(context: Context): Boolean {
        return withContext(Dispatchers.IO) { isProotContainerRunningBlocking(context) }
    }

    @JvmStatic
    fun isProotContainerRunningBlocking(context: Context): Boolean {
        try {
            val procDir = File("/proc")
            val entries = procDir.listFiles() ?: return false
            val myPid = android.os.Process.myPid()
            for (pidDir in entries) {
                if (!pidDir.isDirectory) continue
                val name = pidDir.name
                if (name.any { !it.isDigit() }) continue
                val pid = name.toIntOrNull() ?: continue
                if (pid == myPid) continue

                // 1) exe 指向 "proot" 或 "proot-distro" 等真实可执行文件？
                val exeFile = File(pidDir, "exe")
                val exeName = try {
                    exeFile.canonicalPath.substringAfterLast('/')
                } catch (_: Exception) {
                    ""
                }
                val exeLooksLikeProot = exeName == "proot" || exeName.startsWith("proot-")

                // 2) 如果 exe 匹配 proot，再检查 cmdline 是否有 "-r" 参数指向某个 rootfs
                //    （排除非容器用途的 proot 调用，例如 proot --help）
                if (exeLooksLikeProot) {
                    val argv = readCmdline(pidDir) ?: continue
                    // argv[0] 是可执行文件，argv[1..] 是参数
                    var i = 1
                    while (i < argv.size) {
                        if (argv[i] == "-r" && i + 1 < argv.size) {
                            val rootFsPath = argv[i + 1]
                            // 只要 -r 指向的路径包含 "rootfs" 或 "debian-container" 等典型关键字，
                            // 就认为是正在运行中的容器
                            if (rootFsPath.contains("/rootfs") ||
                                rootFsPath.contains("debian-container") ||
                                rootFsPath.contains("container")) {
                                return true
                            }
                        }
                        i++
                    }
                }

                // 3) 兜底：exe 不直接叫 proot（例如 proot-distro 包装脚本里再 exec proot），
                //    但 cmdline 里出现 "/.../usr/bin/proot" 和 "-r" 组合，也算
                val argv = readCmdline(pidDir) ?: continue
                val argv0 = argv.firstOrNull() ?: ""
                val argvJoined = argv.joinToString(" ")
                if ((argv0.contains("bin/proot") || argvJoined.contains(" /usr/bin/proot ")) &&
                    (argvJoined.contains(" -r /") || argvJoined.contains(" -r$ ") || argvJoined.contains(" -r\""))) {
                    if (argvJoined.contains("/rootfs") || argvJoined.contains("container")) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    /**
     * 读取 /proc/<pid>/cmdline -> 按 NUL 拆分为 argv 数组。
     * 文件不存在（进程已退出）或无法读取时返回 null。
     */
    private fun readCmdline(pidDir: File): Array<String>? {
        val cmdline = File(pidDir, "cmdline")
        if (!cmdline.canRead()) return null
        return try {
            val bytes = cmdline.readBytes()
            if (bytes.isEmpty()) return null
            val text = bytes.toString(Charsets.UTF_8)
            // 按 NUL('\u0000') 分隔；最后一项也是空串，要过滤掉
            text.split('\u0000').filter { it.isNotEmpty() }.toTypedArray()
        } catch (_: Exception) {
            null
        }
    }

    data class DetectionResult(
        val qemuCount: Int,
        val containerRunning: Boolean
    )

    @JvmStatic
    fun detectAllBlocking(context: Context): DetectionResult {
        return DetectionResult(
            qemuCount = countRunningQemuBlocking(context),
            containerRunning = isProotContainerRunningBlocking(context)
        )
    }
}
