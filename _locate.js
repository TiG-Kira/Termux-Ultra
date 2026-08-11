const fs = require("fs");
const path = "D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AndroidVmConfig.kt";
let raw = fs.readFileSync(path, "utf8");
let lines = raw.split(/\r?\n/);
console.log("== L458..L474 ==");
for (let i = 457; i <= 473 && i < lines.length; i++) console.log(i+1, JSON.stringify(lines[i]));
console.log("TOTAL lines:", lines.length);
