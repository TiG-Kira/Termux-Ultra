const fs = require("fs");
const path = "D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AndroidVmConfig.kt";
let raw = fs.readFileSync(path, "utf8");
let lines = raw.split(/\r?\n/);

// 打印 L170-L205 和 L265-L275 检查 data class 的括号位置
console.log("== L170..L205 ==");
for (let i = 169; i <= 204; i++) console.log(i+1, lines[i]);
console.log("\n== L265..L280 ==");
for (let i = 264; i <= 279; i++) console.log(i+1, lines[i]);
