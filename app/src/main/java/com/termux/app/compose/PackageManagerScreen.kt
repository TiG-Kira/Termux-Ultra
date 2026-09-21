package com.termux.app.compose

import android.content.Context
import com.termux.R
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.compat.ShellEnvironmentCompat
import com.termux.shared.compat.TermuxTaskCompat
import com.termux.shared.termux.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

object AppShell {

    suspend fun exec(context: Context, command: String, timeout: Int = 60): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val shell = resolveShell()
            if (shell == null) return@withContext Pair(-1, "找不到 shell")

            val ec = ExecutionCommand(
                System.currentTimeMillis().toInt(),
                shell,
                arrayOf("-c", command),
                null, null, "app-shell", false
            )
            val client = ShellEnvironmentCompat(TermuxShellEnvironment())
            val task = try {
                TermuxTaskCompat.execute(context, ec, null, client, false)
            } catch (e: Exception) {
                return@withContext Pair(-1, e.message ?: "执行失败")
            }

            val deadline = System.currentTimeMillis() + timeout * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(150)
                if (ec.hasExecuted() || ec.resultData.exitCode != null) break
            }
            runCatching { task?.killIfExecuting(context, false) }

            val rd = ec.resultData
            val out = rd.stdout.toString()
            val err = rd.stderr.toString()
            val code = rd.exitCode ?: -1
            Pair(code, if (out.isNotBlank()) out else err)
        }

    /**
     * 流式执行 shell 命令。每 150ms 读取一次 stdout/stderr 增量，
     * 通过 onOutput 回调实时推送。
     */
    suspend fun execStreaming(
        context: Context,
        command: String,
        timeout: Int = 60,
        onOutput: (String) -> Unit
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val shell = resolveShell()
        if (shell == null) return@withContext Pair(-1, "找不到 shell")

        val ec = ExecutionCommand(
            System.currentTimeMillis().toInt(),
            shell,
            arrayOf("-c", command),
            null, null, "app-shell", false
        )
        val client = ShellEnvironmentCompat(TermuxShellEnvironment())
        val task = try {
            TermuxTaskCompat.execute(context, ec, null, client, false)
        } catch (e: Exception) {
            return@withContext Pair(-1, e.message ?: "执行失败")
        }

        var lastStdoutLen = 0
        var lastStderrLen = 0
        val deadline = System.currentTimeMillis() + timeout * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            val rd = ec.resultData
            val outLen = rd.stdout.length
            val errLen = rd.stderr.length
            if (outLen > lastStdoutLen || errLen > lastStderrLen) {
                val sb = StringBuilder()
                if (outLen > lastStdoutLen) {
                    sb.append(rd.stdout.substring(lastStdoutLen))
                }
                if (errLen > lastStderrLen) {
                    sb.append(rd.stderr.substring(lastStderrLen))
                }
                val delta = sb.toString()
                if (delta.isNotBlank()) onOutput(delta)
                lastStdoutLen = outLen
                lastStderrLen = errLen
            }
            if (ec.hasExecuted() || rd.exitCode != null) break
        }
        runCatching { task?.killIfExecuting(context, false) }

        val rd = ec.resultData
        val out = rd.stdout.toString()
        val err = rd.stderr.toString()
        val code = rd.exitCode ?: -1
        val fullOut = if (out.isNotBlank()) out else err
        Pair(code, fullOut)
    }

    private fun resolveShell(): String? {
        val binDir = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
        if (binDir.isNotEmpty()) {
            for (name in arrayOf("bash", "login", "zsh", "sh")) {
                val f = File(binDir, name)
                if (f.exists() && f.canExecute()) return f.absolutePath
            }
        }
        val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        for (name in arrayOf("bash", "sh")) {
            val f = File("$prefix/bin", name)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        return null
    }
}

data class PackageInfo(
    val name: String,
    val version: String = "",
    val description: String = "",
    val isInstalled: Boolean = false,
    val homepage: String = "",
    val depends: List<String> = emptyList(),
    val maintainer: String = "",
    val conflicts: List<String> = emptyList(),
    val license: String = "",
    val size: String = "",
    val section: String = ""
) {
    /** 启发式 section 回退：pkg list-installed / list-all 输出不带 Section，
     *  按包名前缀硬映射一个分类 key（与 pkg show 的 Section 字段统一 key 体系）。
     *  找不到规则时返回 "other"。 */
    fun resolveSection(): String = section.ifBlank { SectionClassifier.classify(name) }
}

