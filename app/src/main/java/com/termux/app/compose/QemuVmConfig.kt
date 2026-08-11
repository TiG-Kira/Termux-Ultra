package com.termux.app.compose

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 判断是否需要使用 proot 容器来运行 QEMU。
 * Android 15 (SDK 35) 及以上版本的 linker namespace 限制导致 Termux 原生的
 * qemu-system-* 无法解析系统库（如 libdng_sdk.so）引用的私有符号，
 * 表现为 "CANNOT LINK EXECUTABLE ... cannot locate symbol ... referenced by /system/lib64/..."。
 * 这时改用 proot Ubuntu/Debian 容器内的 QEMU 二进制，它完全使用容器自己的 libc 和系统库。
 */
fun shouldUseQemuInContainer(): Boolean = Build.VERSION.SDK_INT >= 35

/**
 * 音频输出模式
 * - disabled: 关闭
 * - vnc_rfb: 优先使用 VNC RFB 扩展通过 VNC 连接直接传音频；容器模式下若QEMU无vnc audio driver自动回退PulseAudio
 * - pa_follow_screen: PulseAudio 方案；进入VNC页面时开始播放，退出页面时停止（跟随VNC页面生命周期）
 * - pa_persist: PulseAudio 方案；虚拟机声音持续播放（即使VNC页面关闭，用户如果用其他PulseAudio客户端也能听）
 */
object AudioMode {
    const val DISABLED = "disabled"
    const val VNC_RFB = "vnc_rfb"
    const val PA_FOLLOW_SCREEN = "pa_follow_screen"
    const val PA_PERSIST = "pa_persist"
}

