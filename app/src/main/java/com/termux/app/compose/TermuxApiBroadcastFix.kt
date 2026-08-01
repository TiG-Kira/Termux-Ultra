package com.termux.app.compose

import android.content.Context
import com.termux.shared.logger.Logger
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Fixes the "termux-api commands hang" problem on Android 14+ (API 34+).
 *
 * Root cause (confirmed from termux-api-package/termux-api.c line 251):
 *   execv(PREFIX "/bin/am", child_argv);
 * The C client binary calls $PREFIX/bin/am via ABSOLUTE PATH (not PATH lookup),
 * with a hard-coded component "com.termux.api/.TermuxApiReceiver" (line 236).
 *
 * In integrated mode our package is "com.termux", so the real receiver is
 * "com.termux/com.termux.api.TermuxApiReceiver".  The broadcast silently fails,
 * ResultReturner never runs, and the termux-* command hangs forever.
 *
 * Fix: replace $PREFIX/bin/am itself with a wrapper script that rewrites the
 * component name for termux-api broadcasts.  The original am is backed up as
 * am.real and all non-termux-api calls are passed through unchanged.
 *
 * Why PATH hook didn't work: the C client uses execv() with an absolute path,
 * so it never searches PATH.  Replacing $PREFIX/bin/am directly is the only
 * reliable interception point.
 *
 * ---- Lessons learned about Permission denied ----
 * 1. SHEBANG MUST be line 1 (byte 0).  If the marker comment is on line 1 and
 *    the shebang on line 2, execv() sees neither #! nor ELF magic and returns
 *    Permission denied / Exec format error.
 * 2. `mv file new` + creating a new file loses SELinux context.  Instead use
 *    `cp -p file am.real` to preserve ALL attributes (mode/uid/gid/mtime/SELinux
 *    context) and then `> file` to TRUNCATE and rewrite the SAME inode.  This
 *    keeps the original context so execv() is allowed by SELinux.
 * 3. As a last resort, try `restorecon -RF` on the two paths; some Xiaomi /
 *    heavily customised ROMs relabel app-private files after the app starts.
 */
object TermuxApiBroadcastFix {

    private const val LOG_TAG = "TermuxApiBroadcastFix"

    /** Path to the real am binary/script in the Termux prefix. */
    private const val AM_BIN = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/am"

    /** Backup of the original am, created when we install the wrapper. */
    private const val AM_REAL = AM_BIN + ".real"

    /**
     * Marker placed on line 2 of the wrapper (line 1 is the mandatory shebang).
     * isOurWrapper() checks lines 1..4 so an exact line-number match is not needed.
     */
    private const val HOOK_MARKER = "# TERMUX-ULTRA termux-api broadcast fix v3"

    /** Literal dollar character (Unicode escape avoids Kotlin string-template). */
    private const val DOL = "\u0024"

