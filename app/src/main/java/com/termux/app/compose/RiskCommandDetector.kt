package com.termux.app.compose

import java.util.regex.Pattern

/**
 * 高危命令检测器。
 *
 * 检测规则覆盖：
 * - dd (直接磁盘写入)
 * - su/sudo 在 Termux 原生环境下
 * - 格式化命令 (mkfs, mkfs.ext4 等)
 * - fdisk/分区操作
 * - rm -rf / (递归删除根)
 * - 其他危险操作 (fork bomb, 内核模块操作等)
 */
object RiskCommandDetector {

    /** 单条命令/脚本行的最大检测长度：超长输入先截断再检测，防止正则灾难性回溯导致服务端卡死 */
    private const val MAX_DETECT_INPUT = 8 * 1024
    /** detectScript 单行脚本的最大检测长度 */
    private const val MAX_LINE_DETECT = 4 * 1024
    /** expandShellVarsPublic 输入上限 */
    private const val MAX_EXPAND_INPUT = 64 * 1024

    /** 危险命令类型 */
    enum class RiskType(val displayName: String) {
        DD("dd 磁盘写入"),
        SU_SUDO("su/sudo 提权"),
        FORMAT("格式化/分区"),
        RM_RF_ROOT("递归删除根目录"),
        FORK_BOMB("fork bomb 资源耗尽"),
        KERNEL_MODULE("内核模块操作"),
        RAW_DISK_WRITE("原始磁盘写入"),
        SHUTDOWN_REBOOT("关机/重启"),
        SETPROP("系统属性修改"),
        ROOT_CMD("ROOT 特权命令")
    }

    data class DetectionResult(
        val isDangerous: Boolean,
        val riskType: RiskType?,
        val matchedCommand: String,
        val description: String,
        /** 是否为 Windows 磁盘级命令（format, diskpart, bcdedit 等） */
        val isWindowsDiskCommand: Boolean = false
    )

