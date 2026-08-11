const fs = require("fs");
const path = "D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AndroidVmConfig.kt";
let raw = fs.readFileSync(path, "utf8");
let lines = raw.split(/\r?\n/);

// 目标：L454~L590 整块 (数组索引 453..589) 替换成精简版的 generateContainerDownloadScript
// 以及删掉中间重复的第二个 generateScript()
const before = lines.slice(0, 453).join("\n");
const after  = lines.slice(590).join("\n");

// simpleFinal 全部写成数组 push + 单引号连接，完全避免反引号模板解析
let middle = "";
middle += '    /**\n';
middle += '     * Phase 1（容器模式）：ISO 下载直接复用原生 Termux 实现即可。\n';
middle += '     * 原因：ISO 文件写到 vmDir（共享目录），容器内 QemuVmConfig 启动时会自动做路径翻译\n';
middle += '     *       /data/data/com.termux/files/home/xxx → /root/shared/xxx。\n';
middle += '     * QEMU 启动 & 磁盘创建 100% 交给 QemuVmConfig.generateScript()。\n';
middle += '     */\n';
middle += '    private fun generateContainerDownloadScript(): String {\n';
middle += '        val sb = StringBuilder()\n';
middle += '        sb.append("echo \\"=== Android-x86 虚拟机 Phase 1: ISO 下载 ===\\"\\n")\n';
middle += '        sb.append("echo \\"容器模式：ISO 在 Termux 层下载，共享目录自动同步到容器\\"\\n\\n")\n';
middle += '        sb.append(generateNativeDownloadScript())\n';
middle += '        return sb.toString()\n';
middle += '    }\n';

const final = before + "\n" + middle + "\n" + after;
fs.writeFileSync(path, final, "utf8");
console.log("OK, total lines:", final.split(/\r?\n/).length);