    // ------------------------------------------------------------------
    // Wrapper script body
    //
    // CRITICAL: SHEBANG MUST BE THE VERY FIRST LINE.  The POSIX exec*() family
    // only accepts interpreter directives at byte offset 0.
    // ------------------------------------------------------------------
    private val AM_WRAPPER_BODY: String = buildString {
        val d = DOL
        // Line 1: shebang (must be line 1 for exec*() to treat this as a script)
        appendLine("#!/data/data/com.termux/files/usr/bin/sh")
        // Line 2: marker (so isOurWrapper recognises the file)
        appendLine(HOOK_MARKER)
        appendLine("# Wrapper installed by TermuxApiBroadcastFix.")
        appendLine("# Rewrites com.termux.api/.TermuxApiReceiver -> com.termux/com.termux.api.TermuxApiReceiver")
        appendLine("# for termux-api broadcasts in integrated mode.  All other calls pass through.")
        appendLine("")
        // Build safely-quoted argument list, rewriting the component when found.
        appendLine("next_is_component=0")
        appendLine("args=" + '"' + '"')
        appendLine("for a in " + '"' + d + "@" + '"' + "; do")
        appendLine("    if [ " + '"' + d + "next_is_component" + '"' + " = 1 ]; then")
        appendLine("        next_is_component=0")
        appendLine("        case " + '"' + d + "a" + '"' + " in")
        appendLine("            com.termux.api/.TermuxApiReceiver)")
        appendLine("                a=com.termux/com.termux.api.TermuxApiReceiver ;;")
        appendLine("        esac")
        appendLine("    fi")
        appendLine("    case " + '"' + d + "a" + '"' + " in")
        appendLine("        -n) next_is_component=1 ;;")
        appendLine("    esac")
        // Escape single quotes for safe shell re-assembly: ' -> '\''
        appendLine("    esc=" + d + "(printf " + "'" + "%s" + "'" + " " + '"' + d + "a" + '"' + " | sed " + '"' + "s/" + "'" + "/" + "'" + "\\\\" + "'" + "'" + "/g" + '"' + ")")
        appendLine("    args=" + '"' + d + "args " + "'" + d + "esc" + "'" + '"')
        appendLine("done")
        appendLine("")
        // Exec the real am with the (possibly rewritten) arguments.
        appendLine("eval exec " + '"' + d + "(dirname " + '"' + d + "0" + '"' + ")" + "/am.real" + '"' + " " + d + "args")
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------
    /**
     * Install the am wrapper.  The entire install flow (backup via `cp -p`,
     * overwrite of existing inode via `>`, chmod, restorecon) is performed
     * inside a single `/system/bin/sh -c ...` invocation.  A detailed diagnostic
     * block (`ls -lZ`, `sh -n`, `test -x`) is printed at the end so logcat can
     * reveal exactly why Permission denied still happens on specific ROMs.
     */
    @JvmStatic
    fun applyAmWrapper(context: Context) {
        try {
            val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
            if (!prefix.isDirectory) {
                Logger.logWarn(LOG_TAG, "Prefix not yet installed; skipping am wrapper install")
                return
            }
            val amBin = File(AM_BIN)
            if (!amBin.exists()) {
                Logger.logWarn(LOG_TAG, "$PREFIX/bin/am does not exist; nothing to wrap")
                return
            }
            if (isOurWrapper(amBin)) {
                // Idempotent: if it already IS our wrapper there is nothing to do.
                Logger.logInfo(LOG_TAG, "am wrapper already in place")
                return
            }

            Logger.logInfo(LOG_TAG, "---- Installing am wrapper (v3) ----")
            logFileInfo("BEFORE-install", AM_BIN)
            logFileInfo("BEFORE-install", AM_REAL)

            val script = buildInstallShellScript()
            val exit = runShellScriptAndLog(script)

            logFileInfo("AFTER-install", AM_BIN)
            logFileInfo("AFTER-install", AM_REAL)

            // Re-check after install so user gets confirmation.
            if (isOurWrapper(amBin)) {
                Logger.logInfo(LOG_TAG, "am wrapper installed OK at $AM_BIN (exit=$exit)")
            } else {
                Logger.logError(LOG_TAG, "Install script ran (exit=$exit) but wrapper marker not found at $AM_BIN; look at 'sh>' logcat lines above for diagnostic output")
            }
        } catch (t: Throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply am wrapper", t)
        }
    }

    @JvmStatic
    fun removeAmWrapper() {
        try {
            val sb = StringBuilder()
            // If we still have a backup, remove our wrapper and restore the original.
            sb.appendLine("if [ -f \"$AM_REAL\" ]; then")
            sb.appendLine("    echo '[remove] restoring am.real -> am'")
            sb.appendLine("    rm -f \"$AM_BIN\"")
            sb.appendLine("    cp -p \"$AM_REAL\" \"$AM_BIN\"")
            sb.appendLine("    chmod 0755 \"$AM_BIN\" || true")
            sb.appendLine("    (restorecon -RF \"$AM_BIN\" 2>/dev/null || /system/bin/toolbox restorecon -RF \"$AM_BIN\" 2>/dev/null || true)")
            sb.appendLine("    rm -f \"$AM_REAL\"")
            sb.appendLine("else")
            // No backup.  If current file has our marker, remove it; user can reinstall pkg.
            sb.appendLine("    if grep -qF \"$HOOK_MARKER\" \"$AM_BIN\" 2>/dev/null; then")
            sb.appendLine("        echo '[remove] no backup found but marker present; deleting wrapper file'")
            sb.appendLine("        rm -f \"$AM_BIN\"")
            sb.appendLine("    fi")
            sb.appendLine("fi")
            sb.appendLine("echo '[remove] ls -lZ result:'; ls -lZ \"$AM_BIN\" 2>&1 || true")
            runShellScriptAndLog(sb.toString())
            Logger.logInfo(LOG_TAG, "am wrapper removed / original restored")
        } catch (t: Throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to remove am wrapper", t)
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Check if the wrapper marker string appears anywhere in the first 4 lines.
     * Because the shebang is now line 1 the marker is on line 2, but we check
     * the head of the file so version upgrades / manual edits stay detected.
     */
    private fun isOurWrapper(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            file.readText(Charsets.UTF_8)
                .lineSequence()
                .take(4)
                .any { it.trim() == HOOK_MARKER }
        } catch (t: Throwable) {
            false
        }
    }

    /** Log `ls -lZ` (mode/uid/gid/SELinux label) + `head -n2` + executable flag for a path. */
    private fun logFileInfo(tag: String, path: String) {
        runShellScriptAndLog(
            "echo '=== $tag: $path ===';" +
            " ls -lZ \"$path\" 2>&1 || echo '(not present)';" +
            " if [ -f \"$path\" ]; then" +
            "   if [ -x \"$path\" ]; then echo 'executable: YES'; else echo 'executable: NO'; fi;" +
            "   (sh -n \"$path\" 2>&1 && echo 'shell-syntax-check: PASS') || echo 'shell-syntax-check: FAIL (expected for ELF/non-shell am)';" +
            "   head -n 4 \"$path\" 2>&1 | sed 's/^/head: /';" +
            " fi"
        )
    }

    /**
     * Build the POSIX-shell installer script that is executed via /system/bin/sh.
     *
     * Key differences vs the v2 script that caused "Permission denied":
     *   • Backup is done with `cp -p` (preserves ALL attributes incl. SELinux label)
     *     instead of `mv`, so the destination `am.real` immediately has the right
     *     permissions and label to be exec'd.
     *   • The wrapper is written by TRUNCATING the existing $AM_BIN (`> $AM_BIN`)
     *     then writing the new content into the SAME inode.  This means the
     *     SELinux label, ownership, and permissions on the file *itself* never
     *     change — only the bytes do — so execv() permission checks pass.
     *   • chmod 0755 + `restorecon -RF` are re-applied defensively at the end.
     *   • Finally, a full diagnostic block prints `ls -lZ`, `-x` flag test and
     *     `sh -n` syntax check so any future Permission denied has direct evidence.
     */
    private fun buildInstallShellScript(): String {
        val sb = StringBuilder()
        // 0) Helpful header so logcat lines are easy to correlate.
        sb.appendLine("echo '[install] begin; running as:'; id 2>&1 || true")
        // 1) Legacy v1 cleanup
        sb.appendLine("rm -rf \"$PREFIX/bin/.termux-ultra\"")
        sb.appendLine("rm -f \"${TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH}/profile.d/termux-ultra-am.sh\"")
        // 2) If it already has our marker, nothing to do.
        sb.appendLine("if grep -qF \"$HOOK_MARKER\" \"$AM_BIN\" 2>/dev/null; then")
        sb.appendLine("    echo '[install] marker already present, skipping'")
        sb.appendLine("    exit 0")
        sb.appendLine("fi")
        // 3) Backup original am -> am.real via `cp -p` to preserve ALL attributes
        //    (mode/uid/gid/mtime/acls/SELinux).  Only if am.real does not already exist.
        sb.appendLine("if [ -f \"$AM_BIN\" ] && [ ! -f \"$AM_REAL\" ]; then")
        sb.appendLine("    echo '[install] copying (cp -p) am -> am.real to preserve attrs'")
        sb.appendLine("    cp -p \"$AM_BIN\" \"$AM_REAL\" || exit 1")
        sb.appendLine("    chmod 0755 \"$AM_REAL\" || true")
        sb.appendLine("fi")
        // 4) Write wrapper into the EXISTING AM_BIN inode.  Truncate first so any
        //    shell reading cached pages sees the new length, then cat our body in.
        //    Using a quoted heredoc means NO shell expansion of the wrapper body.
        sb.appendLine("echo '[install] writing wrapper body into existing inode of am'")
        sb.appendLine(": > \"$AM_BIN\"")
        sb.appendLine("cat >> \"$AM_BIN\" <<'__TERMUX_ULTRA_AM_WRAPPER_EOF__'")
        sb.append(AM_WRAPPER_BODY)
        if (!AM_WRAPPER_BODY.endsWith("\n")) sb.append('\n')
        sb.appendLine("__TERMUX_ULTRA_AM_WRAPPER_EOF__")
        // 5) Re-apply permissions defensively (should not be needed because the
        //    inode already had the original permissions, but better safe).
        sb.appendLine("chmod 0755 \"$AM_BIN\" || true")
        sb.appendLine("if [ -f \"$AM_REAL\" ]; then chmod 0755 \"$AM_REAL\" || true; fi")
        // 6) Restore SELinux context — catch-all for heavily-customised ROMs that
        //    relabel app directories on boot.  Try both toybox and Android toolbox paths.
        sb.appendLine("(restorecon -RF \"$AM_BIN\" \"$AM_REAL\" 2>/dev/null || /system/bin/toolbox restorecon -RF \"$AM_BIN\" \"$AM_REAL\" 2>/dev/null || echo '[install] restorecon not available on this device')")
        // 7) Diagnostic block (shown in logcat via `sh>` lines).
        sb.appendLine("echo '=== INSTALL DIAGNOSTIC BLOCK ==='")
        sb.appendLine("echo '[diag] ls -lZ files:'; ls -lZ \"$AM_BIN\" \"$AM_REAL\" 2>&1 || true")
        sb.appendLine("echo \"[diag] AM_BIN executable: \$([ -x \"$AM_BIN\" ] && echo YES || echo NO)\"")
        sb.appendLine("echo \"[diag] AM_REAL executable: \$([ -x \"$AM_REAL\" ] && echo YES || echo NO)\"")
        sb.appendLine("(sh -n \"$AM_BIN\" 2>&1 && echo '[diag] wrapper syntax check: PASS') || echo '[diag] wrapper syntax check: FAIL'")
        sb.appendLine("echo '[diag] wrapper head -5:'; head -n 5 \"$AM_BIN\" 2>&1 | sed 's/^/diag-head: /'")
        // 8) Smoke test: exec the wrapper with `--help`.  If this returns
        //    Permission denied we will see the error right there in the log.
        sb.appendLine("echo '[diag] smoke-testing exec of wrapper with --help (may be short output):'")
        sb.appendLine("( \"$AM_BIN\" --help 2>&1 || echo \"[diag] wrapper --help exit code=\$?\" ) | head -n 3")
        sb.appendLine("echo '=== END INSTALL DIAGNOSTIC BLOCK ==='")
        return sb.toString()
    }

    /**
     * Execute a POSIX-shell script through the Android system `/system/bin/sh`
     * (not Termux sh, because the whole wrapper might be installed before the
     * user has even bootstrapped the prefix).  Returns the exit code.
     * Every line the shell writes to stdout/stderr is prefixed with `sh>` and
     * forwarded to Logger so Permission denied / restorecon / syntax errors
     * are immediately visible in logcat.
     */
    private fun runShellScriptAndLog(script: String): Int {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", script)
                .directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
                .redirectErrorStream(true)
            val env = pb.environment()
            if (!env.containsKey("HOME")) env["HOME"] = TermuxConstants.TERMUX_HOME_DIR_PATH
            val proc = pb.start()
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Logger.logDebug(LOG_TAG, "sh> $line")
                }
            }
            val exit = proc.waitFor()
            proc.destroy()
            if (exit != 0) Logger.logWarn(LOG_TAG, "sh script exited with code $exit")
            exit
        } catch (t: Throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run sh script", t)
            -1
        }
    }

    private const val PREFIX = TermuxConstants.TERMUX_PREFIX_DIR_PATH
}