    /** 危险命令模式列表，按优先级排序。 */
    private val riskPatterns = listOf(
        // dd 直接磁盘写入 - of= 指向块设备节点（含分区号，如 sda1, nvme0n1p1, mmcblk0p1）
        // 用 [^\n;|&]* 替代 .*：限定扫描范围，避免长行上灾难性回溯
        RiskPattern(
            Pattern.compile("""\bdd\b\s+[^\n;|&]*of=/dev/(?:null|zero|random|urandom|(?:sd[a-z]|nvme\d+n\d+|mmcblk\d+|loop\d+|ram\d+|zram\d+|vd[a-z]|xvd[a-z]|blk\d+)(?:p?\d*)?)""", Pattern.CASE_INSENSITIVE),
            RiskType.DD,
            "检测到 dd 直接写入设备节点，可能导致数据永久丢失或设备损坏",
            requireNative = false
        ),
        // dd 管道写入 (dd ... | dd ... 或通过管道写入设备)
        RiskPattern(
            Pattern.compile("""\bdd\b\s+[^\n;|&]*\|\s*dd\b\s+[^\n;|&]*of=/dev/""", Pattern.CASE_INSENSITIVE),
            RiskType.DD,
            "检测到 dd 直接写入设备节点，可能导致数据永久丢失或设备损坏",
            requireNative = false
        ),
        // 通用 dd 命令检测（不带设备路径，但仍可能危险）
        RiskPattern(
            Pattern.compile("""\bdd\b""", Pattern.CASE_INSENSITIVE),
            RiskType.DD,
            "检测到 dd 命令，可能会大量写入或覆盖数据，请确认操作目标",
            requireNative = false
        ),
        // su/sudo 在 Termux 原生环境（容器和虚拟机内正常使用不拦截）
        RiskPattern(
            Pattern.compile("""(?:^|[;|&])\s*(?:su|sudo)\b"""),
            RiskType.SU_SUDO,
            "检测到 su/sudo 提权命令，在 Termux 原生环境下可能导致权限混乱或安全风险",
            requireNative = true
        ),

        // 格式化命令
        RiskPattern(
            Pattern.compile("""\b(mkfs(?:\.[a-z0-9]+)?|mkfs\.(?:ext[234]|fat|vfat|ntfs|xfs|btrfs|zfs)|newfs(?:_msdos)?)""", Pattern.CASE_INSENSITIVE),
            RiskType.FORMAT,
            "检测到格式化命令，将清除磁盘上所有数据且不可恢复",
            requireNative = false
        ),
        // fdisk / parted 分区操作
        RiskPattern(
            Pattern.compile("""\b(fdisk|parted|sfdisk|cfdisk|gdisk|sgdisk)\b""", Pattern.CASE_INSENSITIVE),
            RiskType.FORMAT,
            "检测到分区操作命令，可能导致分区表损坏和数据丢失",
            requireNative = false
        ),
        // rm -rf / 或 rm -rf /*
        RiskPattern(
            Pattern.compile("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*\s+|--recursive\s+)(?:/\s*$|/\*\s*$|/(?:etc|bin|sbin|usr|var|lib|home|root)\b)"""),
            RiskType.RM_RF_ROOT,
            "检测到递归删除系统目录命令，可能导致系统完全损坏",
            requireNative = false
        ),
        // fork bomb
        RiskPattern(
            Pattern.compile(""":\(\)\{\s*:\s*\|\s*:\s*&\s*\}\s*;?\s*"""),
            RiskType.FORK_BOMB,
            "检测到 fork bomb，将耗尽系统资源导致设备卡死",
            requireNative = false
        ),
        RiskPattern(
            Pattern.compile("""\bfork\s*bomb\b""", Pattern.CASE_INSENSITIVE),
            RiskType.FORK_BOMB,
            "检测到 fork bomb，将耗尽系统资源导致设备卡死",
            requireNative = false
        ),
        // 内核模块操作
        RiskPattern(
            Pattern.compile("""\b(insmod|rmmod|modprobe|modinfo|lsmod)\b""", Pattern.CASE_INSENSITIVE),
            RiskType.KERNEL_MODULE,
            "检测到内核模块操作，可能导致系统不稳定或安全风险",
            requireNative = false
        ),
        // 原始磁盘写入 (除 dd 外的 raw 写入)
        RiskPattern(
            Pattern.compile("""\b(?:cat|cp|pv|tee|gunzip|gzip|bzip2|xz|zstd)\b[^\n;|]*>\s*/dev/(?:sd[a-z]|nvme\d+n\d+|mmcblk\d+|loop\d+|ram\d+|zram\d+|vd[a-z]|xvd[a-z]|blk\d+)""", Pattern.CASE_INSENSITIVE),
            RiskType.RAW_DISK_WRITE,
            "检测到直接写入块设备操作，可能导致数据永久丢失",
            requireNative = false
        ),
        // 关机/重启
        RiskPattern(
            Pattern.compile("""^\s*(?:shutdown|reboot|poweroff|halt|init\s+[06])\b""", Pattern.CASE_INSENSITIVE),
            RiskType.SHUTDOWN_REBOOT,
            "检测到关机/重启命令，将强制终止所有进程",
            requireNative = false
        ),
        // Windows 磁盘/分区毁灭性操作（format, diskpart, bcdedit, PowerShell cmdlets）
        RiskPattern(
            Pattern.compile("""^\s*format\s+[a-zA-Z]:""", Pattern.CASE_INSENSITIVE),
            RiskType.FORMAT,
            "检测到 Windows format 命令，将格式化指定分区，数据不可恢复",
            requireNative = false,
            isWindowsDiskCommand = true
        ),
        RiskPattern(
            Pattern.compile("""^\s*diskpart\b""", Pattern.CASE_INSENSITIVE),
            RiskType.FORMAT,
            "检测到 diskpart 命令，可清除磁盘分区表或擦除全部扇区",
            requireNative = false,
            isWindowsDiskCommand = true
        ),
        RiskPattern(
            Pattern.compile("""^\s*bcdedit\b""", Pattern.CASE_INSENSITIVE),
            RiskType.FORMAT,
            "检测到 bcdedit 命令，可修改或删除系统启动配置，导致系统无法引导",
            requireNative = false,
            isWindowsDiskCommand = true
        ),
        RiskPattern(
            Pattern.compile("""^\s*Format-Volume\b""", Pattern.CASE_INSENSITIVE),
            RiskType.FORMAT,
            "检测到 PowerShell Format-Volume 命令，将格式化指定卷",
            requireNative = false,
            isWindowsDiskCommand = true
        ),
        RiskPattern(
            Pattern.compile("""^\s*Clear-Disk\b""", Pattern.CASE_INSENSITIVE),
            RiskType.FORMAT,
            "检测到 PowerShell Clear-Disk 命令，将清空磁盘全部数据",
            requireNative = false,
            isWindowsDiskCommand = true
        ),
        // setprop 系统属性修改（ROOT 环境下可修改系统关键属性）
        RiskPattern(
            Pattern.compile("""\bsetprop\s+(?:persist\.|ro\.|sys\.)""", Pattern.CASE_INSENSITIVE),
            RiskType.SETPROP,
            "检测到 setprop 系统属性修改命令，可能影响系统稳定性",
            requireNative = true
        ),
        // setprop 通用检测
        RiskPattern(
            Pattern.compile("""^\s*setprop\b""", Pattern.CASE_INSENSITIVE),
            RiskType.SETPROP,
            "检测到 setprop 命令，修改系统属性需谨慎",
            requireNative = true
        ),
        // ROOT 环境下的高危特权命令
        RiskPattern(
            Pattern.compile("""\bchmod\s+(-[a-zA-Z]*\s+)?777\b""", Pattern.CASE_INSENSITIVE),
            RiskType.ROOT_CMD,
            "检测到 chmod 777，将文件设为全局可读写执行，存在安全风险",
            requireNative = true
        ),
        // ROOT 环境下直接操作 /system /vendor 等系统分区
        RiskPattern(
            Pattern.compile("""\b(?:mount|remount)\s+(?:-o\s+)?(?:rw|ro|noatime)\s+/(?:system|vendor|product|odm)""", Pattern.CASE_INSENSITIVE),
            RiskType.ROOT_CMD,
            "检测到系统分区挂载操作，可能导致系统无法启动",
            requireNative = true
        ),
        // ROOT 下的 SELinux 操作
        RiskPattern(
            Pattern.compile("""\b(?:setenforce|selinuxenabled|getenforce)\b""", Pattern.CASE_INSENSITIVE),
            RiskType.ROOT_CMD,
            "检测到 SELinux 操作，修改 SELinux 策略可能降低系统安全性",
            requireNative = true
        )
    )

