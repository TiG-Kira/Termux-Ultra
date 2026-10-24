"""Run audit regression tests with an existing Gradle Kotlin/JUnit cache (no Android build)."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
out = root / "build/audit-tests"
out.mkdir(parents=True, exist_ok=True)

def jar(group, module, version=None):
    base = cache / group / module
    versions = [base / version] if version else sorted(base.iterdir(), reverse=True)
    for directory in versions:
        files = list(directory.rglob("*.jar"))
        if files:
            return str(files[0])
    raise RuntimeError(f"Missing cached dependency: {group}:{module}")

dependencies = [
    jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.20"),
    jar("org.jetbrains.kotlin", "kotlin-stdlib"),
    jar("org.jetbrains.kotlin", "kotlin-script-runtime"),
    jar("org.jetbrains.kotlin", "kotlin-reflect"),
    jar("org.jetbrains", "annotations"),
    jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
    jar("junit", "junit", "4.13.2"),
    jar("org.hamcrest", "hamcrest-core"),
]
classpath = os.pathsep.join(dependencies)
sources = [
    "app/src/main/java/com/termux/app/compose/SafeFileOperations.kt",
    "app/src/main/java/com/termux/app/plugin/PluginArchive.kt",
    "app/src/main/java/com/termux/app/ssh/ShellQuote.kt",
    "app/src/main/java/com/termux/app/ssh/SshCommands.kt",
    "app/src/main/java/com/termux/app/ssh/SshConnection.kt",
    "app/src/main/java/com/termux/app/ftp/FtpPathPolicy.kt",
    "app/src/main/java/com/termux/app/ftp/FtpServer.kt",
    "app/src/test/java/com/termux/app/compose/SafeFileOperationsTest.kt",
    "app/src/test/java/com/termux/app/plugin/PluginArchiveTest.kt",
    "app/src/test/java/com/termux/app/ssh/ShellQuoteTest.kt",
    "app/src/test/java/com/termux/app/ftp/FtpPathPolicyTest.kt",
    "app/src/test/java/com/termux/app/ftp/FtpServerTest.kt",
]
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.name == "nt" else "java"
args = ["-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-classpath", classpath, "-d", str(out / "classes"), *[str(root / s) for s in sources]]
argfile = out / "compiler.args"
argfile.write_text("\n".join('"' + a.replace("\\", "/").replace('"', '\\"') + '"' for a in args), encoding="utf-8")
subprocess.run([java, "@" + str(argfile)], check=True)
subprocess.run([java, "-cp", str(out / "classes") + os.pathsep + classpath, "org.junit.runner.JUnitCore",
                "com.termux.app.compose.SafeFileOperationsTest", "com.termux.app.plugin.PluginArchiveTest",
                "com.termux.app.ssh.ShellQuoteTest", "com.termux.app.ftp.FtpPathPolicyTest",
                "com.termux.app.ftp.FtpServerTest"], check=True)
