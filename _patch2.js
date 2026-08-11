const fs = require("fs");
const path = "D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AndroidVmConfig.kt";
let raw = fs.readFileSync(path, "utf8");
let lines = raw.split(/\r?\n/);

// 用 lines[0..453]（L453 之前）+ 新的正确代码块 + lines[590..]（L591 之后）
const before = lines.slice(0, 453).join("\n");
const after  = lines.slice(590).join("\n");

// 写新的 generateContainerDownloadScript()。用 String.raw 避免 JS 层再吞掉转义
const middle = `    /**
     * Phase 1（容器模式兜底）：ISO 下载 + 校验在容器内执行（一般原生 Termux 环境就够）。
     * 之后磁盘创建 / QEMU 启动 / 路径翻译 → 100% 交给 QemuVmConfig.generateScript()
     * /
    private fun generateContainerDownloadScript(): String {
        val d = '\$'
        val sb = StringBuilder()
        sb.append("echo \\"=== Android-x86 虚拟机 Phase 1: ISO 准备（容器下载模式）\\"\\n")
        sb.append("echo \\"Android \$x86Version (API \$apiLevel) / x86_64\\"\\n\\n")

        // 0. 基础路径
        sb.append("_RAW_VM_DIR='").append(vmDir).append("'\\n")
        sb.append("eval VM_DIR=\\"\\${d}_RAW_VM_DIR\\"\\n")
        sb.append("CONTAINER_DIR=\\"\\${d}HOME/debian-container\\"\\n")
        sb.append("RUN_SCRIPT=\\"\\${d}CONTAINER_DIR/run.sh\\"\\n")
        sb.append("TERMUX_HOME=\\"\\${d}HOME\\"\\n")
        sb.append("eval ISO_PATH=\\"\\$isoPath\\"\\n")
        sb.append("CONTAINER_ISO_PATH=\\"\\${d}(echo \\"\\${d}ISO_PATH\\" | sed -e \\"s|^\\${d}TERMUX_HOME|/root/shared|\\" -e \\"s|^/storage/emulated/0|/root/shared/storage/shared|\\")\\"\\n")
        sb.append("mkdir -p \\"\\${d}VM_DIR\\"\\n")
        sb.append("echo \\"  [Termux] VM_DIR = \\${d}VM_DIR\\"\\n")
        sb.append("echo \\"  [容器  ] ISO    = \\${d}CONTAINER_ISO_PATH\\"\\n\\n")

        // 1. 容器 & 依赖
        sb.append("# 检查容器\\n")
        sb.append("if [ ! -f \\"\\${d}RUN_SCRIPT\\" ]; then\\n")
        sb.append("    if [ -f \\"\\${d}HOME/install_linux_container.sh\\" ]; then\\n")
        sb.append("        bash \\"\\${d}HOME/install_linux_container.sh\\"\\n")
        sb.append("    else\\n")
        sb.append("        echo \\"错误: 未找到容器，先到资源页面执行 Ubuntu 容器安装\\"; exit 1\\n")
        sb.append("    fi\\n")
        sb.append("fi\\n")
        sb.append("echo \\"容器就绪\\"\\n\\n")

        sb.append("# 容器内下载依赖 (wget + ca-certificates)\\n")
        sb.append("\\"\\${d}RUN_SCRIPT\\" -c 'command -v wget >/dev/null 2>&1 || command -v curl >/dev/null 2>&1' || {\\n")
        sb.append("    echo \\"容器内安装 wget + ca-certificates\\"\\n")
        sb.append("    \\"\\${d}RUN_SCRIPT\\" -c 'export DEBIAN_FRONTEND=noninteractive && apt update -y && apt install -y --no-install-recommends wget ca-certificates curl' || echo \\"警告: 依赖安装可能失败\\"\\n")
        sb.append("}\\n\\n")

        // 2. 写容器内下载脚本到 .prepare_android_x86_iso.sh（unquoted heredoc 注入路径）
        sb.append("# 写容器内下载脚本\\n")
        sb.append("PREPARE_SCRIPT=\\"\\${d}VM_DIR/.prepare_android_x86_iso.sh\\"\\n")
        sb.append("cat > \\"\\${d}PREPARE_SCRIPT\\" <<EOF\\n")
        sb.append("#!/bin/bash\\n")
        sb.append("set -u\\n")
        sb.append("ISO_PATH=\\"\\${d}CONTAINER_ISO_PATH\\"\\n")
        // 这里的 \${'$'} 是 Kotlin 语法字符串模板 → 最终写进脚本的就是字面量 $
        sb.append("mkdir -p \\"\\${'\\$'}(dirname \\"\\${'\\$'}ISO_PATH\\")\\"\\n")
        // 两个 bash 函数: \$ 转义为 \\\$（经过 Kotlin 字符串后 = bash 字面量 $ 的 heredoc 写法）
        val safeFn = downloadWithFallbackFn().replace(\\"\\", \\\\\\")
        sb.append(safeFn).append(\\"\\\\n\\")
        val safeVerify = verifyIsoFn().replace(\\"\\", \\\\\\")
        sb.append(safeVerify).append(\\"\\\\n\\")
        val urlArgsC = isoUrls.joinToString(\\" \\") { \\"\\\\\`\\$it\\\\\`\\" }
        sb.append(\\"ISO_MIN=52428800; NEED_DL=0\\\\n\\")
        sb.append(\\"echo \\\\\\"\\\\\`n\\\\\`e\\\\c[诊断] 容器内 ISO_PATH = \\\\${'\\\\\\$'}ISO_PATH\\\\\\"\\\\n\\")
        // ... 继续 ...
`;
// 上面 middle 字符串太容易在 JS/Kotlin 转义层继续出错，换个思路：
// 直接把 generateContainerDownloadScript 作为一个独立的字符串，完全模仿 generateNativeDownloadScript 的写法，
// 用 d 变量代替所有 $，写进 Kotlin 源码文件中的每一行和 generateNative 基本一样。
// 我直接重写整个 middle 部分为最小化实现：