    private data class RiskPattern(
        val pattern: Pattern,
        val type: RiskType,
        val description: String,
        /** 如果为 true，则仅在原生 Termux 环境下生效（容器/虚拟机内跳过） */
        val requireNative: Boolean,
        /** 是否为 Windows 磁盘级命令 */
        val isWindowsDiskCommand: Boolean = false
    )

    /**
     * Shell 变量与命令拼接预处理。
     *
     * 解析 shell 风格的变量赋值和展开：
     *   a="rm -rf /"           → 变量表{a="rm -rf /"}
     *   $a                      → rm -rf /
     *   ${a}                    → rm -rf /
     *   CMD=su; $CMD -c ls     → su -c ls
     *   mycmd="sudo"; $mycmd shutdown → sudo shutdown
     *   "rm -rf /" | sh         → rm -rf / (管道右半展开)
     *
     * 只做安全近似解析（不 fork 子进程），目的是让检测器看到用户实际要执行的内容。
     */
    fun expandShellVarsPublic(input: String): String {
        // 输入上限保护：超长内容截断，避免正则处理巨大字符串拖慢/拖死服务端
        var s = if (input.length > MAX_EXPAND_INPUT) input.take(MAX_EXPAND_INPUT) else input

        // 1. 提取并展开变量赋值：KEY=VALUE 后面紧跟使用
        //    处理 "CMD=su -c ls; $CMD ..." 这种拼接
        val varAssignPattern = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'|(\S+))""")
        val varMap = mutableMapOf<String, String>()

        // 从整条命令里提取变量赋值（按分号/换行/&& 分段）
        val segments = s.split(Regex("""[;\n]|&&|\|\|"""))
        val expandedSegments = mutableListOf<String>()
        for (seg in segments) {
            var seg2 = seg.trim()
            // 提取赋值
            for (m in varAssignPattern.findAll(seg2)) {
                val key = m.groupValues[1]
                val value = m.groupValues[2] ?: m.groupValues[3] ?: m.groupValues[4] ?: ""
                if (key.length < 12) varMap[key] = value
            }
            // 展开 $VAR 和 ${VAR}
            seg2 = seg2.replace(Regex("""\$\{(\w+)\}""")) { m ->
                varMap[m.groupValues[1]] ?: m.value
            }
            seg2 = seg2.replace(Regex("""\$(\w+)""")) { m ->
                varMap[m.groupValues[1]] ?: m.value
            }
            expandedSegments.add(seg2)
        }
        s = expandedSegments.joinToString("; ")

        // 2. 管道展开：A | B 取 B 的实际含义（shell 下是 B 执行 A 的输出）
        //    用户写 "rm -rf / | sh" 实际 rm -rf / 才是危险的
        s = s.replace(Regex("""^\s*cat\s+\S+\s*\|\s*"""), "")

        // 3. quote 剥离（检测时不需要引号）
        s = s.replace(Regex("""["']"""), "")

        return s
    }