/** 分类视图导航栈层级 */
data class PkgNavLevel(
    val sectionKey: String?,   // null = 根层级（显示分类列表）；非空 = 该 section 下的包列表
    val label: String?         // UI 显示用；null 时显示"全部软件包"
)

/** 启发式 section 分类器 —— 覆盖 Termux 常见包 */
object SectionClassifier {
    // 规则：按包名前缀 / 子串匹配，顺序从上到下，先匹配先生效
    private val RULES: List<Pair<Regex, String>> = listOf(
        // 开发语言生态
        Regex("^python[0-9.]*-") -> "python",
        Regex("^python$") -> "python",
        Regex("^pip[0-9.]*$") -> "python",
        Regex("^perl-") -> "perl",
        Regex("^perl$") -> "perl",
        Regex("^ruby-") -> "ruby",
        Regex("^ruby$") -> "ruby",
        Regex("^gem$") -> "ruby",
        Regex("^openjdk") -> "java",
        Regex("^java-") -> "java",
        Regex("^kotlin") -> "java",
        // 开发工具链
        Regex("^clang") -> "devel",
        Regex("^gcc") -> "devel",
        Regex("^g\\+\\+") -> "devel",
        Regex("^cmake") -> "devel",
        Regex("^make$") -> "devel",
        Regex("^meson") -> "devel",
        Regex("^ninja$") -> "devel",
        Regex("^autoconf") -> "devel",
        Regex("^automake") -> "devel",
        Regex("^libtool") -> "devel",
        Regex("^pkg-config") -> "devel",
        Regex("^llvm") -> "devel",
        Regex("^lldb") -> "devel",
        Regex("^golang") -> "devel",
        Regex("^rust") -> "devel",
        Regex("^cargo$") -> "devel",
        Regex("^nodejs") -> "devel",
        Regex("^npm$") -> "devel",
        Regex("^yarn$") -> "devel",
        // 版本控制
        Regex("^git$") -> "vcs",
        Regex("^git-lfs$") -> "vcs",
        Regex("^hg$") -> "vcs",
        Regex("^svn$") -> "vcs",
        // 开发库（尾缀 -dev / -static / -headers 或前缀 lib）
        Regex("(^|[-_.])dev$") -> "libs",
        Regex("-dev$") -> "libs",
        Regex("-static$") -> "libs",
        Regex("-headers$") -> "libs",
        Regex("^lib[a-z0-9]") -> "libs",
        // 网络
        Regex("^curl$") -> "net",
        Regex("^wget$") -> "net",
        Regex("^openssl$") -> "net",
        Regex("^openssh$") -> "net",
        Regex("^sshpass$") -> "net",
        Regex("^nmap$") -> "net",
        Regex("^tcpdump$") -> "net",
        Regex("^netcat") -> "net",
        Regex("^nc$") -> "net",
        Regex("^whois$") -> "net",
        Regex("^dnsutils$") -> "net",
        Regex("^inetutils") -> "net",
        Regex("^iproute2$") -> "net",
        Regex("^iptables$") -> "net",
        Regex("^dhcp$") -> "net",
        Regex("^tor$") -> "net",
        Regex("^proxychains") -> "net",
        Regex("^gnupg") -> "net",
        // Shell / 终端
        Regex("^bash$") -> "shell",
        Regex("^zsh$") -> "shell",
        Regex("^fish$") -> "shell",
        Regex("^dash$") -> "shell",
        Regex("^tcsh$") -> "shell",
        Regex("^screen$") -> "shell",
        Regex("^tmux$") -> "shell",
        // 编辑器
        Regex("^vim$") -> "editors",
        Regex("^nvim$") -> "editors",
        Regex("^neovim$") -> "editors",
        Regex("^emacs") -> "editors",
        Regex("^nano$") -> "editors",
        Regex("^micro$") -> "editors",
        Regex("^jed$") -> "editors",
        // 图形
        Regex("^xorg") -> "x11",
        Regex("^xfce") -> "x11",
        Regex("^lxde") -> "x11",
        Regex("^openbox$") -> "x11",
        Regex("^glib$") -> "graphics",
        Regex("^imagemagick") -> "graphics",
        Regex("^ffmpeg$") -> "video",
        Regex("^vlc$") -> "video",
        Regex("^mplayer$") -> "video",
        Regex("^mpv$") -> "video",
        Regex("^pulseaudio") -> "sound",
        Regex("^sox$") -> "sound",
        Regex("^lame$") -> "sound",
        // 数据库
        Regex("^sqlite") -> "database",
        Regex("^mysql") -> "database",
        Regex("^postgresql") -> "database",
        Regex("^redis$") -> "database",
        Regex("^mongodb") -> "database",
        // 实用工具
        Regex("^busybox") -> "utils",
        Regex("^coreutils$") -> "utils",
        Regex("^util-linux$") -> "utils",
        Regex("^findutils$") -> "utils",
        Regex("^grep$") -> "utils",
        Regex("^sed$") -> "utils",
        Regex("^awk$") -> "utils",
        Regex("^gawk$") -> "utils",
        Regex("^tar$") -> "utils",
        Regex("^gzip$") -> "utils",
        Regex("^bzip2$") -> "utils",
        Regex("^xz-utils$") -> "utils",
        Regex("^zip$") -> "utils",
        Regex("^unzip$") -> "utils",
        Regex("^7zip") -> "utils",
        Regex("^rsync$") -> "utils",
        Regex("^time$") -> "utils",
        Regex("^which$") -> "utils",
        Regex("^file$") -> "utils",
        Regex("^bc$") -> "math",
        Regex("^dc$") -> "math",
    )

