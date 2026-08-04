package com.termux.app.compose

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

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
    val hasSound: Boolean = false,
    val shareDir: String = "\$HOME/storage/shared/qemu_share",
    val bootOrder: List<String> = listOf("c"),
    val vncPort: Int = 5900
) {
    /**
     * 生成 QEMU 启动脚本。
     * 脚本会检查并安装 qemu-system-x86_64，检查 storage/shared 映射，
     * 必要时创建新硬盘，最后启动 QEMU 虚拟机。
     */
    fun generateScript(): String {
        val sb = StringBuilder()
        sb.append("#!/data/data/com.termux/files/usr/bin/bash\n")
        sb.append("echo \"=== QEMU with VNC: $name ===\"\n\n")

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

        // 5. 构建并启动 QEMU 命令
        sb.append("# 启动 QEMU\n")
        sb.append("killall -9 qemu-system-x86_64 2>/dev/null\n")
        sb.append("sleep 1\n\n")

        val vncDisplay = vncPort - 5900
        val bootOrderStr = bootOrder.joinToString("")

        // 将参数放入 bash 数组，避免 eval/引号嵌套造成的语法错误
        sb.append("QEMU_ARGS=(\n")
        sb.append("    -M q35\n")
        sb.append("    -cpu core2duo\n")
        sb.append("    -accel tcg,thread=multi\n")
        sb.append("    -smp $cpuCores,cores=$cpuCores,threads=1,sockets=1\n")
        sb.append("    -m $memoryMB\n")
        sb.append("    -net user -net nic,model=virtio\n")
        if (hasSound) {
            sb.append("    -audio sdl,model=hda\n")
        }
        sb.append("    -vga virtio\n")
        sb.append("    -usb -device usb-tablet\n")
        sb.append("    -vnc localhost:$vncDisplay\n")
        sb.append("    -hda \"$diskPath\"\n")
        if (isoPath != null) {
            sb.append("    -cdrom \"$isoPath\"\n")
        }
        sb.append("    -rtc base=localtime\n")
        sb.append("    -boot order=$bootOrderStr\n")
        sb.append(")\n\n")

        sb.append("echo \"正在启动 QEMU 虚拟机...\"\n")
        sb.append("echo \"VNC 端口: $vncPort (display :$vncDisplay)\"\n")
        sb.append("echo \"请使用 VNC 客户端连接 localhost:$vncPort\"\n")
        sb.append("qemu-system-x86_64 \"\${QEMU_ARGS[@]}\"\n\n")

        sb.append("echo ''\n")
        sb.append("echo '========================================'\n")
        sb.append("echo 'QEMU 虚拟机已停止'\n")
        sb.append("echo '脚本执行完成'\n")
        sb.append("echo '========================================'\n")

        return sb.toString()
    }
}

/**
 * QEMU 虚拟机配置管理器。
 * 使用 SharedPreferences + Gson 持久化虚拟机配置列表。
 */
object QemuVmManager {
    private const val PREFS_NAME = "qemu_vms_prefs"
    private const val KEY_VMS = "vms_list"
    private val gson = Gson()

    fun loadVms(context: Context): List<QemuVmConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_VMS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<QemuVmConfig>>() {}.type
            gson.fromJson<List<QemuVmConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
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
}
