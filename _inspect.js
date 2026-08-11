const fs = require("fs");
const path = "D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AndroidVmConfig.kt";
let raw = fs.readFileSync(path, "utf8");
let lines = raw.split(/\r?\n/);

// ── 先扫一遍 L454~L531 (generateContainerDownloadScript) 所有 sb.append("echo "xxx"...") 的错误引号 ──
// 正确应为 sb.append("echo \"xxx\"\n")
// 所以把所有 sb.append("echo "...) 中的内部双引号补反斜杠
// 实际上更简单：用正则把 "echo " 后的第二个裸 " 换成 \", 但因为整体结构乱了，不如重写 generateContainerDownloadScript 整个函数块
// L454 是函数开始, L531 是函数结束(对应的是结束括号行)

// 更彻底: 用正则定位 "private fun generateContainerDownloadScript(): String {" 到它结束的 "    }" 行,
// 同时删除紧随其后重复的第二个 generateScript()(到 L589 的 "}")

// 用精确的行号处理
// 先打印 L450-L595 行内容确认编号 (用 0-based 数组索引)
console.log("lines 453..594:");
for (let i = 453; i <= 594 && i < lines.length; i++) {
    console.log(String(i+1).padStart(4), lines[i]);
}