    fun classify(name: String): String {
        val n = name.lowercase()
        for ((re, section) in RULES) {
            if (re.containsMatchIn(n)) return section
        }
        return "other"
    }
}

object PkgRepo {

    /**
     * 把任意字符串转成 sh 的单引号字面量，防止命令注入。
     *
     * 所有 `AppShell.exec()` 最终都走 `sh -c "<command>"`，因此任何拼接进命令的
     * 用户输入（搜索关键字等）都必须先经过这里。否则关键字里的 `;` `$(...)`、
     * 反引号、`&&` 会被 shell 当作语法执行，造成命令注入；即便不含恶意字符，
     * 空格也会让 `pkg search` 收到被拆开的多个参数。
     */
    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun getInstalled(context: Context): List<PackageInfo> {
        val (code, output) = AppShell.exec(context, "pkg list-installed 2>/dev/null")
        if (code != 0) return emptyList()
        val result = mutableListOf<PackageInfo>()
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Last")) continue
            val nameVersion = trimmed.substringBefore('\t')
            val slashIdx = nameVersion.indexOf('/')
            if (slashIdx > 0) {
                val name = nameVersion.substring(0, slashIdx)
                val version = nameVersion.substring(slashIdx + 1)
                if (name.isNotBlank()) result.add(PackageInfo(name, version, isInstalled = true))
            }
        }
        return result
    }

    suspend fun getAvailableAll(context: Context): List<PackageInfo> {
        val (code, output) = AppShell.exec(context, "pkg list-all 2>/dev/null", timeout = 60)
        if (code != 0) return emptyList()
        val installedNames = getInstalledNames(context)
        val result = mutableListOf<PackageInfo>()
        val seen = mutableSetOf<String>()
        val pkgNameRegex = Regex("^[a-z0-9][a-z0-9+._-]*$")
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Last")) continue
            if (trimmed.startsWith("Installed package ")) continue
            // Skip repository URL lines and apt repo markers
            if (trimmed.contains("://")) continue
            if (trimmed.startsWith("[") && "]" in trimmed) continue
            val slashIdx = trimmed.indexOf('/')
            if (slashIdx > 0) {
                val beforeSlash = trimmed.substring(0, slashIdx).trim()
                val actualName = if (beforeSlash.startsWith("Package ")) {
                    beforeSlash.removePrefix("Package ").trim()
                } else {
                    beforeSlash
                }
                val versionPart = trimmed.substring(slashIdx + 1).substringBefore(' ').trim()
                if (actualName.isNotBlank() && actualName !in seen && actualName !in installedNames
                    && pkgNameRegex.matches(actualName)) {
                    seen.add(actualName)
                    result.add(PackageInfo(actualName, versionPart, isInstalled = false))
                }
            }
        }
        return result
    }

    suspend fun searchAvailable(context: Context, keyword: String): List<PackageInfo> {
        if (keyword.isBlank()) return emptyList()
        val (code, output) = AppShell.exec(context, "pkg search ${shq(keyword)} 2>/dev/null")
        if (code != 0) return emptyList()
        val result = mutableListOf<PackageInfo>()
        val installedNames = getInstalledNames(context)
        val seen = mutableSetOf<String>()
        val pkgNameRegex = Regex("^[a-z0-9][a-z0-9+._-]*$")
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Skip repository URL lines and apt repo markers
            if (trimmed.contains("://")) continue
            if (trimmed.startsWith("[") && "]" in trimmed) continue
            val slashIdx = trimmed.indexOf('/')
            if (slashIdx > 0) {
                val beforeSlash = trimmed.substring(0, slashIdx).trim()
                val actualName = if (beforeSlash.contains("package", ignoreCase = true)) {
                    beforeSlash.substringAfterLast(' ').ifBlank { beforeSlash }
                } else {
                    beforeSlash
                }
                val versionPart = trimmed.substring(slashIdx + 1).substringBefore(' ').trim()
                if (actualName.isNotBlank() && actualName !in seen && pkgNameRegex.matches(actualName)) {
                    seen.add(actualName)
                    result.add(
                        PackageInfo(
                            name = actualName,
                            version = versionPart,
                            isInstalled = installedNames.contains(actualName)
                        )
                    )
                }
            }
        }
        return result
    }

    suspend fun getDetail(context: Context, name: String): PackageInfo? {
        val installed = getInstalledNames(context)
        val (code, output) = AppShell.exec(context, "pkg show ${shq(name)} 2>/dev/null")
        if (code != 0 && output.isBlank()) return null

        val fields = mutableMapOf<String, String>()
        var lastKey = ""
        for (rawLine in output.lines()) {
            val line = rawLine
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (lastKey.isNotEmpty()) fields[lastKey] = (fields[lastKey] ?: "") + "\n" + line.trim()
            } else if (":" in line) {
                val colonIdx = line.indexOf(':')
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                lastKey = key
                fields[key] = value
            }
        }

        val depends = (fields["Depends"] ?: "").split(',').map { it.trim() }.filter { it.isNotBlank() }
        val conflicts = (fields["Conflicts"] ?: "").split(',').map { it.trim() }.filter { it.isNotBlank() }

        return PackageInfo(
            name = fields["Package"] ?: name,
            version = fields["Version"] ?: "",
            description = fields["Description"] ?: "",
            homepage = fields["Homepage"] ?: "",
            depends = depends,
            isInstalled = installed.contains(name),
            maintainer = fields["Maintainer"] ?: "",
            conflicts = conflicts,
            license = fields["License"] ?: "",
            size = fields["Size"] ?: "",
            section = fields["Section"] ?: ""
        )
    }

    /** 把 pkg 输出的 Section（Debian 标准）或启发式 key 归一化成分类 key。 */
    fun normalizeSectionKey(raw: String): String {
        val r = raw.trim().lowercase()
        if (r.isBlank()) return "other"
        // 常见别名 / Debian 原样名 → 我们的分类 key
        return when {
            r in listOf("python", "python3") -> "python"
            r in listOf("perl") -> "perl"
            r in listOf("ruby") -> "ruby"
            r in listOf("java", "java-vm", "openjdk") -> "java"
            r in listOf("devel", "development") -> "devel"
            r in listOf("libs", "library", "libraries") -> "libs"
            r in listOf("net", "network") -> "net"
            r in listOf("shells") -> "shell"
            r in listOf("editors") -> "editors"
            r in listOf("graphics") -> "graphics"
            r in listOf("video") -> "video"
            r in listOf("sound", "audio") -> "sound"
            r in listOf("x11") -> "x11"
            r in listOf("database", "databases") -> "database"
            r in listOf("mail") -> "mail"
            r in listOf("math") -> "math"
            r in listOf("science") -> "science"
            r in listOf("vcs") -> "vcs"
            r in listOf("admin", "admin/system") -> "admin"
            r in listOf("oldlibs") -> "oldlibs"
            r in listOf("utils", "misc") -> "utils"
            else -> r
        }
    }

    /** section key → 友好中文（用 stringResource） */
    fun sectionDisplayName(context: Context, key: String): String {
        val resId = when (normalizeSectionKey(key)) {
            "python" -> R.string.pkg_cat_python
            "perl" -> R.string.pkg_cat_perl
            "ruby" -> R.string.pkg_cat_ruby
            "java" -> R.string.pkg_cat_java
            "devel" -> R.string.pkg_cat_devel
            "libs" -> R.string.pkg_cat_libs
            "net" -> R.string.pkg_cat_net
            "shell" -> R.string.pkg_cat_shell
            "editors" -> R.string.pkg_cat_editors
            "graphics" -> R.string.pkg_cat_graphics
            "video" -> R.string.pkg_cat_video
            "sound" -> R.string.pkg_cat_sound
            "x11" -> R.string.pkg_cat_x11
            "database" -> R.string.pkg_cat_database
            "mail" -> R.string.pkg_cat_mail
            "math" -> R.string.pkg_cat_math
            "science" -> R.string.pkg_cat_science
            "vcs" -> R.string.pkg_cat_vcs
            "admin" -> R.string.pkg_cat_admin
            "oldlibs" -> R.string.pkg_cat_oldlibs
            "utils" -> R.string.pkg_cat_utils
            else -> R.string.pkg_cat_other
        }
        return context.getString(resId)
    }

    suspend fun install(context: Context, name: String, onOutput: ((String) -> Unit)? = null): Pair<Boolean, String> {
        val cmd = "export DEBIAN_FRONTEND=noninteractive && pkg install -y ${shq(name)} 2>&1"
        val (code, output) = if (onOutput != null) AppShell.execStreaming(context, cmd, timeout = 180, onOutput = onOutput)
                             else AppShell.exec(context, cmd, timeout = 180)
        return (code == 0) to output
    }

    suspend fun uninstall(context: Context, name: String, onOutput: ((String) -> Unit)? = null): Pair<Boolean, String> {
        val cmd = "export DEBIAN_FRONTEND=noninteractive && pkg uninstall -y ${shq(name)} 2>&1"
        val (code, output) = if (onOutput != null) AppShell.execStreaming(context, cmd, timeout = 60, onOutput = onOutput)
                             else AppShell.exec(context, cmd, timeout = 60)
        return (code == 0) to output
    }

    suspend fun update(context: Context, onOutput: ((String) -> Unit)? = null): Pair<Boolean, String> {
        val cmd = "export DEBIAN_FRONTEND=noninteractive && pkg update 2>&1"
        val (code, output) = if (onOutput != null) AppShell.execStreaming(context, cmd, timeout = 180, onOutput = onOutput)
                             else AppShell.exec(context, cmd, timeout = 180)
        return (code == 0) to output
    }

    suspend fun upgradeAll(context: Context, onOutput: ((String) -> Unit)? = null): Pair<Boolean, String> {
        val cmd = "export DEBIAN_FRONTEND=noninteractive && pkg upgrade -y 2>&1"
        val (code, output) = if (onOutput != null) AppShell.execStreaming(context, cmd, timeout = 300, onOutput = onOutput)
                             else AppShell.exec(context, cmd, timeout = 300)
        return (code == 0) to output
    }

    suspend fun hasLocks(context: Context): Boolean {
        val (_, out) = AppShell.exec(context, "pgrep -f 'pkg|apt|dpkg' 2>/dev/null")
        return out.trim().isNotEmpty()
    }

    suspend fun forceRemoveLocks(context: Context): String {
        val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        val locks = listOf(
            "$prefix/var/lib/apt/lists/lock",
            "$prefix/var/lib/dpkg/lock-frontend",
            "$prefix/var/lib/dpkg/lock",
            "$prefix/var/cache/apt/archives/lock",
            "$prefix/cache/apt/archives/lock"
        )
        val cmd = "rm -f ${locks.joinToString(" ")} 2>&1"
        val (_, out) = AppShell.exec(context, cmd)
        return out
    }

    private suspend fun getInstalledNames(context: Context): Set<String> {
        return getInstalled(context).map { it.name }.toSet()
    }
}