data class QemuVmConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mode: String,            // "existing_disk" | "install_iso" | "create_disk"
    val diskPath: String,        // 磁盘文件路径（shared 路径）
    val newDiskSizeGB: Int = 20, // 新建硬盘容量（install_iso / create_disk 模式使用）
    val newDiskFormat: String = "qcow2", // 新建硬盘格式（qcow2 / raw / vmdk）
    val isoPath: String? = null, // ISO 镜像路径（install_iso 或手动挂载时使用）
    val cpuCores: Int = 2,
    val memoryMB: Int = 1024,
    @Deprecated("Use audioMode instead; kept for backward compatibility with saved configs")
    val hasSound: Boolean = false,
    val audioMode: String = AudioMode.VNC_RFB, // 默认 VNC RFB 方案
    val shareDir: String = "\$HOME/storage/shared/Termux/Sharing",
    val bootOrder: List<String> = listOf("c"),
    val vncPort: Int = 5900,
    val diskInterface: String = "ide",   // 硬盘连接方式: ide / virtio / scsi / sata
    val machineType: String = "q35",     // 虚拟PC类型: pc / q35 / isapc
    // ── 性能优化字段（可选，默认值保持旧有行为） ──
    val cpuModelOverride: String? = null,      // 自定义 CPU 模型覆盖默认选择
    val enableShareDir: Boolean = true,       // 是否启用 9p 共享目录（安装阶段可关闭以减少开销）
    val ssdCacheMode: Boolean = false         // 磁盘是否启用 writeback 缓存模式（模拟 SSD）
) {
    /** 向后兼容：有效音频模式。audioMode=disabled 时若 hasSound=true 视为 vnc_rfb（旧数据迁移） */
    val effectiveAudioMode: String
        get() = when (audioMode) {
            AudioMode.DISABLED -> if (hasSound) AudioMode.VNC_RFB else AudioMode.DISABLED
            else -> audioMode
        }
    val effectiveHasSound: Boolean get() = effectiveAudioMode != AudioMode.DISABLED

    /**
     * 将 bootOrder 列表正确转换为 QEMU `-boot` 参数行。
     * QEMU -boot 语法规则：
     *   1. 设备顺序必须直接拼接字符：-boot order=dc (不是逗号分隔)
     *   2. 选项（menu=on 等）必须使用独立的 -boot 参数
     *   3. 不能写成 -boot order=d,c,menu=on (QEMU 会把逗号当参数分隔符解析)
     */
    internal fun buildBootArgs(): String {
        val deviceChars = bootOrder.filter { it.length == 1 && it[0] in "cdnpr" }.joinToString("")
        val options = bootOrder.filter { it.contains("=") }
        val sb = StringBuilder()
        if (deviceChars.isNotEmpty()) {
            sb.append("    -boot order=$deviceChars\n")
        }
        for (opt in options) {
            sb.append("    -boot $opt\n")
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * 生成 QEMU 启动脚本。
     * - Android 14 及以下：使用 Termux 原生的 qemu-system-x86_64。
     * - Android 15 (SDK 35) 及以上：自动进入 proot 容器，使用容器里的 qemu-system-x86_64，
     *   磁盘/ISO/共享目录都通过 proot bind mount 映射进容器。
     */
    fun generateScript(): String {
        return if (shouldUseQemuInContainer()) generateContainerScript()
        else generateNativeScript()
    }

    /**
     * Termux 原生环境下的启动脚本（Android <= 14）。
     */
    private fun generateNativeScript(): String {
        val sb = StringBuilder()
        sb.append("#!/data/data/com.termux/files/usr/bin/bash\n")
        sb.append("echo \"=== QEMU with VNC (Termux 原生): $name ===\"\n\n")

        // 1. 检查并安装 qemu-system-x86_64
        sb.append("# 检查并安装 qemu-system-x86_64\n")
        sb.append("if ! command -v qemu-system-x86_64 &> /dev/null; then\n")
        sb.append("    echo \"正在安装 qemu-system-x86-64...\"\n")
        sb.append("    pkg install -y qemu-system-x86-64\n")
        sb.append("    if ! command -v qemu-system-x86_64 &> /dev/null; then\n")
        sb.append("        echo \"错误: qemu-system-x86-64 安装失败\"\n")
        sb.append("        sleep 3\n")
        sb.append("        exit 1\n")
        sb.append("    fi\n")
        sb.append("fi\n")
        sb.append("echo \"qemu-system-x86_64 已就绪\"\n\n")

        // 2. 检查 storage/shared 映射
        sb.append("# 检查内部存储映射\n")
        sb.append("if [ ! -d \"\$HOME/storage/shared\" ]; then\n")
        sb.append("    echo \"正在设置内部存储映射...\"\n")
        sb.append("    termux-setup-storage\n")
        sb.append("    sleep 3\n")
        sb.append("    if [ ! -d \"\$HOME/storage/shared\" ]; then\n")
        sb.append("        echo \"错误: 无法创建存储映射，请在 Termux 中手动运行 termux-setup-storage\"\n")
        sb.append("        sleep 3\n")
        sb.append("        exit 1\n")
        sb.append("    fi\n")
        sb.append("fi\n")
        sb.append("echo \"内部存储映射已就绪\"\n\n")

        // 3. 创建共享目录
        sb.append("# 创建共享目录\n")
        sb.append("mkdir -p \"$shareDir\"\n")
        sb.append("echo \"共享目录: $shareDir\"\n")
        sb.append("echo \"提示: 您可以将文件放入此目录以便在虚拟机中使用\"\n\n")

        // 4. 如果是 install_iso / create_disk 模式，创建新硬盘
        if (mode == "install_iso" || mode == "create_disk") {
            sb.append("# 创建新硬盘\n")
            sb.append("# 确保硬盘所在目录存在\n")
            sb.append("mkdir -p \"\$(dirname \"$diskPath\")\"\n")
            sb.append("if [ ! -f \"$diskPath\" ]; then\n")
            sb.append("    echo \"正在创建新硬盘 (${newDiskSizeGB}GB, 格式 $newDiskFormat)...\"\n")
            sb.append("    qemu-img create -f $newDiskFormat \"$diskPath\" ${newDiskSizeGB}G\n")
            sb.append("    if [ ! -f \"$diskPath\" ]; then\n")
            sb.append("        echo \"错误: 硬盘创建失败\"\n")
            sb.append("        sleep 3\n")
            sb.append("        exit 1\n")
            sb.append("    fi\n")
            sb.append("fi\n")
            sb.append("echo \"硬盘已就绪: $diskPath\"\n\n")
        }

        // 4.5 根据用户音频模式配置 PulseAudio / VNC RFB（原生模式）
        if (effectiveHasSound) {
            sb.append("# 4.5 配置音频后端（用户选择模式: $effectiveAudioMode）\n")
            sb.append("USER_AUDIO_MODE=\"$effectiveAudioMode\"\n")
            sb.append("HAS_VNC_AUDIO=0\n")
            sb.append("HAS_PULSEAUDIO=0\n")
            sb.append("AUDIO_FLAG_FILE=\"\$HOME/.qemu_vm_audio_${id}.env\"\n")

            when (effectiveAudioMode) {
                AudioMode.VNC_RFB -> {
                    sb.append("echo \"音频模式: VNC RFB 扩展\"\n")
                    // 原生 Termux 的 qemu-system-x86-64 是带 vnc audio driver 的
                    sb.append("HAS_VNC_AUDIO=1\n")
                }
                AudioMode.PA_FOLLOW_SCREEN, AudioMode.PA_PERSIST -> {
                    sb.append("echo \"音频模式: PulseAudio (${if (effectiveAudioMode == AudioMode.PA_FOLLOW_SCREEN) "跟随VNC页面启停" else "持续播放"})\"\n")
                    sb.append("if ! command -v pulseaudio &> /dev/null; then\n")
                    sb.append("    echo \"正在安装 PulseAudio (Termux 原生音频服务)...\"\n")
                    sb.append("    pkg install -y pulseaudio 2>/dev/null || {\n")
                    sb.append("        echo \"警告: PulseAudio 安装失败，声音将不可用\"\n")
                    sb.append("        sleep 5\n")
                    sb.append("    }\n")
                    sb.append("fi\n")
                    sb.append("if command -v pulseaudio &> /dev/null; then\n")
                    sb.append("    # 清理所有旧配置文件，确保干净启动\n")
                    sb.append("    rm -rf \"\$HOME/.config/pulse/client.conf\" 2>/dev/null\n")
                    sb.append("    rm -rf \"\$HOME/.config/pulse/default.pa\" 2>/dev/null\n")
                    sb.append("    # 杀掉所有旧 PA 进程\n")
                    sb.append("    pulseaudio --kill 2>/dev/null || true\n")
                    sb.append("    sleep 0.5\n")
                    // 分步启动PA：先启动基础daemon，再用pactl加载模块
                    sb.append("    # 第一步：启动基础 PA daemon\n")
                    sb.append("    pulseaudio --start --log-target=syslog 2>/dev/null\n")
                    sb.append("    sleep 1\n")
                    // 验证PA daemon是否在运行
                    sb.append("    # 验证 PA daemon 是否在运行\n")
                    sb.append("    if ! pactl info 2>/dev/null | grep -q 'String: I\\'m using'; then\n")
                    sb.append("        echo 'PA daemon 未正常运行，尝试重启...'\n")
                    sb.append("        pulseaudio --kill 2>/dev/null || true\n")
                    sb.append("        sleep 0.5\n")
                    sb.append("        pulseaudio --start 2>/dev/null\n")
                    sb.append("        sleep 1\n")
                    sb.append("        if ! pactl info 2>/dev/null | grep -q 'String: I\\'m using'; then\n")
                    sb.append("            echo 'ERROR: PA daemon 无法启动，跳过音频'\n")
                    sb.append("            HAS_PULSEAUDIO=0\n")
                    sb.append("        else\n")
                    sb.append("            HAS_PULSEAUDIO=1\n")
                    sb.append("        fi\n")
                    sb.append("    else\n")
                    sb.append("        HAS_PULSEAUDIO=1\n")
                    sb.append("    fi\n")
                    // 如果PA daemon在运行，加载模块
                    sb.append("    if [ \"\$HAS_PULSEAUDIO\" = \"1\" ]; then\n")
                    sb.append("        # 第二步：加载必要的模块\n")
                    sb.append("        pactl load-module module-null-sink sink_name=auto_null sink_properties=device.description=Virtual-Sink >/dev/null 2>&1 || true\n")
                    sb.append("        sleep 0.5\n")
                    sb.append("        pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1;::1 port=4713 >/dev/null 2>&1 || true\n")
                    sb.append("        sleep 0.5\n")
                    sb.append("        pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=auto_null.monitor record=true port=4714 >/dev/null 2>&1 || true\n")
                    sb.append("        # 等待 TCP 端口就绪\n")
                    sb.append("        PA_READY=0\n")
                    sb.append("        for _ in 1 2 3 4 5 6 7 8 9 10; do\n")
                    sb.append("            if command -v ss >/dev/null 2>&1; then\n")
                    sb.append("                ss -ltn 2>/dev/null | grep -q ':4713 ' && { PA_READY=1; break; }\n")
                    sb.append("            elif command -v netstat >/dev/null 2>&1; then\n")
                    sb.append("                netstat -ltn 2>/dev/null | grep -q ':4713 ' && { PA_READY=1; break; }\n")
                    sb.append("            else\n")
                    sb.append("                (echo > /dev/tcp/127.0.0.1/4713) 2>/dev/null && { PA_READY=1; break; }\n")
                    sb.append("            fi\n")
                    sb.append("            sleep 0.5\n")
                    sb.append("        done\n")
                    sb.append("        if [ \"\$PA_READY\" = \"1\" ]; then\n")
                    sb.append("            echo \"PulseAudio TCP 4713 已就绪\"\n")
                    sb.append("        else\n")
                    sb.append("            echo \"WARN: PulseAudio TCP 未就绪，QEMU 音频不可用\"\n")
                    sb.append("            HAS_PULSEAUDIO=0\n")
                    sb.append("        fi\n")
                    sb.append("        echo \"PulseAudio 已启动\"\n")
                    sb.append("    fi\n")
                    sb.append("else\n")
                    sb.append("    echo \"警告: pulseaudio 未安装，声音不可用\"\n")
                    sb.append("    HAS_PULSEAUDIO=0\n")
                    sb.append("fi\n")
                }
            }

            // 写入音频模式标记文件（与容器模式一致，VNC 页面会读取此文件判断如何播放）
            sb.append("cat > \"\$AUDIO_FLAG_FILE\" <<ENV_EOF\n")
            sb.append("USER_AUDIO_MODE=\$USER_AUDIO_MODE\n")
            sb.append("HAS_VNC_AUDIO=\$HAS_VNC_AUDIO\n")
            sb.append("HAS_PULSEAUDIO=\$HAS_PULSEAUDIO\n")
            sb.append("PA_TCP_PORT=4713\n")
            sb.append("PA_SIMPLE_PROTO_PORT=4714\n")
            sb.append("ENV_EOF\n")
            sb.append("echo \"音频依赖检查完成 (mode=\$USER_AUDIO_MODE, vnc=\$HAS_VNC_AUDIO, pa=\$HAS_PULSEAUDIO)\"\n\n")
        } else {
            sb.append("echo \"跳过音频配置（声音已关闭）\"\n\n")
        }

        // 5. 构建并启动 QEMU 命令
        sb.append("# 启动 QEMU\n")
        sb.append("killall -9 qemu-system-x86_64 2>/dev/null\n")
        sb.append("sleep 1\n\n")

        val vncDisplay = vncPort - 5900

        // 将参数放入 bash 数组，避免 eval/引号嵌套造成的语法错误
        // 音频参数在 bash 脚本中动态生成，根据 PA 是否成功启动来决定
        sb.append("# ========= 音频参数计算：根据 HAS_VNC_AUDIO / HAS_PULSEAUDIO 决定 =========\n")
        sb.append("EXTRA_AUDIO_ARGS=()\n")
        sb.append("VNC_AUDIO_ARG=\"\"\n")
        sb.append("if [ \"\$HAS_VNC_AUDIO\" = \"1\" ]; then\n")
        sb.append("    # 方案A: VNC RFB 扩展 — 音频直接通过 VNC 连接传递\n")
        sb.append("    EXTRA_AUDIO_ARGS=(\n")
        sb.append("        -audiodev vnc,id=vnc_audio,server\n")
        sb.append("        -device ich9-intel-hda\n")
        sb.append("        -device hda-output,audiodev=vnc_audio\n")
        sb.append("    )\n")
        sb.append("    VNC_AUDIO_ARG=\",audiodev=vnc_audio\"\n")
        sb.append("elif [ \"\$HAS_PULSEAUDIO\" = \"1\" ]; then\n")
        sb.append("    # 方案B: PulseAudio — QEMU 通过 PulseAudio 后端输出声音\n")
        sb.append("    EXTRA_AUDIO_ARGS=(\n")
        sb.append("        -audiodev pa,id=pa_audio,server=tcp:127.0.0.1:4713\n")
        sb.append("        -device ich9-intel-hda\n")
        sb.append("        -device hda-output,audiodev=pa_audio\n")
        sb.append("    )\n")
        sb.append("    VNC_AUDIO_ARG=\"\"\n")
        sb.append("fi\n\n")
        sb.append("QEMU_ARGS=(\n")
        sb.append("    -M $machineType\n")
        // CPU 模型：优先用覆盖值，否则按机器类型选默认
        val cpuModelFinal = cpuModelOverride
            ?: if (machineType == "isapc" || machineType == "pc") "qemu64" else "core2duo"
        sb.append("    -cpu $cpuModelFinal\n")
        sb.append("    -accel tcg,thread=multi\n")
        sb.append("    -smp $cpuCores,cores=$cpuCores,threads=1,sockets=1\n")
        sb.append("    -m $memoryMB\n")
        sb.append("    -net user -net nic,model=virtio\n")
        // 音频参数由 bash 脚本动态生成
        sb.append("    \"\${EXTRA_AUDIO_ARGS[@]}\"\n")
        sb.append("    -vga virtio\n")
        sb.append("    -usb -device usb-tablet\n")
        sb.append("    -vnc localhost:$vncDisplay\${VNC_AUDIO_ARG}\n")
        sb.append("    -no-reboot\n")
        // SSD 级缓存选项（TCG 下模拟 SSD 行为，显著加速 I/O）
        // aio=threads 比 aio=native 兼容性更好（Termux/容器环境可能没有 native AIO）
        val driveCacheOption = if (ssdCacheMode) ",cache=writeback,aio=threads,discard=on,detect-zeroes=on" else ""
        // 根据硬盘连接方式生成参数
        when (diskInterface) {
            "virtio" -> {
                sb.append("    -drive file=\"$diskPath\",if=none,id=hd0$driveCacheOption\n")
                sb.append("    -device virtio-blk-pci,drive=hd0\n")
            }
            "scsi" -> {
                sb.append("    -drive file=\"$diskPath\",if=none,id=hd0$driveCacheOption\n")
                sb.append("    -device virtio-scsi-pci,id=scsi0\n")
                sb.append("    -device scsi-hd,drive=hd0,bus=scsi0.0\n")
            }
            "sata" -> {
                sb.append("    -drive file=\"$diskPath\",if=none,id=hd0$driveCacheOption\n")
                sb.append("    -device ich9-ahci,id=sata0\n")
                sb.append("    -device ide-hd,drive=hd0,bus=sata0.0\n")
            }
            else -> {
                // ide: 使用最简单的 -hda，通过全局参数指定缓存模式
                sb.append("    -hda \"$diskPath\"\n")
                if (ssdCacheMode) {
                    sb.append("    -global ide-hd.drive-cache=writeback\n")
                }
            }
        }
        if (isoPath != null) {
            sb.append("    -cdrom \"$isoPath\"\n")
        }
        sb.append("    -rtc base=localtime\n")
        sb.append(buildBootArgs()).append("\n")
        // 9p virtio 文件夹共享（可选，安装阶段关闭以减少设备开销）
        if (enableShareDir) {
            sb.append("    -fsdev local,security_model=mapped-file,id=fsdev_shared,path=\"$shareDir\"\n")
            sb.append("    -device virtio-9p-pci,id=fs0,fsdev=fsdev_shared,mount_tag=hostshare\n")
        }
        sb.append(")\n\n")

        sb.append("echo \"正在启动 QEMU 虚拟机...\"\n")
        sb.append("echo \"VNC 端口: $vncPort (display :$vncDisplay)\"\n")
        sb.append("echo \"请使用 VNC 客户端连接 localhost:$vncPort\"\n")
        sb.append("echo \"共享文件夹挂载方法（虚拟机内执行）：\"\n")
        sb.append("echo \"  Linux:   sudo mkdir -p /mnt/share && sudo mount -t 9p -o trans=virtio hostshare /mnt/share\"\n")
        sb.append("echo \"  Windows: 需要安装 9P 客户端驱动后使用\\\\?\\mnt\\hostshare 或第三方工具\"\n")
        // 后台启动 + 等待端口 LISTEN，与容器模式相同逻辑避免 Connection closed by server
        sb.append("QEMU_LOG=\"/data/data/com.termux/files/usr/tmp/.qemu_${id}.log\"\n")
        sb.append("nohup qemu-system-x86_64 \"\${QEMU_ARGS[@]}\" > \"\$QEMU_LOG\" 2>&1 &\n")
        sb.append("QEMU_PID=\$!\n")
        sb.append("VNC_LISTEN_OK=0\n")
        sb.append("for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do\n")
        sb.append("    if command -v ss >/dev/null 2>&1; then\n")
        sb.append("        ss -ltn 2>/dev/null | grep -qE \"[:.]${vncPort} \" && { VNC_LISTEN_OK=1; break; }\n")
        sb.append("    elif command -v netstat >/dev/null 2>&1; then\n")
        sb.append("        netstat -ltn 2>/dev/null | grep -qE \"[:.]${vncPort} \" && { VNC_LISTEN_OK=1; break; }\n")
        sb.append("    else\n")
        sb.append("        (echo > /dev/tcp/127.0.0.1/$vncPort) >/dev/null 2>&1 && { VNC_LISTEN_OK=1; break; }\n")
        sb.append("    fi\n")
        sb.append("    if ! kill -0 \$QEMU_PID 2>/dev/null; then\n")
        sb.append("        echo 'ERROR: QEMU 进程已异常退出，日志如下:'\n")
        sb.append("        cat \"\$QEMU_LOG\" 2>/dev/null\n")
        sb.append("        exit 1\n")
        sb.append("    fi\n")
        sb.append("    sleep 1\n")
        sb.append("done\n")
        sb.append("if [ \"\$VNC_LISTEN_OK\" = \"1\" ]; then\n")
        sb.append("    echo \"VNC 服务已就绪 (端口 $vncPort)\"\n")
        sb.append("else\n")
        sb.append("    echo 'WARN: 30s 内未检测到 VNC LISTEN，仍尝试接管前台'\n")
        sb.append("fi\n")
        sb.append("echo '虚拟机运行中，按 Ctrl+C 或关闭终端可停止'\n")
        sb.append("wait \$QEMU_PID 2>/dev/null\n")
        sb.append("QEMU_EXIT=\$?\n")
        sb.append("rm -f \"\$QEMU_LOG\"\n\n")

        sb.append("echo ''\n")
        sb.append("echo '========================================'\n")
        sb.append("echo 'QEMU 虚拟机已停止'\n")
        sb.append("echo '脚本执行完成'\n")
        sb.append("echo '========================================'\n")

        return sb.toString()
    }

    /**
     * proot 容器环境下的启动脚本（Android 15 / SDK 35+）。
     * 容器使用自己的 glibc / libstdc++，不再触碰 Android 系统库，
     * 从而绕过 linker namespace 对私有符号的限制。
     */
    private fun generateContainerScript(): String {
        val sb = StringBuilder()
        sb.append("#!/data/data/com.termux/files/usr/bin/bash\n")
        sb.append("echo \"=== QEMU with VNC (proot 容器, Android 17+): $name ===\"\n")
        sb.append("echo \"提示: Android 17 下 Termux 原生 QEMU 受 linker 限制无法启动\"\n")
        sb.append("echo \"     已自动切换为 proot Ubuntu/Debian 容器内的 QEMU\"\n\n")

        // ========== Termux 层：检查容器、QEMU 依赖 ==========
        sb.append("CONTAINER_DIR=\"\$HOME/debian-container\"\n")
        sb.append("RUN_SCRIPT=\"\$CONTAINER_DIR/run.sh\"\n\n")

        // 1. 检查 / 创建 proot 容器
        sb.append("# 1. 检查 proot Linux 容器\n")
        sb.append("if [ ! -f \"\$RUN_SCRIPT\" ] || [ ! -f \"\$CONTAINER_DIR/rootfs/bin/bash\" ]; then\n")
        sb.append("    echo \"容器不存在，开始下载 Ubuntu 24.04 rootfs (首次需要几分钟)...\"\n")
        sb.append("    if [ -f \"\$HOME/install_linux_container.sh\" ]; then\n")
        sb.append("        bash \"\$HOME/install_linux_container.sh\"\n")
        sb.append("    else\n")
        sb.append("        echo \"请先到资源页面执行 'Ubuntu 容器安装'，然后重试。\"\n")
        sb.append("        sleep 3\n")
        sb.append("        exit 1\n")
        sb.append("    fi\n")
        sb.append("fi\n")
        // 修复 run.sh 中可能的旧包名
        sb.append("if grep -q \"com\\.termux\\.ultra\" \"\$RUN_SCRIPT\" 2>/dev/null; then\n")
        sb.append("    sed -i 's/com\\.termux\\.ultra/com.termux/g' \"\$RUN_SCRIPT\"\n")
        sb.append("fi\n")
        sb.append("echo \"Linux 容器已就绪: \$CONTAINER_DIR\"\n\n")

        // 2. 检查容器里是否装了 QEMU，没装就自动 apt install
        sb.append("# 2. 检查容器内的 QEMU 安装\n")
        sb.append("if ! \"\$RUN_SCRIPT\" -c 'command -v qemu-system-x86_64' >/dev/null 2>&1; then\n")
        sb.append("    echo \"容器内还没装 QEMU，正在安装 qemu-system-x86、qemu-utils、genisoimage (需要几分钟)...\"\n")
        sb.append("    \"\$RUN_SCRIPT\" -c 'export DEBIAN_FRONTEND=noninteractive; apt update -y && apt install -y --no-install-recommends qemu-system-x86 qemu-system-gui qemu-utils genisoimage curl wget ca-certificates'\n")
        sb.append("    if ! \"\$RUN_SCRIPT\" -c 'command -v qemu-system-x86_64' >/dev/null 2>&1; then\n")
        sb.append("        echo \"错误: 容器内 QEMU 安装失败，请手动进入容器执行 apt install qemu-system-x86\"\n")
        sb.append("        sleep 3\n")
        sb.append("        exit 1\n")
        sb.append("    fi\n")
        sb.append("fi\n")
        sb.append("echo \"容器内 QEMU 已就绪\"\n\n")

        // 2.5 根据用户选择的音频模式配置音频后端
        if (effectiveHasSound) {
            sb.append("# 2.5 配置音频后端（用户选择模式: $effectiveAudioMode）\n")
            sb.append("USER_AUDIO_MODE=\"$effectiveAudioMode\"\n")
            sb.append("HAS_VNC_AUDIO=0\n")
            sb.append("HAS_PULSEAUDIO=0\n")

            when (effectiveAudioMode) {
                AudioMode.VNC_RFB -> {
                    // 方案A (VNC_RFB): 优先尝试 VNC audio driver，缺失则安装；全部失败回退 PulseAudio
                    sb.append("echo \"音频模式: VNC RFB 扩展（优先，失败回退 PulseAudio）\"\n")
                    sb.append("if \"\$RUN_SCRIPT\" -c 'qemu-system-x86_64 -audiodev help 2>&1 | grep -q \"^vnc\"'; then\n")
                    sb.append("    echo \"容器内 QEMU 已支持 VNC audio driver\"\n")
                    sb.append("    HAS_VNC_AUDIO=1\n")
                    sb.append("else\n")
                    sb.append("    echo \"容器内 QEMU 未检测到 vnc audio driver，尝试升级/安装补全...\"\n")
                    sb.append("    \"\$RUN_SCRIPT\" -c 'export DEBIAN_FRONTEND=noninteractive; apt update -y && apt install -y --no-install-recommends --reinstall qemu-system-x86 qemu-system-gui qemu-system-data qemu-system-modules-opengl qemu-system-modules-spice 2>/dev/null; DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends libpulse0 libasound2 2>/dev/null; true' 2>/dev/null || true\n")
                    sb.append("    if \"\$RUN_SCRIPT\" -c 'qemu-system-x86_64 -audiodev help 2>&1 | grep -q \"^vnc\"'; then\n")
                    sb.append("        echo \"安装完成，已启用 VNC audio driver\"\n")
                    sb.append("        HAS_VNC_AUDIO=1\n")
                    sb.append("    else\n")
                    sb.append("        echo \"VNC audio driver 不可用，回退到 PulseAudio（自动降级）\"\n")
                    sb.append("        USER_AUDIO_MODE=\"${AudioMode.PA_PERSIST}\"\n")
                    sb.append("    fi\n")
                    sb.append("fi\n")
                    // 如果 VNC driver 不可用，自动装 PulseAudio
                    sb.append("if [ \"\$HAS_VNC_AUDIO\" != \"1\" ]; then\n")
                    sb.append("    if ! \"\$RUN_SCRIPT\" -c 'command -v pulseaudio' >/dev/null 2>&1; then\n")
                    sb.append("        echo \"正在安装 PulseAudio (容器内，VNC 回退方案)...\"\n")
                    sb.append("        \"\$RUN_SCRIPT\" -c 'export DEBIAN_FRONTEND=noninteractive; apt update -y && apt install -y --no-install-recommends pulseaudio pulseaudio-utils libpulse0' 2>/dev/null || {\n")
                    sb.append("            echo \"警告: PulseAudio 安装失败，声音将不可用\"\n")
                    sb.append("            sleep 5\n")
                    sb.append("        }\n")
                    sb.append("    fi\n")
                    sb.append("    \"\$RUN_SCRIPT\" -c 'command -v pulseaudio' >/dev/null 2>&1 && HAS_PULSEAUDIO=1\n")
                    sb.append("fi\n")
                }
                AudioMode.PA_FOLLOW_SCREEN, AudioMode.PA_PERSIST -> {
                    // 方案B (PulseAudio): 直接配置 PulseAudio，不尝试 VNC RFB
                    sb.append("echo \"音频模式: PulseAudio (${if (effectiveAudioMode == AudioMode.PA_FOLLOW_SCREEN) "跟随VNC页面启停" else "持续播放"})\"\n")
                    sb.append("if ! \"\$RUN_SCRIPT\" -c 'command -v pulseaudio' >/dev/null 2>&1; then\n")
                    sb.append("    echo \"正在安装 PulseAudio (容器内音频服务)...\"\n")
                    sb.append("    \"\$RUN_SCRIPT\" -c 'export DEBIAN_FRONTEND=noninteractive; apt update -y && apt install -y --no-install-recommends pulseaudio pulseaudio-utils libpulse0' 2>/dev/null || {\n")
                    sb.append("        echo \"警告: PulseAudio 安装失败，声音将不可用\"\n")
                    sb.append("        echo \"请手动进入容器执行: apt install pulseaudio\"\n")
                    sb.append("        sleep 5\n")
                    sb.append("    }\n")
                    sb.append("fi\n")
                    sb.append("if \"\$RUN_SCRIPT\" -c 'command -v pulseaudio' >/dev/null 2>&1; then\n")
                    sb.append("    HAS_PULSEAUDIO=1\n")
                    sb.append("    echo \"PulseAudio 已就绪\"\n")
                    sb.append("else\n")
                    sb.append("    echo \"警告: PulseAudio 不可用\"\n")
                    sb.append("fi\n")
                }
            }

            // 把检测结果 + 用户选择的模式写入 VM 脚本可读的环境标记文件
            // 使用不带引号的 heredoc 让 Bash 展开变量
            sb.append("AUDIO_FLAG_FILE=\"\$HOME/.qemu_vm_audio_${id}.env\"\n")
            sb.append("cat > \"\$AUDIO_FLAG_FILE\" <<ENV_EOF\n")
            sb.append("USER_AUDIO_MODE=\$USER_AUDIO_MODE\n")
            sb.append("HAS_VNC_AUDIO=\$HAS_VNC_AUDIO\n")
            sb.append("HAS_PULSEAUDIO=\$HAS_PULSEAUDIO\n")
            // PulseAudio TCP 端口：QEMU(容器内) -> Android App(VNC页面) 播放
            sb.append("PA_TCP_PORT=4713\n")
            sb.append("PA_SIMPLE_PROTO_PORT=4714\n")
            sb.append("ENV_EOF\n")
            sb.append("echo \"音频依赖检查完成 (mode=\$USER_AUDIO_MODE, vnc=\$HAS_VNC_AUDIO, pa=\$HAS_PULSEAUDIO)\"\n\n")
        } else {
            sb.append("echo \"跳过音频配置（声音已关闭）\"\n\n")
        }

        // 3. 内部存储映射（Termux 层）
        sb.append("# 3. 内部存储映射\n")
        sb.append("if [ ! -d \"\$HOME/storage/shared\" ]; then\n")
        sb.append("    echo \"正在设置内部存储映射...\"\n")
        sb.append("    termux-setup-storage\n")
        sb.append("    sleep 3\n")
        sb.append("fi\n\n")

        // 4. 创建共享目录 + 创建新硬盘（在 Termux 层做，容器里 termux-setup-storage 不可用）
        sb.append("# 4. 创建共享目录和新硬盘 (Termux 层)\n")
        sb.append("mkdir -p \"$shareDir\"\n")
        val vncDisplay = vncPort - 5900

        if (mode == "install_iso" || mode == "create_disk") {
            sb.append("mkdir -p \"\$(dirname \"$diskPath\")\"\n")
            sb.append("if [ ! -f \"$diskPath\" ]; then\n")
            sb.append("    echo \"正在创建新硬盘 (${newDiskSizeGB}GB, 格式 $newDiskFormat)...\"\n")
            // 优先使用容器内的 qemu-img（容器版 / 原生版 qcow2 格式完全兼容）
            // 把 diskPath 里的 \$HOME 前缀映射到容器内的 /root/shared，再由容器内 qemu-img 创建到正确位置
            val qemuImgDiskPath = diskPath.replace("\$HOME", "/root/shared")
            sb.append("    if \"\$RUN_SCRIPT\" -c 'command -v qemu-img' >/dev/null 2>&1; then\n")
            sb.append("        \"\$RUN_SCRIPT\" -c 'qemu-img create -f $newDiskFormat \"$qemuImgDiskPath\" ${newDiskSizeGB}G' 2>/dev/null || true\n")
            sb.append("    fi\n")
            sb.append("    if [ ! -f \"$diskPath\" ]; then\n")
            sb.append("        echo \"  回退到 Termux 层创建镜像...\"\n")
            sb.append("        command -v qemu-img >/dev/null 2>&1 && qemu-img create -f $newDiskFormat \"$diskPath\" ${newDiskSizeGB}G 2>/dev/null || \\\n")
            sb.append("            truncate -s ${newDiskSizeGB}G \"$diskPath\"\n")
            sb.append("    fi\n")
            sb.append("    if [ ! -f \"$diskPath\" ]; then\n")
            sb.append("        echo \"错误: 硬盘创建失败\"\n")
            sb.append("        sleep 3\n")
            sb.append("        exit 1\n")
            sb.append("    fi\n")
            sb.append("fi\n")
            sb.append("echo \"硬盘已就绪: $diskPath\"\n\n")
        }

        // ========== 构造容器内执行的脚本 ==========
        // 容器内目录映射：
        //   /root/shared            -> Termux $HOME
        //   磁盘/ISO 路径如果以 $HOME 开头，在 VM 脚本里必须替换为 /root/shared
        //   这样容器内才能通过 bind mount 访问到文件

        val vmScriptPath = "\$HOME/.qemu_vm_${id}.sh"

        sb.append("# 5. 验证文件并写入容器内执行的 VM 启动脚本\n")
        // 在 Termux 层检查磁盘文件是否存在
        sb.append("echo \"Termux 层验证磁盘文件...\"\n")
        sb.append("if [ ! -f \"$diskPath\" ]; then\n")
        sb.append("    echo \"ERROR: 磁盘文件不存在: $diskPath\"\n")
        sb.append("    echo \"请确认硬盘文件已正确创建\"\n")
        sb.append("    sleep 3\n")
        sb.append("    exit 1\n")
        sb.append("fi\n")
        sb.append("echo \"磁盘文件已就绪: $diskPath\"\n\n")

        sb.append("cat > \"$vmScriptPath\" <<'VM_EOF'\n")
        sb.append("#!/bin/bash\n")
        sb.append("# 不使用 set -e：PulseAudio / pactl 模块加载等辅助命令失败时，不应导致 VM 启动中断\n")
        sb.append("set +e\n")
        sb.append("echo \"  [容器内] 启动参数解析中...\"\n")

        // 容器内路径转换：将 $HOME 替换为 /root/shared（容器内的 bind 路径）
        val containerDiskPath = diskPath.replace("\$HOME", "/root/shared")
        val containerIsoPath = isoPath?.replace("\$HOME", "/root/shared")
        val containerShareDir = shareDir.replace("\$HOME", "/root/shared")

        // 音频：根据 Termux 层写入的标记，结合用户选择的模式决定使用哪种音频后端
        if (effectiveHasSound) {
            sb.append("# 音频：从 Termux 层读取用户音频模式与检测结果\n")
            sb.append("AUDIO_FLAG_FILE=\"/root/shared/.qemu_vm_audio_${id}.env\"\n")
            sb.append("QEMU_AUDIO_VNC_OK=0\n")
            sb.append("QEMU_USE_PULSEAUDIO=0\n")
            sb.append("if [ -f \"\$AUDIO_FLAG_FILE\" ]; then\n")
            sb.append("    . \"\$AUDIO_FLAG_FILE\"\n")
            sb.append("fi\n")
            // 最终音频策略：
            //   VNC_RFB 模式且 HAS_VNC_AUDIO=1 => 走 VNC audio driver
            //   其他情况（PA_FOLLOW_SCREEN / PA_PERSIST / VNC_RFB但driver不可用）=> 走 PulseAudio
            sb.append("case \"\${USER_AUDIO_MODE:-disabled}\" in\n")
            sb.append("  ${AudioMode.VNC_RFB})\n")
            sb.append("    if [ \"\${HAS_VNC_AUDIO:-0}\" = \"1\" ] && qemu-system-x86_64 -audiodev help 2>&1 | grep -q '^vnc'; then\n")
            sb.append("        QEMU_AUDIO_VNC_OK=1\n")
            sb.append("        echo '  [容器内] 音频策略: VNC RFB 扩展（音频直接通过VNC连接）'\n")
            sb.append("    else\n")
            sb.append("        QEMU_USE_PULSEAUDIO=1\n")
            sb.append("        echo '  [容器内] VNC audio不可用，自动降级到 PulseAudio'\n")
            sb.append("    fi\n")
            sb.append("    ;;\n")
            sb.append("  ${AudioMode.PA_FOLLOW_SCREEN}|${AudioMode.PA_PERSIST})\n")
            sb.append("    QEMU_USE_PULSEAUDIO=1\n")
            sb.append("    echo '  [容器内] 音频策略: PulseAudio (用户选择模式)'\n")
            sb.append("    ;;\n")
            sb.append("  *)\n")
            sb.append("    echo '  [容器内] 音频: 模式未识别，跳过音频'\n")
            sb.append("    ;;\n")
            sb.append("esac\n")

            // 如果最终选择 PulseAudio：启动 daemon 并开启 TCP 监听（供 Android 端 VNC 页面播放）
            sb.append("if [ \"\$QEMU_USE_PULSEAUDIO\" = \"1\" ]; then\n")
            sb.append("    if command -v pulseaudio >/dev/null 2>&1; then\n")
            sb.append("        # ========== [PA修复1] 二次确认运行环境变量和目录 ==========\n")
            sb.append("        if [ -f /root/.config/pulse/env.sh ]; then\n")
            sb.append("            . /root/.config/pulse/env.sh\n")
            sb.append("        else\n")
            sb.append("            export XDG_RUNTIME_DIR=\"/tmp/runtime-root\"\n")
            sb.append("            export PULSE_RUNTIME_PATH=\"/tmp/runtime-root/pulse\"\n")
            sb.append("            export PULSE_SERVER=\"tcp:127.0.0.1:4713\"\n")
            sb.append("            export HOME=\"/root\"\n")
            sb.append("        fi\n")
            sb.append("        mkdir -p \"\$XDG_RUNTIME_DIR/pulse\" /root/.config/pulse /run/pulse 2>/dev/null\n")
            sb.append("        chmod 700 \"\$XDG_RUNTIME_DIR\" \"\$XDG_RUNTIME_DIR/pulse\" /root/.config/pulse 2>/dev/null\n")
            sb.append("        chmod 1777 /run/pulse 2>/dev/null || true\n")
            sb.append("        # ========== [PA修复2] 校验/补全配置文件（不再 rm -rf 清空预配置！） ==========\n")
            sb.append("        if [ ! -f /root/.config/pulse/client.conf ]; then\n")
            sb.append("            cat > /root/.config/pulse/client.conf <<PAEOF\n")
            sb.append("allow-root = yes\n")
            sb.append("disable-shm = true\n")
            sb.append("auto-connect-localhost = yes\n")
            sb.append("default-server = tcp:127.0.0.1:4713\n")
            sb.append("PAEOF\n")
            sb.append("        fi\n")
            sb.append("        if [ ! -f /root/.config/pulse/daemon.conf ]; then\n")
            sb.append("            cat > /root/.config/pulse/daemon.conf <<PAEOF\n")
            sb.append("allow-root = yes\n")
            sb.append("exit-idle-time = -1\n")
            sb.append("flat-volumes = no\n")
            sb.append("shm-size-bytes = 0\n")
            sb.append("log-target = stderr\n")
            sb.append("log-level = notice\n")
            sb.append("PAEOF\n")
            sb.append("        fi\n")
            sb.append("        if [ ! -f /root/.config/pulse/default.pa ]; then\n")
            sb.append("            cat > /root/.config/pulse/default.pa <<PAEOF\n")
            sb.append(".fail\n")
            sb.append("load-module module-native-protocol-unix\n")
            sb.append("load-module module-always-sink\n")
            sb.append("load-module module-null-sink sink_name=auto_null sink_properties=device.description=Virtual-Sink\n")
            sb.append("load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1;::1 port=4713\n")
            sb.append("load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=auto_null.monitor record=true port=4714\n")
            sb.append("PAEOF\n")
            sb.append("        fi\n")
            sb.append("        # 杀掉所有旧 PA 进程 + 清 pid/cookie 锁，避免被占用\n")
            sb.append("        pulseaudio --kill 2>/dev/null || true\n")
            sb.append("        pkill -9 pulseaudio 2>/dev/null || true\n")
            sb.append("        sleep 0.5\n")
            sb.append("        rm -rf \"\$XDG_RUNTIME_DIR/pulse/pid\" \"\$XDG_RUNTIME_DIR/pulse/native\" /root/.config/pulse/cookie 2>/dev/null || true\n")
            sb.append("        # ========== [PA修复3] 启动 PA daemon（daemonize 模式 + 三级兜底方案） ==========\n")
            sb.append("        PA_START_OK=0\n")
            sb.append("        pulseaudio -D --disallow-module-loading=false --exit-idle-time=-1 --file=/root/.config/pulse/default.pa --log-target=stderr 2>/tmp/pulse_start1.log || true\n")
            sb.append("        sleep 1.5\n")
            sb.append("        # ========== [PA修复4] 验证 daemon 是否存活（双重稳健判断） ==========\n")
            sb.append("        if pulseaudio --check 2>/dev/null || pactl info >/dev/null 2>&1; then\n")
            sb.append("            PA_START_OK=1\n")
            sb.append("            echo '  [容器内] PA daemon 启动成功 (方案1: default.pa)'\n")
            sb.append("        else\n")
            sb.append("            echo '  [容器内] 方案1 启动失败，尝试方案2 (命令行--load兜底)...'\n")
            sb.append("            pulseaudio --kill 2>/dev/null || true\n")
            sb.append("            pkill -9 pulseaudio 2>/dev/null || true\n")
            sb.append("            rm -rf \"\$XDG_RUNTIME_DIR/pulse/pid\" \"\$XDG_RUNTIME_DIR/pulse/native\" /root/.config/pulse/cookie 2>/dev/null || true\n")
            sb.append("            sleep 0.5\n")
            sb.append("            pulseaudio -D --exit-idle-time=-1 \\\n")
            sb.append("                --load=\"module-native-protocol-unix\" \\\n")
            sb.append("                --load=\"module-null-sink sink_name=auto_null sink_properties=device.description=Virtual-Sink\" \\\n")
            sb.append("                --load=\"module-native-protocol-tcp auth-ip-acl=127.0.0.1;::1 port=4713\" \\\n")
            sb.append("                --load=\"module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=auto_null.monitor record=true port=4714\" \\\n")
            sb.append("                --log-target=stderr --disallow-module-loading=false 2>/tmp/pulse_start2.log || true\n")
            sb.append("            sleep 2\n")
            sb.append("            if pulseaudio --check 2>/dev/null || pactl info >/dev/null 2>&1; then\n")
            sb.append("                PA_START_OK=1\n")
            sb.append("                echo '  [容器内] PA daemon 启动成功 (方案2: 命令行--load)'\n")
            sb.append("            else\n")
            sb.append("                echo '  [容器内] 方案2 也失败，尝试方案3 (system-mode 兜底)...'\n")
            sb.append("                pulseaudio --kill 2>/dev/null || true\n")
            sb.append("                pkill -9 pulseaudio 2>/dev/null || true\n")
            sb.append("                rm -rf /var/run/pulse /var/lib/pulse /run/pulse/* \"\$XDG_RUNTIME_DIR/pulse/*\" 2>/dev/null || true\n")
            sb.append("                mkdir -p /var/run/pulse /var/lib/pulse /etc/pulse 2>/dev/null\n")
            sb.append("                sleep 0.5\n")
            sb.append("                pulseaudio --system -D --disallow-module-loading=false --exit-idle-time=-1 --realtime=false --no-cpu-limit --log-target=stderr \\\n")
            sb.append("                    --load=\"module-native-protocol-tcp auth-ip-acl=127.0.0.1;::1 port=4713 auth-anonymous=1\" \\\n")
            sb.append("                    --load=\"module-null-sink sink_name=auto_null sink_properties=device.description=Virtual-Sink\" \\\n")
            sb.append("                    --load=\"module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=auto_null.monitor record=true port=4714\" 2>/tmp/pulse_start3.log || true\n")
            sb.append("                sleep 2.5\n")
            sb.append("                if PULSE_SERVER=tcp:127.0.0.1:4713 pactl info >/dev/null 2>&1; then\n")
            sb.append("                    PA_START_OK=1\n")
            sb.append("                    echo '  [容器内] PA daemon 启动成功 (方案3: system-mode)'\n")
            sb.append("                else\n")
            sb.append("                    echo '  [容器内] ERROR: 三种方案均未启动 PA daemon'\n")
            sb.append("                    [ -f /tmp/pulse_start1.log ] && { echo '  --- 方案1日志 ---'; sed -n '1,20p' /tmp/pulse_start1.log; }\n")
            sb.append("                    [ -f /tmp/pulse_start2.log ] && { echo '  --- 方案2日志 ---'; sed -n '1,20p' /tmp/pulse_start2.log; }\n")
            sb.append("                    [ -f /tmp/pulse_start3.log ] && { echo '  --- 方案3日志 ---'; sed -n '1,20p' /tmp/pulse_start3.log; }\n")
            sb.append("                fi\n")
            sb.append("            fi\n")
            sb.append("        fi\n")
            sb.append("        if [ \"\$PA_START_OK\" != \"1\" ]; then\n")
            sb.append("            echo '  [容器内] ERROR: PA daemon 无法启动，跳过音频'\n")
            sb.append("            QEMU_USE_PULSEAUDIO=0\n")
            sb.append("            QEMU_AUDIO_VNC_OK=0\n")
            sb.append("        fi\n")
            sb.append("        if [ \"\$QEMU_USE_PULSEAUDIO\" = \"1\" ]; then\n")
            sb.append("            # 补加载必要模块（daemon 首次启动可能没读到配置；重复加载 = 安全忽略）\n")
            sb.append("            pactl load-module module-null-sink sink_name=auto_null sink_properties=device.description=Virtual-Sink >/dev/null 2>&1 || true\n")
            sb.append("            sleep 0.3\n")
            sb.append("            pactl load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1;::1 port=4713 auth-anonymous=1 >/dev/null 2>&1 || true\n")
            sb.append("            sleep 0.3\n")
            sb.append("            pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=auto_null.monitor record=true port=4714 >/dev/null 2>&1 || true\n")
            sb.append("            sleep 0.3\n")
            sb.append("            # 等待 4713（QEMU PA 后端连接）和 4714（Android 播放器）双端口就绪\n")
            sb.append("            PA_READY=0\n")
            sb.append("            PA_SIMPLE_READY=0\n")
            sb.append("            for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do\n")
            sb.append("                if command -v ss >/dev/null 2>&1; then\n")
            sb.append("                    ss -ltn 2>/dev/null | grep -q ':4713 ' && PA_READY=1\n")
            sb.append("                    ss -ltn 2>/dev/null | grep -q ':4714 ' && PA_SIMPLE_READY=1\n")
            sb.append("                elif command -v netstat >/dev/null 2>&1; then\n")
            sb.append("                    netstat -ltn 2>/dev/null | grep -q ':4713 ' && PA_READY=1\n")
            sb.append("                    netstat -ltn 2>/dev/null | grep -q ':4714 ' && PA_SIMPLE_READY=1\n")
            sb.append("                else\n")
            sb.append("                    (echo > /dev/tcp/127.0.0.1/4713) 2>/dev/null && PA_READY=1\n")
            sb.append("                    (echo > /dev/tcp/127.0.0.1/4714) 2>/dev/null && PA_SIMPLE_READY=1\n")
            sb.append("                fi\n")
            sb.append("                [ \"\$PA_READY\" = \"1\" ] && [ \"\$PA_SIMPLE_READY\" = \"1\" ] && break\n")
            sb.append("                sleep 0.5\n")
            sb.append("            done\n")
            sb.append("            if [ \"\$PA_READY\" = \"1\" ] && [ \"\$PA_SIMPLE_READY\" = \"1\" ]; then\n")
            sb.append("                echo '  [容器内] PulseAudio TCP 4713 + 4714 双端口就绪'\n")
            sb.append("                echo '  [容器内] PulseAudio 已启动'\n")
            sb.append("            elif [ \"\$PA_READY\" = \"1\" ]; then\n")
            sb.append("                echo '  [容器内] WARN: 仅 4713 就绪，4714(Simple Protocol) 未打开；虚拟机内部有声但 VNC 页面可能无声音'\n")
            sb.append("            else\n")
            sb.append("                echo '  [容器内] WARN: PulseAudio 4713/4714 均未就绪，QEMU 音频不可用'\n")
            sb.append("                QEMU_USE_PULSEAUDIO=0\n")
            sb.append("                QEMU_AUDIO_VNC_OK=0\n")
            sb.append("            fi\n")
            sb.append("        fi\n")
            sb.append("    else\n")
            sb.append("        echo '  [容器内] 警告: pulseaudio 未安装，声音不可用'\n")
            sb.append("        QEMU_USE_PULSEAUDIO=0\n")
            sb.append("        QEMU_AUDIO_VNC_OK=0\n")
            sb.append("    fi\n")
            sb.append("fi\n\n")
        }

        // 在 VM_EOF 中做启动前检查
        sb.append("echo \"  [容器内] 检查磁盘文件...\"\n")
        sb.append("if [ ! -f \"$containerDiskPath\" ]; then\n")
        sb.append("    echo \"  ERROR: 容器内磁盘文件不存在: $containerDiskPath\"\n")
        sb.append("    echo \"  Termux 路径: $diskPath\"\n")
        sb.append("    echo \"  可能原因: 存储权限未授予或容器未正确绑定\"\n")
        sb.append("    exit 1\n")
        sb.append("fi\n\n")
        // 容器内用绝对路径引用 Termux 端的文件：
        //   Termux $HOME -> /root/shared  (container_run.sh 已 bind)
        //   所以磁盘/ISO/share 路径只要把 $HOME 替换成 /root/shared 即可
        sb.append("TMUX_HOME=\"/root/shared\"\n")
        sb.append("\n")

        sb.append("killall -9 qemu-system-x86_64 2>/dev/null || true\n")
        sb.append("sleep 1\n\n")

        // 先在数组外计算好音频参数（case 语句不能写在 bash 数组括号内）
        if (effectiveHasSound) {
            sb.append("# ========= 音频参数计算：根据 QEMU_AUDIO_VNC_OK / QEMU_USE_PULSEAUDIO 决定 =========\n")
            sb.append("EXTRA_AUDIO_ARGS=()\n")
            sb.append("VNC_AUDIO_ARG=\"\"\n")
            sb.append("if [ \"\$QEMU_AUDIO_VNC_OK\" = \"1\" ]; then\n")
            sb.append("    # 方案A: 通过 VNC RFB 扩展传递音频，客户端无需额外配置\n")
            sb.append("    EXTRA_AUDIO_ARGS=(\n")
            sb.append("        -audiodev vnc,id=vnc_audio,server\n")
            sb.append("        -device ich9-intel-hda\n")
            sb.append("        -device hda-output,audiodev=vnc_audio\n")
            sb.append("    )\n")
            sb.append("    VNC_AUDIO_ARG=\",audiodev=vnc_audio\"\n")
            sb.append("elif [ \"\$QEMU_USE_PULSEAUDIO\" = \"1\" ]; then\n")
            sb.append("    # 方案B: 使用 PulseAudio 作为容器内音频后端\n")
            sb.append("    # 指定 server=tcp:127.0.0.1:4713，让 QEMU 的 PA 客户端连上容器内 PA daemon\n")
            sb.append("    EXTRA_AUDIO_ARGS=(\n")
            sb.append("        -audiodev pa,id=pa_audio,server=tcp:127.0.0.1:4713\n")
            sb.append("        -device ich9-intel-hda\n")
            sb.append("        -device hda-output,audiodev=pa_audio\n")
            sb.append("    )\n")
            sb.append("    VNC_AUDIO_ARG=\"\"\n")
            sb.append("fi\n\n")
        } else {
            sb.append("EXTRA_AUDIO_ARGS=()\n")
            sb.append("VNC_AUDIO_ARG=\"\"\n\n")
        }

        // 生成 QEMU 参数数组（容器内路径使用 /root/shared 前缀）
        sb.append("QEMU_ARGS=(\n")
        sb.append("    -M $machineType\n")
        val cpuModelFinal = cpuModelOverride
            ?: if (machineType == "isapc" || machineType == "pc") "qemu64" else "core2duo"
        sb.append("    -cpu $cpuModelFinal\n")
        sb.append("    -accel tcg,thread=multi\n")
        sb.append("    -smp $cpuCores,cores=$cpuCores,threads=1,sockets=1\n")
        sb.append("    -m $memoryMB\n")
        sb.append("    -net user -net nic,model=virtio\n")
        if (effectiveHasSound) {
            sb.append("    \"\${EXTRA_AUDIO_ARGS[@]}\"\n")
        }
        sb.append("    -vga virtio\n")
        sb.append("    -usb -device usb-tablet\n")
        sb.append("    -vnc 0.0.0.0:$vncDisplay\${VNC_AUDIO_ARG}\n")
        sb.append("    -no-reboot\n")
        val driveCacheOption = if (ssdCacheMode) ",cache=writeback,aio=threads,discard=on,detect-zeroes=on" else ""
        when (diskInterface) {
            "virtio" -> {
                sb.append("    -drive file=\"$containerDiskPath\",if=none,id=hd0$driveCacheOption\n")
                sb.append("    -device virtio-blk-pci,drive=hd0\n")
            }
            "scsi" -> {
                sb.append("    -drive file=\"$containerDiskPath\",if=none,id=hd0$driveCacheOption\n")
                sb.append("    -device virtio-scsi-pci,id=scsi0\n")
                sb.append("    -device scsi-hd,drive=hd0,bus=scsi0.0\n")
            }
            "sata" -> {
                sb.append("    -drive file=\"$containerDiskPath\",if=none,id=hd0$driveCacheOption\n")
                sb.append("    -device ich9-ahci,id=sata0\n")
                sb.append("    -device ide-hd,drive=hd0,bus=sata0.0\n")
            }
            else -> {
                sb.append("    -hda \"$containerDiskPath\"\n")
                if (ssdCacheMode) {
                    sb.append("    -global ide-hd.drive-cache=writeback\n")
                }
            }
        }
        if (containerIsoPath != null) {
            sb.append("    -cdrom \"$containerIsoPath\"\n")
        }
        sb.append("    -rtc base=localtime\n")
        sb.append(buildBootArgs()).append("\n")
        if (enableShareDir) {
            sb.append("    -fsdev local,security_model=mapped-file,id=fsdev_shared,path=\"$containerShareDir\"\n")
            sb.append("    -device virtio-9p-pci,id=fs0,fsdev=fsdev_shared,mount_tag=hostshare\n")
        }
        sb.append(")\n\n")

        sb.append("echo \"  [容器内] 正在启动 QEMU 虚拟机...\"\n")
        sb.append("echo \"  VNC 端口: $vncPort (display :$vncDisplay)\"\n")
        sb.append("echo \"  请使用 VNC 客户端连接 localhost:$vncPort\"\n")
        // 用后台方式启动，先确保 daemon 进程存活，等端口打开后再交给前台接管
        sb.append("QEMU_LOG=\"/tmp/.qemu_${id}.log\"\n")
        sb.append("nohup qemu-system-x86_64 \"\${QEMU_ARGS[@]}\" > \"\$QEMU_LOG\" 2>&1 &\n")
        sb.append("QEMU_PID=\$!\n")
        sb.append("echo \$QEMU_PID > /tmp/.qemu_${id}.pid\n")
        // 等待 VNC 端口真正进入 LISTEN（最多等 30 秒），端口未就绪前 Android 点进去会 Connection closed
        sb.append("VNC_LISTEN_OK=0\n")
        sb.append("for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do\n")
        sb.append("    if command -v ss >/dev/null 2>&1; then\n")
        sb.append("        ss -ltn 2>/dev/null | grep -qE \"[:.]${vncPort} \" && { VNC_LISTEN_OK=1; break; }\n")
        sb.append("    elif command -v netstat >/dev/null 2>&1; then\n")
        sb.append("        netstat -ltn 2>/dev/null | grep -qE \"[:.]${vncPort} \" && { VNC_LISTEN_OK=1; break; }\n")
        sb.append("    else\n")
        sb.append("        (echo > /dev/tcp/127.0.0.1/$vncPort) >/dev/null 2>&1 && { VNC_LISTEN_OK=1; break; }\n")
        sb.append("    fi\n")
        sb.append("    # 启动中途如果 qemu 已退出，就不用再等了\n")
        sb.append("    if ! kill -0 \$QEMU_PID 2>/dev/null; then\n")
        sb.append("        echo '  ERROR: QEMU 进程已异常退出，日志如下:'\n")
        sb.append("        cat \"\$QEMU_LOG\" 2>/dev/null\n")
        sb.append("        exit 1\n")
        sb.append("    fi\n")
        sb.append("    sleep 1\n")
        sb.append("done\n")
        sb.append("if [ \"\$VNC_LISTEN_OK\" = \"1\" ]; then\n")
        sb.append("    echo \"  [容器内] VNC 服务已就绪 (端口 $vncPort)\"\n")
        sb.append("else\n")
        sb.append("    echo '  WARN: 30s 内未检测到 VNC LISTEN 状态，但仍尝试接管 qemu...'\n")
        sb.append("fi\n")
        // 前台接管，保持脚本不退出（一旦退出容器就会被销毁）
        sb.append("echo '  [容器内] 虚拟机运行中，按 Ctrl+C 或关闭终端可停止'\n")
        sb.append("wait \$QEMU_PID 2>/dev/null\n")
        sb.append("QEMU_EXIT=\$?\n")
        sb.append("rm -f /tmp/.qemu_${id}.pid \"\$QEMU_LOG\"\n\n")

        sb.append("echo ''\n")
        sb.append("echo '========================================'\n")
        sb.append("echo 'QEMU 虚拟机已停止'\n")
        sb.append("echo '========================================'\n")
        sb.append("VM_EOF\n")
        sb.append("chmod +x \"$vmScriptPath\"\n\n")

        // ========== 6. 进入容器执行 VM 脚本 ==========
        // 用 proot 直接启动，添加多个 -b bind mount，保证磁盘/ISO/共享目录在容器内能按原始绝对路径访问
        sb.append("# 6. 用 proot 进入容器，挂载磁盘/ISO/共享目录后执行 VM 脚本\n")
        sb.append("VM_SCRIPT_CONTAINER=\"/root/shared/.qemu_vm_${id}.sh\"\n")
        // $HOME/storage/shared 是符号链接，指向 /storage/emulated/0
        // proot -b 不会跟随符号链接，需要额外绑定真实路径
        sb.append("STORAGE_REAL=\"\$(realpath \"\$HOME/storage/shared\" 2>/dev/null || echo /storage/emulated/0)\"\n")
        sb.append("EXTRA_BINDS=()\n")
        sb.append("[ -d \"\$HOME/storage/shared\" ] && EXTRA_BINDS+=(-b \"\$STORAGE_REAL:/root/shared/storage/shared\")\n")

        sb.append("unset LD_PRELOAD\n")
        sb.append("mkdir -p \"\$CONTAINER_DIR/rootfs/etc\" 2>/dev/null\n")
        sb.append("if [ ! -s \"\$CONTAINER_DIR/rootfs/etc/resolv.conf\" ]; then\n")
        sb.append("    printf \"nameserver 8.8.8.8\\nnameserver 8.8.4.4\\n\" > \"\$CONTAINER_DIR/rootfs/etc/resolv.conf\" 2>/dev/null || true\n")
        sb.append("fi\n\n")

        // ========== 关键修复：进入容器前先预配置 PulseAudio 启动环境 ==========
        // 问题：env -i 会清空 XDG_RUNTIME_DIR / PULSE_RUNTIME_PATH 等关键变量；
        //      且容器默认禁止 root 启动 PA、缺少 machine-id、/dev/shm 在 proot 下不可靠。
        // 方案：用 $RUN_SCRIPT 先在容器内跑一次性初始化脚本：
        //      (1) 生成 /etc/machine-id  (2) 创建 XDG_RUNTIME_DIR 并给权限
        //      (3) 写 client.conf (allow-root + disable-shm + default-server)
        //      (4) 写 daemon.conf (不退出空闲 + stderr日志)
        //      (5) 写 default.pa (null-sink + 4713 TCP + 4714 Simple Protocol)
        //      (6) 写 env.sh 供 VM 脚本内二次 source 确认
        sb.append("# 6.0 容器内 PulseAudio 启动环境预配置（解决 env -i + root + proot 下 PA 无法启动的问题）\n")
        sb.append("if [ \"\${HAS_PULSEAUDIO:-0}\" = \"1\" ] || [ \"\${HAS_VNC_AUDIO:-0}\" != \"1\" -a \"\${USER_AUDIO_MODE:-${AudioMode.DISABLED}}\" != \"${AudioMode.DISABLED}\" ]; then\n")
        sb.append("    \"\$RUN_SCRIPT\" -c 'set +e\n")
        sb.append("# 6.0.1 machine-id（D-Bus / PulseAudio 依赖）\n")
        sb.append("if [ ! -s /etc/machine-id ]; then\n")
        sb.append("    (command -v dbus-uuidgen >/dev/null 2>&1 && dbus-uuidgen --ensure=/etc/machine-id) || \\\n")
        sb.append("        (command -v uuidgen >/dev/null 2>&1 && uuidgen | tr -d \"\\n\" > /etc/machine-id) || \\\n")
        sb.append("        (head -c 16 /dev/urandom 2>/dev/null | od -An -tx1 | tr -d \" \\n\" > /etc/machine-id) 2>/dev/null || true\n")
        sb.append("fi\n")
        sb.append("[ -s /etc/machine-id ] || echo \"00000000000000000000000000000001\" > /etc/machine-id\n")
        sb.append("mkdir -p /var/lib/dbus 2>/dev/null; [ ! -f /var/lib/dbus/machine-id ] && cp -f /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true\n")
        sb.append("# 6.0.2 运行时目录\n")
        sb.append("export XDG_RUNTIME_DIR=\"/tmp/runtime-root\"\n")
        sb.append("mkdir -p \"\$XDG_RUNTIME_DIR/pulse\" /root/.config/pulse /run/pulse 2>/dev/null\n")
        sb.append("chmod 700 \"\$XDG_RUNTIME_DIR\" \"\$XDG_RUNTIME_DIR/pulse\" /root/.config/pulse 2>/dev/null\n")
        sb.append("chmod 1777 /run/pulse 2>/dev/null || true\n")
        sb.append("# 6.0.3 client.conf\n")
        sb.append("cat > /root/.config/pulse/client.conf <<PAEOF\n")
        sb.append("allow-root = yes\n")
        sb.append("disable-shm = true\n")
        sb.append("auto-connect-localhost = yes\n")
        sb.append("default-server = tcp:127.0.0.1:4713\n")
        sb.append("PAEOF\n")
        sb.append("chmod 644 /root/.config/pulse/client.conf 2>/dev/null\n")
        sb.append("# 6.0.4 daemon.conf\n")
        sb.append("cat > /root/.config/pulse/daemon.conf <<PAEOF\n")
        sb.append("allow-root = yes\n")
        sb.append("exit-idle-time = -1\n")
        sb.append("flat-volumes = no\n")
        sb.append("shm-size-bytes = 0\n")
        sb.append("log-target = stderr\n")
        sb.append("log-level = notice\n")
        sb.append("resample-method = speex-fixed-2\n")
        sb.append("PAEOF\n")
        sb.append("chmod 644 /root/.config/pulse/daemon.conf 2>/dev/null\n")
        sb.append("# 6.0.5 default.pa：null-sink + native TCP 4713 + simple-protocol TCP 4714\n")
        sb.append("cat > /root/.config/pulse/default.pa <<PAEOF\n")
        sb.append(".fail\n")
        sb.append("load-module module-device-restore\n")
        sb.append("load-module module-stream-restore\n")
        sb.append("load-module module-card-restore\n")
        sb.append("load-module module-augment-properties\n")
        sb.append("load-module module-switch-on-port-available\n")
        sb.append("load-module module-native-protocol-unix\n")
        sb.append("load-module module-default-device-restore\n")
        sb.append("load-module module-always-sink\n")
        sb.append("load-module module-null-sink sink_name=auto_null sink_properties=device.description=Virtual-Sink\n")
        sb.append("load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1;::1 port=4713\n")
        sb.append("load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=auto_null.monitor record=true port=4714\n")
        sb.append("load-module module-intended-roles\n")
        sb.append("load-module module-suspend-on-idle\n")
        sb.append("load-module module-position-event-sounds\n")
        sb.append("PAEOF\n")
        sb.append("chmod 644 /root/.config/pulse/default.pa 2>/dev/null\n")
        sb.append("# 6.0.6 环境变量快照（env -i 后脚本会再 source 一次）\n")
        sb.append("cat > /root/.config/pulse/env.sh <<PAEOF\n")
        sb.append("export XDG_RUNTIME_DIR=\"/tmp/runtime-root\"\n")
        sb.append("export PULSE_RUNTIME_PATH=\"/tmp/runtime-root/pulse\"\n")
        sb.append("export HOME=\"/root\"\n")
        sb.append("export PULSE_SERVER=\"tcp:127.0.0.1:4713\"\n")
        sb.append("PAEOF\n")
        sb.append("chmod 644 /root/.config/pulse/env.sh 2>/dev/null\n")
        sb.append("[ -f /root/.config/pulse/default.pa ] && echo \"  容器内 PA 预配置完成\" || echo \"  容器内 PA 预配置警告: default.pa 未写入\"\n")
        sb.append("' 2>/dev/null || true\n")
        sb.append("fi\n\n")

        // 直接组装 proot 命令（复用 container_run.sh 的参数，运行 VM 脚本）
        // 关键修复：env -i 里带上 XDG_RUNTIME_DIR / PULSE_RUNTIME_PATH / PULSE_SERVER，否则 PA 无 runtime 目录可用
        sb.append("echo \"正在进入 proot 容器并启动虚拟机 (extra binds: \${EXTRA_BINDS[*]}) ...\"\n")
        sb.append("exec proot --link2symlink \\\n")
        sb.append("    -0 \\\n")
        sb.append("    -r \"\$CONTAINER_DIR/rootfs\" \\\n")
        sb.append("    -b /dev \\\n")
        sb.append("    -b /proc \\\n")
        sb.append("    -b /sys \\\n")
        sb.append("    -b \"\$HOME:/root/shared\" \\\n")
        sb.append("    \"\${EXTRA_BINDS[@]}\" \\\n")
        sb.append("    -w /root \\\n")
        sb.append("    /usr/bin/env -i \\\n")
        sb.append("    HOME=/root \\\n")
        sb.append("    XDG_RUNTIME_DIR=/tmp/runtime-root \\\n")
        sb.append("    PULSE_RUNTIME_PATH=/tmp/runtime-root/pulse \\\n")
        sb.append("    PULSE_SERVER=tcp:127.0.0.1:4713 \\\n")
        sb.append("    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/sbin:/bin \\\n")
        sb.append("    TERM=\"\${TERM:-xterm-256color}\" \\\n")
        sb.append("    LANG=C.UTF-8 \\\n")
        sb.append("    /bin/bash --login \"\$VM_SCRIPT_CONTAINER\"\n")

        return sb.toString()
    }
}

/**
 * ISO 镜像识别结果，包含系统名称和推荐配置。
 */
data class IsoSystemInfo(
    val systemName: String,               // 系统名称，如 "Windows XP"
    val recommendedMachineType: String,   // 推荐机型: pc / q35 / isapc
    val recommendedDiskInterface: String, // 推荐硬盘接口: ide / virtio / scsi / sata
    val recommendedCpuCores: Int,         // 推荐CPU核心数
    val recommendedMemoryMB: Int          // 推荐内存(MB)
)

/**
 * 根据 ISO 文件路径名智能识别可能包含的操作系统。
 * 返回识别结果；无法识别时返回 null。
 */
fun detectIsoSystem(isoPath: String): IsoSystemInfo? {
    val name = isoPath.lowercase()
    return when {
        // === Windows 系列 ===
        name.contains("win98") || name.contains("windows98") || name.contains("w98") ->
            IsoSystemInfo("Windows 98", "isapc", "ide", 1, 512)
        name.contains("win2000") || name.contains("win2k") || name.contains("windows2000") ->
            IsoSystemInfo("Windows 2000", "pc", "ide", 1, 512)
        name.contains("winxp") || name.contains("windowsxp") || name.contains("wxp") ->
            IsoSystemInfo("Windows XP", "pc", "ide", 1, 1024)
        name.contains("winvista") || name.contains("vista") ->
            IsoSystemInfo("Windows Vista", "q35", "sata", 2, 2048)
        name.contains("win7") || name.contains("windows7") ->
            IsoSystemInfo("Windows 7", "q35", "sata", 2, 2048)
        name.contains("win8") || name.contains("windows8") ->
            IsoSystemInfo("Windows 8", "q35", "sata", 2, 2048)
        name.contains("win10") || name.contains("windows10") ->
            IsoSystemInfo("Windows 10", "q35", "virtio", 2, 4096)
        name.contains("win11") || name.contains("windows11") ->
            IsoSystemInfo("Windows 11", "q35", "virtio", 4, 4096)
        // === Linux 发行版 ===
        name.contains("ubuntu") ->
            IsoSystemInfo("Ubuntu", "q35", "virtio", 2, 2048)
        name.contains("debian") ->
            IsoSystemInfo("Debian", "q35", "virtio", 2, 2048)
        name.contains("fedora") ->
            IsoSystemInfo("Fedora", "q35", "virtio", 2, 2048)
        name.contains("centos") || name.contains("rhel") || name.contains("rocky") || name.contains("alma") ->
            IsoSystemInfo("CentOS/RHEL", "q35", "virtio", 2, 2048)
        name.contains("archlinux") || name.contains("arch-") || name.contains("arch_") ->
            IsoSystemInfo("Arch Linux", "q35", "virtio", 2, 2048)
        name.contains("opensuse") || name.contains("suse") ->
            IsoSystemInfo("openSUSE", "q35", "virtio", 2, 2048)
        name.contains("kali") ->
            IsoSystemInfo("Kali Linux", "q35", "virtio", 2, 2048)
        name.contains("linuxmint") || name.contains("mint") ->
            IsoSystemInfo("Linux Mint", "q35", "virtio", 2, 2048)
        name.contains("alpine") ->
            IsoSystemInfo("Alpine Linux", "q35", "virtio", 1, 512)
        // === 通用匹配 ===
        name.contains("windows") ->
            IsoSystemInfo("Windows", "q35", "sata", 2, 2048)
        name.contains("linux") ->
            IsoSystemInfo("Linux", "q35", "virtio", 2, 2048)
        else -> null
    }
}

/**
 * QEMU 虚拟机配置管理器。
 * 使用 SharedPreferences + Gson 持久化虚拟机配置列表。
 */
object QemuVmManager {
    private const val PREFS_NAME = "qemu_vms_prefs"
    private const val KEY_VMS = "vms_list"
    private const val KEY_MIGRATION_DONE = "paths_migration_v1_done"
    private val gson = Gson()

    // 路径迁移相关常量
    private const val OLD_DEFAULT_SHARE_DIR = "\$HOME/storage/shared/qemu_share"
    private const val NEW_DEFAULT_SHARE_DIR = "\$HOME/storage/shared/Termux/Sharing"
    private const val NEW_DEFAULT_DISK_DIR = "\$HOME/virtual_disks"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    fun loadVms(context: Context): List<QemuVmConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_VMS, null) ?: return emptyList()
        val rawList = try {
            val type = object : TypeToken<List<QemuVmConfig>>() {}.type
            gson.fromJson<List<QemuVmConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (rawList.isEmpty()) return emptyList()
        // 旧版本保存的虚拟机可能缺少一些字段（Gson 会把它们反序列化为 null），
        // 这里通过构造函数传入非空默认值，避免 data class copy() 报 NPE
        return rawList.map { vm ->
            QemuVmConfig(
                id = vm.id.orEmpty().ifBlank { UUID.randomUUID().toString() },
                name = vm.name.orEmpty().ifBlank { "QEMU VM" },
                mode = vm.mode.orEmpty().ifBlank { "existing_disk" },
                diskPath = vm.diskPath.orEmpty(),
                newDiskSizeGB = if (vm.newDiskSizeGB > 0) vm.newDiskSizeGB else 20,
                newDiskFormat = vm.newDiskFormat.orEmpty().ifBlank { "qcow2" },
                isoPath = vm.isoPath,
                cpuCores = if (vm.cpuCores > 0) vm.cpuCores else 2,
                memoryMB = if (vm.memoryMB > 0) vm.memoryMB else 1024,
                hasSound = vm.hasSound,
                audioMode = if (vm.audioMode.isNullOrBlank()) {
                    // 旧数据迁移：audioMode 为空时按 hasSound 回填
                    if (vm.hasSound) AudioMode.VNC_RFB else AudioMode.DISABLED
                } else vm.audioMode,
                shareDir = vm.shareDir.orEmpty().ifBlank { "\$HOME/storage/shared/Termux/Sharing" },
                bootOrder = if (vm.bootOrder?.isNotEmpty() == true) vm.bootOrder else listOf("c"),
                vncPort = if (vm.vncPort in 5900..5999) vm.vncPort else 5900,
                diskInterface = vm.diskInterface.orEmpty().ifBlank { "ide" },
                machineType = vm.machineType.orEmpty().ifBlank { "q35" }
            )
        }
    }

    fun saveVm(context: Context, config: QemuVmConfig) {
        val vms = loadVms(context).toMutableList()
        val index = vms.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            vms[index] = config
        } else {
            vms.add(config)
        }
        saveVms(context, vms)
    }

    fun deleteVm(context: Context, id: String) {
        val vms = loadVms(context).toMutableList()
        vms.removeAll { it.id == id }
        saveVms(context, vms)
    }

    private fun saveVms(context: Context, vms: List<QemuVmConfig>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_VMS, gson.toJson(vms)).apply()
    }

    /**
     * 路径迁移结果
     */
    data class MigrationResult(
        val migratedVmCount: Int,
        val movedDiskCount: Int,
        val shareDirMoved: Boolean,
        val errors: List<String>
    )

    /**
     * 检查虚拟机列表是否需要路径迁移。
     * - 硬盘路径包含 /qemu_disks/ 的需要迁移到 $HOME/virtual_disks/
     * - 共享目录为旧默认值的需要迁移到新默认值
     */
    fun needsMigration(vms: List<QemuVmConfig>): Boolean {
        return vms.any { vm ->
            vm.diskPath.contains("/qemu_disks/") ||
            vm.shareDir == OLD_DEFAULT_SHARE_DIR
        }
    }

    /**
     * 检查是否已经完成过路径迁移。
     */
    fun isMigrationDone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MIGRATION_DONE, false)
    }

    /**
     * 执行路径迁移：
     * 1. 将旧默认硬盘目录 ($HOME/storage/shared/qemu_disks/) 中的硬盘文件移动到新目录 ($HOME/virtual_disks/)
     * 2. 将旧默认共享目录 ($HOME/storage/shared/qemu_share/) 的内容移动到新目录 ($HOME/storage/shared/Termux/Sharing/)
     * 3. 更新所有虚拟机配置中的路径
     *
     * 此函数会执行 shell 命令（mv/cp），应在 IO 线程调用。
     */
    fun migratePaths(context: Context): MigrationResult {
        val vms = loadVms(context)
        if (vms.isEmpty()) {
            markMigrationDone(context)
            return MigrationResult(0, 0, false, emptyList())
        }

        val errors = mutableListOf<String>()
        val newDiskDirAbs = "$TERMUX_HOME/virtual_disks"
        val newShareDirAbs = "$TERMUX_HOME/storage/shared/Termux/Sharing"
        val oldShareDirAbs = "$TERMUX_HOME/storage/shared/qemu_share"

        // 构建迁移脚本（使用绝对路径，不依赖 $HOME 环境变量）
        val script = StringBuilder()
        script.append("set +e\n")
        script.append("mkdir -p \"$newDiskDirAbs\"\n")
        script.append("mkdir -p \"$newShareDirAbs\"\n")

        // 为每个需要迁移硬盘的虚拟机生成 mv 命令
        vms.forEach { vm ->
            if (vm.diskPath.contains("/qemu_disks/")) {
                val oldDiskAbs = vm.diskPath.replace("\$HOME", TERMUX_HOME)
                val fileName = oldDiskAbs.substringAfterLast("/")
                val newDiskAbs = "$newDiskDirAbs/$fileName"
                script.append("if [ -f \"$oldDiskAbs\" ]; then\n")
                script.append("    mv \"$oldDiskAbs\" \"$newDiskAbs\" 2>/dev/null && echo \"DISK_MOVED:$fileName\"\n")
                script.append("fi\n")
            }
        }

        // 迁移共享目录内容
        script.append("if [ -d \"$oldShareDirAbs\" ]; then\n")
        script.append("    cp -r \"$oldShareDirAbs\"/* \"$newShareDirAbs\"/ 2>/dev/null\n")
        script.append("    rm -rf \"$oldShareDirAbs\" 2>/dev/null\n")
        script.append("    echo \"SHARE_MOVED\"\n")
        script.append("fi\n")

        // 执行迁移脚本
        val output = try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", script.toString()))
            val stdout = process.inputStream.bufferedReader().readText()
            process.waitFor()
            stdout
        } catch (e: Exception) {
            errors.add("迁移脚本执行失败: ${e.message}")
            ""
        }

        // 解析输出
        val movedDisks = mutableSetOf<String>()
        var shareMoved = false
        output.lines().forEach { line ->
            when {
                line.startsWith("DISK_MOVED:") -> movedDisks.add(line.removePrefix("DISK_MOVED:"))
                line == "SHARE_MOVED" -> shareMoved = true
            }
        }

        // 更新所有虚拟机配置中的路径
        val migratedVms = vms.map { vm ->
            val newDiskPath = if (vm.diskPath.contains("/qemu_disks/")) {
                val fileName = vm.diskPath.substringAfterLast("/")
                "$NEW_DEFAULT_DISK_DIR/$fileName"
            } else vm.diskPath
            val newShare = if (vm.shareDir == OLD_DEFAULT_SHARE_DIR) NEW_DEFAULT_SHARE_DIR else vm.shareDir
            vm.copy(diskPath = newDiskPath, shareDir = newShare)
        }
        saveVms(context, migratedVms)
        markMigrationDone(context)

        val migratedVmCount = vms.count { vm ->
            vm.diskPath.contains("/qemu_disks/") || vm.shareDir == OLD_DEFAULT_SHARE_DIR
        }

        return MigrationResult(migratedVmCount, movedDisks.size, shareMoved, errors)
    }

    /**
     * 标记路径迁移已完成。
     */
    fun markMigrationDone(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
    }
}
