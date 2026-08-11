const fs = require("fs");
const path = "D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AndroidVmConfig.kt";
let raw = fs.readFileSync(path, "utf8");
let lines = raw.split(/\r?\n/);
console.log("L382 chars:");
for (let i = 0; i < lines[381].length; i++) {
    const c = lines[381][i];
    let code = c.charCodeAt(0).toString(16).padStart(4, "0");
    if (code >= 0x20 && code <= 0x7e) console.log(i, code, JSON.stringify(c));
    else console.log(i, code, c);
}