@Composable
fun PackageManagerScreen(
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onBackPressed: () -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val isDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

    var showProgressDialog by remember { mutableStateOf(false) }
    var progressTitle by remember { mutableStateOf("") }
    var progressLog by remember { mutableStateOf("") }
    var progressSuccess by remember { mutableStateOf<Boolean?>(null) }

    var selectedTab by remember { mutableStateOf(0) }
    var installedList by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var availableList by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadingAvailable by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf<PackageInfo?>(null) }

    // 分类视图状态
    private val pkgPrefs = remember {
        context.getSharedPreferences("termux_preferences", android.content.Context.MODE_PRIVATE)
    }
    val viewMode by remember { mutableStateOf(pkgPrefs.getInt("KEY_PKG_VIEW_MODE", 0)) }

    private var navStack by remember {
        mutableStateOf(listOf(PkgNavLevel(sectionKey = null, label = null)))
    }
    private val currentSection: String? get() = navStack.lastOrNull()?.sectionKey

    // 观察 LiveUpdateState — 实时 log + 后台任务按钮 + 恢复请求
    val livePkgLog by LiveUpdateState.pkgLog.collectAsState()
    val pkgStateSnap by LiveUpdateState.pkgState.collectAsState()

    LaunchedEffect(LiveUpdateState.pkgResumeRequest) {
        LiveUpdateState.pkgResumeRequest.collect { shouldResume ->
            if (shouldResume && LiveUpdateState.hasPkg()) {
                LiveUpdateState.consumeResumeRequest()
                val snap = LiveUpdateState.getPkgStateSnapshot()
                if (snap != null) {
                    showProgressDialog = true
                    progressTitle = when (snap.operation) {
                        LiveUpdateState.PkgOperation.UPDATE -> "正在刷新软件源"
                        LiveUpdateState.PkgOperation.UPGRADE -> "正在升级所有包"
                        LiveUpdateState.PkgOperation.INSTALL -> "正在安装 ${snap.packageName}"
                        LiveUpdateState.PkgOperation.UNINSTALL -> "正在卸载 ${snap.packageName}"
                    }
                    progressSuccess = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        installedList = PkgRepo.getInstalled(context)
        isLoading = false
    }



    LaunchedEffect(searchQuery, selectedTab) {
        if (searchQuery.isBlank()) {
            if (selectedTab == 1) {
                loadingAvailable = true
                availableList = PkgRepo.getAvailableAll(context)
                loadingAvailable = false
            }
        } else {
            loadingAvailable = true
            delay(300)
            availableList = PkgRepo.searchAvailable(context, searchQuery)
            loadingAvailable = false
        }
    }

    // ====== 返回处理栈（详情 > 分类栈 > 搜索 > 退出） ======
    BackHandler {
        when {
            showDetail != null -> showDetail = null
            viewMode == 0 && navStack.size > 1 -> navStack = navStack.dropLast(1)
            searchQuery.isNotBlank() -> searchQuery = ""
            else -> onBackPressed()
        }
    }

    val topBarTitle = when {
        showDetail != null -> showDetail!!.name
        viewMode == 0 && navStack.size > 1 -> PkgRepo.sectionDisplayName(context, currentSection!!)
        else -> context.getString(R.string.pkg_all_packages)
    }

    AnimatedContent(
        targetState = showDetail,
        transitionSpec: {
            val isEnter = targetState != null && initialState == null
            if (isEnter) {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                (slideOutHorizontally { -it / 3 } + fadeOut()) using SizeTransform(clip = false)
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                (slideOutHorizontally { it } + fadeOut()) using SizeTransform(clip = false)
            }
        },
        label = "pkg_detail_transition"
    ) { detail ->
        if (detail != null) {
            PackageDetailScreen(
                pkg = detail,
                navBarBottomPadding = navBarBottomPadding,
                onBack = { showDetail = null },
                onChanged = { success ->
                    scope.launch {
                        installedList = PkgRepo.getInstalled(context)
                        if (searchQuery.isNotBlank()) {
                            availableList = PkgRepo.searchAvailable(context, searchQuery)
                        }
                    }
                    if (success) {
                        Toast.makeText(context, "操作成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "操作失败，请检查日志", Toast.LENGTH_SHORT).show()
                    }
                    showDetail = null
                }
            )
        } else {
            // ============ 主界面（列表/分类视图）============
            Scaffold(
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                topBar = {
                    TopAppBar(
                        title = topBarTitle,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        // 与 BackHandler 同逻辑
                                        when {
                                            viewMode == 0 && navStack.size > 1 -> navStack = navStack.dropLast(1)
                                            searchQuery.isNotBlank() -> searchQuery = ""
                                            else -> onBackPressed()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.back),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        },
                actions = {
                    // 后台任务恢复按钮 — 有运行中的包操作时显示
                    if (pkgStateSnap != null && !pkgStateSnap!!.finished) {
                        IconButton(
                            onClick = {
                                LiveUpdateState.requestResumePkg()
                                showProgressDialog = true
                                progressSuccess = null
                                progressTitle = when (pkgStateSnap!!.operation) {
                                    LiveUpdateState.PkgOperation.UPDATE -> "正在刷新软件源"
                                    LiveUpdateState.PkgOperation.UPGRADE -> "正在升级所有包"
                                    LiveUpdateState.PkgOperation.INSTALL -> "正在安装 ${pkgStateSnap!!.packageName}"
                                    LiveUpdateState.PkgOperation.UNINSTALL -> "正在卸载 ${pkgStateSnap!!.packageName}"
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_play),
                                contentDescription = stringResource(R.string.resume_background),
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            progressTitle = "正在刷新软件源"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            LiveUpdateState.startPkg(LiveUpdateState.PkgOperation.UPDATE, "", backgrounded = false)
                            LiveUpdateState.pkgScope.launch {
                                val (ok, log) = PkgRepo.update(context, onOutput = { LiveUpdateState.appendPkgLog(it) })
                                LiveUpdateState.finishPkg(ok)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                    if (searchQuery.isBlank()) {
                                        availableList = PkgRepo.getAvailableAll(context)
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.refresh_sources),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            progressTitle = "正在升级所有包"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            LiveUpdateState.startPkg(LiveUpdateState.PkgOperation.UPGRADE, "", backgrounded = false)
                            LiveUpdateState.pkgScope.launch {
                                val (ok, log) = PkgRepo.upgradeAll(context, onOutput = { LiveUpdateState.appendPkgLog(it) })
                                LiveUpdateState.finishPkg(ok)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = stringResource(R.string.upgrade_all_packages),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val searchFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            var searchBarActivated by remember { mutableStateOf(false) }
            SearchBar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                inputField = {
                    InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it; searchBarActivated = true },
                        onSearch = { },
                        expanded = searchBarActivated,
                        onExpandedChange = { searchBarActivated = it },
                        label = "搜索软件包"
                    )
                },
                expanded = searchBarActivated,
                onExpandedChange = { searchBarActivated = it }
            ) { }

            // 计算 tab 和列表
            val isSearching = searchQuery.isNotBlank()
            val isCategoryView = viewMode == 0
            val isCategoryRoot = isCategoryView && currentSection == null && !isSearching

            // tab 在分类根含义变了（筛选"已安装的分类 / 未安装的分类"），其它情况保持原含义
            val tabLabelInstalled = if (isCategoryRoot) "已安装分类" else "已安装 (${installedList.size})"
            val tabLabelAvailable = if (isCategoryRoot) "未安装分类" else "未安装"

            if (!isSearching && !isCategoryRoot) {
                TabRowWithContour(
                    tabs = listOf(tabLabelInstalled, tabLabelAvailable),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // 合并的包池（搜索 / 分类包列表 / 列表视图 都要用）
            val allPackages = installedList + availableList
            val tabPackages = if (selectedTab == 0) installedList else availableList
            val effectiveList: List<PackageInfo> = when {
                isSearching -> {
                    val q = searchQuery.lowercase()
                    val installedMatch = installedList.filter { it.name.lowercase().contains(q) }
                    val availableMatch = availableList.filter { it.name.lowercase().contains(q) }
                    (installedMatch + availableMatch).distinctBy { it.name }
                }
                // 分类包列表层级：该 section 下 + 按已安装/未安装 tab 筛选
                isCategoryView && currentSection != null -> tabPackages.filter {
                    PkgRepo.normalizeSectionKey(it.resolveSection()) == PkgRepo.normalizeSectionKey(currentSection!!)
                }
                // 列表视图 / 分类根 tab 层级：原 tab 逻辑
                else -> tabPackages
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading || loadingAvailable) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFF2563EB)
                    )
                } else if (isCategoryRoot) {
                    // ============ 分类根层级：显示分类网格 ============
                    // 按 section 分组 + 按 tab 筛选成员
                    val filteredForSection = tabPackages
                    val sectionGroups = filteredForSection
                        .groupBy { PkgRepo.normalizeSectionKey(it.resolveSection()) }
                        .mapValues { it.value.size }
                        .entries.sortedByDescending { it.value }

                    val listState2 = rememberLazyListState()
                    LazyColumn(
                        state = listState2,
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 4.dp, bottom = 16.dp
                        )
                    ) {
                        if (sectionGroups.isEmpty()) {
                            item {
                                EmptyStateView(
                                    main = if (selectedTab == 0)
                                        context.getString(R.string.pkg_empty_installed)
                                    else
                                        context.getString(R.string.pkg_empty_available),
                                    hint = if (selectedTab == 0)
                                        context.getString(R.string.pkg_empty_installed_hint)
                                    else
                                        context.getString(R.string.pkg_empty_available_hint),
                                    isDark = isDark,
                                    iconRes = R.drawable.ic_folder
                                )
                            }
                        } else {
                            items(sectionGroups) { (sectionKey, count) ->
                                CategoryEntry(
                                    sectionKey = sectionKey,
                                    count = count,
                                    label = PkgRepo.sectionDisplayName(context, sectionKey),
                                    onClick = {
                                        navStack = navStack + PkgNavLevel(sectionKey = sectionKey, label = sectionKey)
                                    },
                                    isDark = isDark
                                )
                            }
                        }
                    }
                } else {
                    // ============ 包列表层级 ============
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 4.dp, bottom = 16.dp
                        )
                    ) {
                        if (effectiveList.isEmpty()) {
                            item {
                                EmptyStateView(
                                    main = when {
                                        isSearching -> context.getString(R.string.pkg_empty_search)
                                        isCategoryView && currentSection != null ->
                                            context.getString(R.string.pkg_empty_section)
                                        selectedTab == 0 -> context.getString(R.string.pkg_empty_installed)
                                        else -> context.getString(R.string.pkg_empty_available)
                                    },
                                    hint = when {
                                        isSearching -> context.getString(R.string.pkg_empty_search_hint)
                                        isCategoryView && currentSection != null ->
                                            context.getString(R.string.pkg_empty_section_hint)
                                        selectedTab == 0 -> context.getString(R.string.pkg_empty_installed_hint)
                                        else -> context.getString(R.string.pkg_empty_available_hint)
                                    },
                                    isDark = isDark,
                                    iconRes = if (isCategoryView && currentSection != null) R.drawable.ic_folder
                                             else if (isSearching) R.drawable.ic_search
                                             else R.drawable.ic_package
                                )
                            }
                        } else {
                            items(effectiveList) { pkg ->
                                PackageCard(
                                    pkg = pkg,
                                    onClick = { showDetail = pkg }
                                )
                            }
                        }
                    }
                }
            }

            OverlayDialog(
                show = showProgressDialog,
                title = progressTitle.ifBlank { "正在处理" },
                summary = "",
                onDismissRequest = { if (progressSuccess != null) showProgressDialog = false },
                content = {
                    val logScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Loading indicator + "处理中..." 一行居中
                        if (progressSuccess == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MiuixTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.common_processing),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (progressSuccess != null) {
                            Text(
                                text = if (progressSuccess == true) "操作成功" else "操作失败",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (progressSuccess == true) MiuixTheme.colorScheme.primary else Color(0xFFDC2626)
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        // Log area — 加载中和完成后都显示，实时更新
                        val displayLog = if (progressSuccess == null) livePkgLog else progressLog
                        val clippedLog = if (displayLog.length > 5000) displayLog.substring(displayLog.length - 5000) else displayLog
                        if (clippedLog.isNotBlank()) {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .height(200.dp)
                                    .background(
                                        color = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = clippedLog,
                                    fontSize = 12.sp,
                                    color = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.7f),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.verticalScroll(logScrollState)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (progressSuccess != null) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(
                                    text = stringResource(R.string.low_android_force_disable_confirm),
                                    onClick = { showProgressDialog = false },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            )
        }
    }
    )
}

@Composable
private fun EmptyStateView(
    main: String,
    hint: String,
    isDark: Boolean,
    iconRes: Int
) {
    val iconTint = if (isDark) Color.White.copy(alpha = 0.35f)
                   else Color.Black.copy(alpha = 0.35f)
    val mainColor = if (isDark) Color.White.copy(alpha = 0.75f)
                    else Color.Black.copy(alpha = 0.75f)
    val hintColor = if (isDark) Color.White.copy(alpha = 0.45f)
                    else Color.Black.copy(alpha = 0.45f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = main,
                color = mainColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                color = hintColor,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CategoryEntry(
    sectionKey: String,
    label: String,
    count: Int,
    onClick: () -> Unit,
    isDark: Boolean
) {
    val bgColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF5F5F7)
    val titleColor = if (isDark) Color.White else Color.Black
    val subColor = if (isDark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2563EB).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = Color(0xFF2563EB),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$count ${stringResource(R.string.pkg_category_count_suffix)}",
                color = subColor,
                fontSize = 13.sp
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = subColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PackageCard(
    pkg: PackageInfo,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color.Black
    val subColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f)
    val accentColor = Color(0xFF2563EB)
    val grayColor = Color(0xFF6B7280)

    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pkg.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pkg.version.ifBlank { "未知版本" },
                    fontSize = 13.sp,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        color = if (pkg.isInstalled) accentColor.copy(alpha = 0.12f)
                               else grayColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (pkg.isInstalled) "已安装" else "可安装",
                    fontSize = 12.sp,
                    color = if (pkg.isInstalled) accentColor else grayColor
                )
            }
        }
    }
}

}
}
