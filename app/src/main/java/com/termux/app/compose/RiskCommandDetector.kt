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

    /** 危险命令类型 */
    enum class RiskType(val displayName: String) {
        DD("dd 磁盘写入"),
        SU_SUDO("su/sudo 提权"),
        FORMAT("格式化/分区"),
        RM_RF_ROOT("递归删除根目录"),
        FORK_BOMB("fork bomb 资源耗尽"),
        KERNEL_MODULE("内核模块操作"),
        RAW_DISK_WRITE("原始磁盘写入"),
        SHUTDOWN_REBOOT("关机/重启")
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
        RiskPattern(
            Pattern.compile("""\bdd\s+.*of=/dev/(?:null|zero|random|urandom|(?:sd[a-z]|nvme\d+n\d+|mmcblk\d+|loop\d+|ram\d+|zram\d+|vd[a-z]|xvd[a-z]|blk\d+)(?:p?\d*)?)""", Pattern.CASE_INSENSITIVE),
            RiskType.DD,
            "检测到 dd 直接写入设备节点，可能导致数据永久丢失或设备损坏",
            requireNative = false
        ),
        // dd 管道写入 (dd ... | dd ... 或通过管道写入设备)
        RiskPattern(
            Pattern.compile("""\bdd\s+.*\|\s*dd\s+.*of=/dev/""", Pattern.CASE_INSENSITIVE),
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
            Pattern.compile("""^\s*(?:su|sudo)\b"""),
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
            Pattern.compile("""\b(?:cat|cp|pv|tee|gunzip|gzip|bzip2|xz|zstd)\s+.*>\s*/dev/(?:sd[a-z]|nvme\d+n\d+|mmcblk\d+|loop\d+|ram\d+|zram\d+|vd[a-z]|xvd[a-z]|blk\d+)""", Pattern.CASE_INSENSITIVE),
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
        for (rp in riskPatterns) {
            if (rp.requireNative && !inNativeTermux) continue
            val matcher = rp.pattern.matcher(trimmed)
            if (matcher.find()) {
                return DetectionResult(
                    isDangerous = true,
                    riskType = rp.type,
                    matchedCommand = trimmed,
                    description = rp.description,
                    isWindowsDiskCommand = rp.isWindowsDiskCommand
                )
            }
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
}
