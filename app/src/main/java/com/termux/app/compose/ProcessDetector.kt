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

                val argv = readCmdline(pidDir) ?: continue
                if (argv.isEmpty()) continue
                val argv0 = argv[0]
                val argvJoined = argv.joinToString(" ")

                // 判断是否为 proot 进程：
                //  - exe 名为 "proot"，或
                //  - cmdline[0] 路径含 "bin/proot"（如 /data/.../usr/bin/proot）
                val exeFile = File(pidDir, "exe")
                val exeName = try {
                    exeFile.canonicalPath.substringAfterLast('/')
                } catch (_: Exception) {
                    ""
                }
                val isProotProcess = exeName == "proot" ||
                    argv0.contains("bin/proot") ||
                    argv0.endsWith("proot")

                if (!isProotProcess) continue

                // proot 容器启动时必定带 rootfs 参数，支持以下所有变体：
                //   -r <path>          （proot 原生短选项）
                //   -R <path>          （proot 短选项，等价于 -r + 推荐 defaults）
                //   --rootfs <path>    （proot 长选项）
                //   --rootfs=<path>    （proot-distro 常用格式，单参数）
                // 只要含任意一种 rootfs 参数，就判定为容器在运行
                val hasRootfsArg = argv.any { arg ->
                    arg == "-r" || arg == "-R" || arg == "--rootfs" || arg.startsWith("--rootfs=")
                }
                // 兜底：cmdline 中直接出现 " -r /" 或 " -R /"（proot 原生短选项 + 绝对路径）
                val hasRootfsInline = argvJoined.contains(" -r /") || argvJoined.contains(" -R /")

                if (hasRootfsArg || hasRootfsInline) return true
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