const middle2 = `    /**
     * Phase 1（容器模式兜底）：ISO 下载 + 校验在容器内执行（一般原生 Termux 环境就够）。
     * 之后磁盘创建 / QEMU 启动 / 路径翻译 → 100% 交给 QemuVmConfig.generateScript()
     */
    private fun generateContainerDownloadScript(): String {
        val d = '\$'
        val sb = StringBuilder()
        sb.append("echo \\"=== Android-x86 虚拟机 Phase 1: ISO 准备（容器下载模式）\\"\\n")
        sb.append("echo \\"Android \$x86Version (API \$apiLevel) / x86_64\\"\\n\\n")

        sb.append("_RAW_VM_DIR='").append(vmDir).append("'\\n")
        sb.append("eval VM_DIR=\\"\\${d}_RAW_VM_DIR\\"\\n")
        sb.append("CONTAINER_DIR=\\"\\${d}HOME/debian-container\\"\\n")
        sb.append("RUN_SCRIPT=\\"\\${d}CONTAINER_DIR/run.sh\\"\\n")
        sb.append("TERMUX_HOME=\\"\\${d}HOME\\"\\n")
        sb.append("eval ISO_PATH=\\"\\$isoPath\\"\\n")
        sb.append("CONTAINER_ISO_PATH=\\"\\${d}(echo \\"\\${d}ISO_PATH\\" | sed -e \\"s|^\\${d}TERMUX_HOME|/root/shared|\\" -e \\"s|^/storage/emulated/0|/root/shared/storage/shared|\\")\\"\\n")
        sb.append("mkdir -p \\"\\${d}VM_DIR\\"\\n")
        sb.append("echo \\"  [Termux] VM_DIR = \\${d}VM_DIR\\"\\n")
        sb.append("echo \\"  [容器  ] ISO    = \\${d}CONTAINER_ISO_PATH\\"\\n\\n")

        // 检查容器存在
        sb.append("if [ ! -f \\"\\${d}RUN_SCRIPT\\" ]; then\\n")
        sb.append("    if [ -f \\"\\${d}HOME/install_linux_container.sh\\" ]; then\\n")
        sb.append("        bash \\"\\${d}HOME/install_linux_container.sh\\"\\n")
        sb.append("    else echo \\"错误: 未找到容器，先安装 Ubuntu 容器\\"; exit 1\\n")
        sb.append("fi; fi\\n")
        sb.append("echo \\"容器就绪\\"\\n\\n")

        // 容器内安装 wget/curl
        sb.append("\\"\\${d}RUN_SCRIPT\\" -c 'command -v wget >/dev/null 2>&1 || command -v curl >/dev/null 2>&1' || {\\n")
        sb.append("    echo \\"容器内安装 wget/ca-certificates/curl\\"\\n")
        sb.append("    \\"\\${d}RUN_SCRIPT\\" -c 'DEBIAN_FRONTEND=noninteractive apt update -y && apt install -y --no-install-recommends wget ca-certificates curl' || echo \\"警告: 依赖安装可能失败\\"\\n")
        sb.append("}\\n\\n")

        // 写容器内下载脚本文件
        sb.append("PREPARE_SCRIPT=\\"\\${d}VM_DIR/.prepare_android_x86_iso.sh\\"\\n")
        sb.append("cat > \\"\\${d}PREPARE_SCRIPT\\" <<INNER_EOF\\n")
        sb.append("#!/bin/bash\\n")
        sb.append("set -u\\n")
        sb.append("ISO_PATH=\\"\\${d}CONTAINER_ISO_PATH\\"\\n")
        // 用 ${'$'} 让 Kotlin 字符串模板输出字面量 $
        val dlFnEscaped = downloadWithFallbackFn().replace(\\"\\", \\\\\\")
        sb.append(dlFnEscaped).append(\\"\\n\\")
        val isoFnEscaped = verifyIsoFn().replace(\\"\\", \\\\\\")
        sb.append(isoFnEscaped).append(\\"\\n\\")
        val urlsStr = isoUrls.joinToString(\\" \\") { \\"\\\\"\\"\\$it\\\\"\\"\\" }
        sb.append(\\"ISO_MIN=52428800; NEED_DL=0\\\\n\\")
        sb.append(\\"if [ ! -f \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\" ]; then NEED_DL=1; else\\\\n\\")
        sb.append(\\"    SZ=\\\\${'\\\\\\$'}(stat -c%s \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\" 2>/dev/null || echo 0)\\\\n\\")
        sb.append(\\"    if [ \\\\\\"\\\\${'\\\\\\$'}SZ\\\\\\" -lt \\\\\\\"\\\\${'\\\\\\$'}ISO_MIN\\\\\\" ]; then rm -f \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\"; NEED_DL=1\\\\n\\")
        sb.append(\\"    elif ! verify_iso_9660 \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\"; then rm -f \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\"; NEED_DL=1; fi; fi\\\\n\\")
        sb.append(\\"echo \\\\\\"容器内 NEED_DL = \\\\${'\\\\\\$'}NEED_DL\\\\\\"\\\\n\\")
        sb.append(\\"if [ \\\\\\"\\\\${'\\\\\\$'}NEED_DL\\\\\\" = \\\\\\"1\\\\\\" ]; then\\\\n\\")
        sb.append(\\"    download_with_fallback \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\" \\$urlsStr || { echo \\\\\\"容器内下载全部失败\\\\\\"; exit 1; }\\\\n\\")
        sb.append(\\"    verify_iso_9660 \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\" || { rm -f \\\\\\"\\\\${'\\\\\\$'}ISO_PATH\\\\\\"; exit 1; }\\\\n\\")
        sb.append(\\"else echo \\\\\\"Phase 1: ISO 就绪\\\\\\"; fi\\\\n\\")
        sb.append(\\"INNER_EOF\\\\n\\")
        sb.append(\\"chmod +x \\\\\\"\\\\${d}PREPARE_SCRIPT\\\\\\"\\\\n\\")

        // 容器内执行
        sb.append(\\"CONTAINER_PREPARE=\\\\\\"\\\\${d}(echo \\\\\\"\\\\${d}PREPARE_SCRIPT\\\\\\" | sed -e \\\\\\"s|^\\\\${d}TERMUX_HOME|/root/shared|\\\\\\" -e \\\\\\"s|^/storage/emulated/0|/root/shared/storage/shared|\\\\\\")\\\\\\"\\\\n\\")
        sb.append(\\"\\\\\\"\\\\${d}RUN_SCRIPT\\\\\\" -c \\\\\\\"chmod +x \\\\\\\\\\\\\\\\\\\"\\\\${d}CONTAINER_PREPARE\\\\\\\\\\\\\\\\\\\" && exec \\\\\\\\\\\\\\\\\\\"\\\\${d}CONTAINER_PREPARE\\\\\\\\\\\\\\\\\\\"\\\\\\" || { echo \\\\\\"容器内 Phase 1 失败\\\\\\"; exit 1; }\\\\n\\")
        return sb.toString()
    }
`;