    /**
     * 检测命令是否为高危命令。

     *
     * @param command 待检测的命令
     * @param inNativeTermux 是否运行在原生 Termux 环境下（非容器/虚拟机）。
     *                       为 false 时，su/sudo 等仅在原生环境下才危险的命令不会被拦截。
     */
    @JvmStatic
    fun detect(command: String?, inNativeTermux: Boolean = true): DetectionResult {
        if (command.isNullOrBlank()) {
            return DetectionResult(false, null, "", "")
        }

        val trimmed = command.trim()
        // 超长命令截断后检测：正则匹配成本与输入长度相关，必须限制上限
        val detectTarget = if (trimmed.length > MAX_DETECT_INPUT) {
            trimmed.take(MAX_DETECT_INPUT)
        } else trimmed
        // 先做 shell 变量展开/拼接解析，让检测器看到实际要执行的内容
        val expanded = expandShellVarsPublic(detectTarget)
        for (rp in riskPatterns) {
            if (rp.requireNative && !inNativeTermux) continue
            // 展开后没匹配到，再试原始命令（展开可能破坏了原始含义）
            val found = rp.pattern.matcher(expanded).find() || rp.pattern.matcher(detectTarget).find()
            if (!found) continue
            return DetectionResult(
                isDangerous = true,
                riskType = rp.type,
                matchedCommand = trimmed,
                description = rp.description,
                isWindowsDiskCommand = rp.isWindowsDiskCommand
            )
        }

        return DetectionResult(false, null, trimmed, "")
    }

    /** 批量检测（用于检测命令列表中是否有高危命令） */
    fun detectAll(commands: List<String>, inNativeTermux: Boolean = true): List<DetectionResult> {
        return commands.map { detect(it, inNativeTermux) }.filter { it.isDangerous }
    }

    /** 判断是否为高危命令（简单布尔接口） */
    @JvmStatic
    fun isDangerous(command: String?, inNativeTermux: Boolean = true): Boolean =
        detect(command, inNativeTermux).isDangerous

    /** Java 友好的检测接口 */
    @JvmStatic
    fun isDangerous(command: String?): Boolean = detect(command).isDangerous

    /**
     * 脚本检测结果。
     * @param lineNumber 行号（从 1 开始）
     * @param lineContent 该行内容
     * @param detection 检测到的危险信息
     */
    data class ScriptDetectionResult(
        val lineNumber: Int,
        val lineContent: String,
        val detection: DetectionResult
    )

    /**
     * 检测脚本文件中的危险命令。
     * @param scriptContent 脚本文件内容
     * @param inNativeTermux 是否运行在原生 Termux 环境
     * @return 检测到的危险命令列表（空列表表示安全）
     */
    fun detectScript(scriptContent: String, inNativeTermux: Boolean = true): List<ScriptDetectionResult> {
        if (scriptContent.isBlank()) return emptyList()

        val lines = scriptContent.lines()
        val results = mutableListOf<ScriptDetectionResult>()

        for ((index, line) in lines.withIndex()) {
            val trimmedLine = line.trim()
            // 跳过空行和注释
            if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) continue

            // 单行超长截断：压缩/混淆脚本常为超长单行，整行匹配会触发灾难性回溯拖死服务端
            val detectLine = if (trimmedLine.length > MAX_LINE_DETECT) {
                trimmedLine.take(MAX_LINE_DETECT)
            } else trimmedLine
            // 先展开变量赋值/替换
            val expandedLine = expandShellVarsPublic(detectLine)
            // 移除常见的 shell 前缀（变量赋值前的命令等）
            val detection = detect(expandedLine, inNativeTermux)
            if (detection.isDangerous) {
                results.add(
                    ScriptDetectionResult(
                        lineNumber = index + 1,
                        lineContent = trimmedLine,
                        detection = detection
                    )
                )
            }
        }

        return results
    }
}