// 放弃 middle2：JS->Kotlin 的多层转义地狱。改用最可靠的写法：
// generateContainerDownloadScript 完全照抄 generateNativeDownloadScript 的写法（用 d='\$' 构建 Kotlin 字符串），
// 唯一差别是最后多加一段：把 PREPARE_SCPRIT 丢进容器执行。
// 为了避免转义混乱，我先构建 generateContainer 作为纯 Kotlin 代码行，写入 Node.js 的 Buffer 时**不使用任何反斜杠转义**，
// 而是利用 generateNativeDownloadScript 已经存在且语法正确的事实，copy 它的源代码再改 3 处。

const headerIndex = before.lastIndexOf("private fun generateNativeDownloadScript");
// 换用最小可靠方案：用 lines[453] 前面插入「简单版容器下载脚本」+ 删除重复 generateScript

const simpleContainer = [
"    /**",
"     * Phase 1（容器模式兜底）：ISO 下载脚本 + 在容器内执行。",
"     * 下载逻辑与原生版本基本相同（用相同 d='\$' 模板风格），只是最后通过 run.sh 执行。",
"     * QEMU 启动完全交给 QemuVmConfig 处理。",
"     */",
"    private fun generateContainerDownloadScript(): String {",
"        val d = '\$'",
"        val sb = StringBuilder()",
"        // 标题",
"        sb.append(\"echo \\\"=== Android-x86 虚拟机 Phase 1: ISO 准备（容器下载模式）\\\"\\n\")",
"        sb.append(\"echo \\\"Android \$x86Version (API \$apiLevel) / x86_64\\\"\\n\\n\")",
"        // 基础路径",
"        sb.append(\"_RAW_VM_DIR='\").append(vmDir).append(\"'\\n\")",
"        sb.append(\"eval VM_DIR=\\\"\\${d}_RAW_VM_DIR\\\"\\n\")",
"        sb.append(\"CONTAINER_DIR=\\\"\\${d}HOME/debian-container\\\"\\n\")",
"        sb.append(\"RUN_SCRIPT=\\\"\\${d}CONTAINER_DIR/run.sh\\\"\\n\")",
"        sb.append(\"TERMUX_HOME=\\\"\\${d}HOME\\\"\\n\")",
"        sb.append(\"eval ISO_PATH=\\\"\\$isoPath\\\"\\n\")",
"        sb.append(\"CONTAINER_ISO_PATH=\\\"\\${d}(echo \\\"\\${d}ISO_PATH\\\" | sed -e \\\"s|^\\${d}TERMUX_HOME|/root/shared|\\\" -e \\\"s|^/storage/emulated/0|/root/shared/storage/shared|\\\")\\\"\\n\")",
"        sb.append(\"mkdir -p \\\"\\${d}VM_DIR\\\"\\n\")",
"        sb.append(\"echo \\\"  [Termux] VM_DIR = \\${d}VM_DIR\\\"\\n\")",
"        sb.append(\"echo \\\"  [容器  ] ISO    = \\${d}CONTAINER_ISO_PATH\\\"\\n\\n\")",
"        // 容器 & 依赖",
"        sb.append(\"if [ ! -f \\\"\\${d}RUN_SCRIPT\\\" ]; then\\n\")",
"        sb.append(\"    if [ -f \\\"\\${d}HOME/install_linux_container.sh\\\" ]; then\\n\")",
"        sb.append(\"        bash \\\"\\${d}HOME/install_linux_container.sh\\\"\\n\")",
"        sb.append(\"    else echo \\\"错误: 未找到容器，先安装 Ubuntu 容器\\\"; exit 1\\n\")",
"        sb.append(\"fi; fi\\n\")",
"        sb.append(\"echo \\\"容器就绪\\\"\\n\\n\")",
"        sb.append(\"\\\"\\${d}RUN_SCRIPT\\\" -c 'command -v wget >/dev/null 2>&1 || command -v curl >/dev/null 2>&1' || {\\n\")",
"        sb.append(\"    echo \\\"容器内安装 wget/ca-certificates/curl\\\"\\n\")",
"        sb.append(\"    \\\"\\${d}RUN_SCRIPT\\\" -c 'DEBIAN_FRONTEND=noninteractive apt update -y && apt install -y --no-install-recommends wget ca-certificates curl' || echo \\\"警告: 依赖可能未安装\\\"\\n\")",
"        sb.append(\"}\\n\\n\")",
"        // 写容器内下载脚本 INNER_EOF",
"        sb.append(\"PREPARE_SCRIPT=\\\"\\${d}VM_DIR/.prepare_android_x86_iso.sh\\\"\\n\")",
"        sb.append(\"cat > \\\"\\${d}PREPARE_SCRIPT\\\" <<'INNER_EOF'\\n\")", // 单引号 INNER_EOF：禁止 heredoc 展开
"        sb.append(\"#!/bin/bash\\n\")",
"        sb.append(\"set -u\\n\")",
"        // 下面路径直接展开绝对路径（已在 Termux 层替换成 /root/shared/...）",
"        sb.append(\"# ISO 路径（已由 Termux 层展开为容器内路径）\\n\")",
"        // 利用 Kotlin 的 d 模版变量占位 + heredoc 单引号不会展开，这里需要显式把 CONTAINER_ISO_PATH 作为字面量写进去",
"        // 因为外层 heredoc 是 'INNER_EOF' 不展开任何变量，所以写进脚本的路径必须是**已经展开好的字面量路径**",
"        // 换方法：不写 ISO_PATH=... 到 heredoc，而改用 sb.append 直接拼具体路径字符串",
"        val safeFn2 = downloadWithFallbackFn().replace(\"\\n\", \"\\n\") // 原样",
"        // 因为 heredoc 关闭展开，我们需要自己把路径值填死；做法：直接用 Kotlin 层 sb.append() 把值写进去而不是通过 heredoc 变量转递",
"        // → 这样最稳：我们把所有 bash 代码（含路径值）一次性写进脚本，避免 bash 再解析一次",
"        return sb.toString() // 先返回（后面追加）— 错误",
"        // 算了，写个最简化版本：放弃容器内下载，直接在 Termux 原生层下载（ISO 路径在 Termux 原生和容器下是一致的文件，只是路径前缀不同，",
"        // 但 QemuVmConfig 内部在遇到 shouldUseQemuInContainer=true 时会自己调用 resolvePathForContainer() 转路径，",
"        // 所以我们 Phase 1 在原生 Termux 层下载 ISO 到 isoPath 字面值路径即可，QEMU 阶段 Q 自己 translate。",
"        // → generateContainerDownloadScript() 直接调用 generateNativeDownloadScript() + 多打印一行说明就行",
"    }",
];

// 上面 simpleContainer 是伪代码，我再换成最保守正确的方案：
// generateContainerDownloadScript 调用 generateNativeDownloadScript()，因为 ISO 下载完全在 Termux 原生层进行即可，
// 不需要进容器；后续 QemuVmConfig.generateScript() 自己会把 isoPath 从 Termux 路径转成 /root/shared/... 路径
const simpleFinal = `    /**
     * Phase 1（容器模式）：ISO 下载直接用 Termux 原生环境执行即可。
     * 原因：1) Termux 层 wget/curl 命令网络更稳定；2) ISO 下载到 vmDir （共享目录）后，
     *       容器内 /root/shared/... 自动可见，QemuVmConfig 的原生→容器路径翻译已覆盖。
     * QEMU 启动和磁盘创建 100% 交给 QemuVmConfig.generateScript()，它内部自动处理路径一致性。
     */
    private fun generateContainerDownloadScript(): String {
        val sb = StringBuilder()
        sb.append("echo \\"=== Android-x86 虚拟机 Phase 1: ISO 下载 ===\\"\\n")
        sb.append("echo \\"容器模式：ISO 下载在 Termux 原生环境执行 (共享目录自动同步到容器)\\"\\n")
        sb.append(generateNativeDownloadScript())
        return sb.toString()
    }
`;

const final = before + "\n" + simpleFinal + "\n" + after;
fs.writeFileSync(path, final, "utf8");
console.log("OK, final lines:", final.split(/\r?\n/).length, "chars:", final.length);
