/*
 * Native crash handler for Termux Ultra.
 *
 * Intercepts fatal signals (SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL), writes
 * crash info (signal, fault address, timestamp, /proc/self/maps) to crash_log.md
 * (async-signal-safe), then does a best-effort JNI callback to ask Java to show
 * an unrecoverable dialog.
 *
 * Java UncaughtExceptionHandler already wrote a full Java stacktrace to crash_log.md
 * BEFORE the native signal handler ran (Java Exception → logCrash → native handler
 * only supplements it). If JNI fails, crash_log.md stays on disk so CrashUtils
 * will show "侦测到已发生错误" on next app launch.
 *
 * All code in the signal handler must be async-signal-safe. We use only:
 *   open, write, close, read, fstat, time, gmtime_r, strftime, snprintf, vsnprintf,
 *   raise, sigaction, memset, strlen, memmove, strchr.
 *
 * JNI calls happen AFTER the async-signal-safe file write — if they deadlock
 * or crash, the crash info is safe on disk.
 */

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

/* Must match TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH in Java */
#define CRASH_LOG_PATH "/data/data/com.termux.ultra/files/home/crash_log.md"

/* JavaVM pointer saved in JNI_OnLoad for later AttachCurrentThread */
static JavaVM *g_vm = NULL;

/* Saved original signal dispositions (kept for reference only) */
static struct sigaction g_old_segv;
static struct sigaction g_old_abrt;
static struct sigaction g_old_bus;
static struct sigaction g_old_fpe;
static struct sigaction g_old_ill;

/* --- async-signal-safe helpers --- */

static void write_all(int fd, const char *buf, size_t len)
{
    while (len > 0) {
        ssize_t n = write(fd, buf, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return;
        }
        buf += n;
        len -= (size_t)n;
    }
}

static void write_str(int fd, const char *s)
{
    if (s) write_all(fd, s, strlen(s));
}

static void write_timestamp(int fd)
{
    time_t now = time(NULL);
    struct tm tm;
    gmtime_r(&now, &tm);
    char tbuf[64];
    strftime(tbuf, sizeof(tbuf), "%Y-%m-%d %H:%M:%S UTC", &tm);
    write_str(fd, tbuf);
}

static const char *signal_name(int sig)
{
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        default:      return "UNKNOWN";
    }
}

/* Write crash info to crash_log.md. Async-signal-safe. */
static void write_crash_log(int sig, siginfo_t *si)
{
    int already_written = 0;
    struct stat st;
    if (stat(CRASH_LOG_PATH, &st) == 0 && st.st_size > 0) {
        already_written = 1;
    }

    int flags = already_written ? (O_WRONLY | O_APPEND)
                                : (O_WRONLY | O_CREAT | O_TRUNC);
    int fd = open(CRASH_LOG_PATH, flags, 0644);
    if (fd < 0) return;

    if (already_written) {
        /* Java exception already wrote its report — add visual separator + native info */
        write_str(fd, "\n\n---\n\n## Native Signal\n\n");
        write_str(fd, "**Signal**: `");
        write_str(fd, signal_name(sig));
        write_str(fd, "`\n\n");
    } else {
        /* crash_log.md is empty — this is a native crash with no Java exception before it */
        write_str(fd, "## Native Crash\n\n");
        write_str(fd, "**Signal**: `");
        write_str(fd, signal_name(sig));
        write_str(fd, "`\n\n");
    }

    write_str(fd, "**Timestamp**: `");
    write_timestamp(fd);
    write_str(fd, "`\n\n");

    /* Hardware fault address for SIGSEGV/SIGBUS/SIGILL/SIGFPE */
    if (si && (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL || sig == SIGFPE)) {
        write_str(fd, "**Fault Address**: `0x");
        char hexbuf[32];
        int hlen = snprintf(hexbuf, sizeof(hexbuf), "%016lx",
                            (unsigned long)(uintptr_t)si->si_addr);
        if (hlen > 0) write_all(fd, hexbuf, (size_t)hlen);
        write_str(fd, "`\n\n");
    }

    /* Dump first 30 lines of /proc/self/maps for module context */
    int mfd = open("/proc/self/maps", O_RDONLY);
    if (mfd >= 0) {
        write_str(fd, "### Process Maps (first 30 lines)\n\n");
        char chunk[2048];
        char line_buf[512];
        int line_count = 0;
        int buf_pos = 0;

        while (line_count < 30) {
            ssize_t n = read(mfd, chunk, sizeof(chunk));
            if (n <= 0) break;

            for (ssize_t i = 0; i < n && line_count < 30; i++) {
                char c = chunk[i];
                if (c == '\n') {
                    line_buf[buf_pos] = '\0';
                    write_str(fd, "- ");
                    write_str(fd, line_buf);
                    write_str(fd, "\n");
                    line_count++;
                    buf_pos = 0;
                } else if (buf_pos < (int)sizeof(line_buf) - 1) {
                    line_buf[buf_pos++] = c;
                }
            }
        }
        close(mfd);
    }

    close(fd);
}

/* Best-effort JNI callback to ask Java layer to show unrecoverable dialog.
 * MAY RETURN -1 if the VM state is broken — caller handles failure gracefully.
 * NOT async-signal-safe; only called AFTER write_crash_log has succeeded. */
static int try_jni_show_dialog(int sig)
{
    if (!g_vm) return -1;

    JNIEnv *env = NULL;
    int attached = 0;

    int ret = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return -1;
        }
        attached = 1;
    } else if (ret != JNI_OK || !env) {
        return -1;
    }

    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);

    jclass helper_cls = (*env)->FindClass(env, "com/termux/app/utils/NativeCrashBridge");
    if (!helper_cls || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        goto cleanup;
    }

    jmethodID mid = (*env)->GetStaticMethodID(env, helper_cls, "nativeCrashDetected", "(I)V");
    if (!mid || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        goto cleanup;
    }

    (*env)->CallStaticVoidMethod(env, helper_cls, mid, (jint)sig);

cleanup:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return 0;
}

/* The actual signal handler. Stack may already be smashed — keep it tight. */
static void native_crash_handler(int sig, siginfo_t *si, void *ctx)
{
    (void)ctx;

    /* Step 1: Write crash_log.md FIRST — async-signal-safe persistence guarantee. */
    write_crash_log(sig, si);

    /* Step 2: Best-effort JNI callback. May deadlock/crash but crash_log.md
     * is safe on disk so next launch will show TYPE_CRASH_POST if JNI failed. */
    try_jni_show_dialog(sig);

    /* Step 3: Restore default handler and re-raise so system tombstone is generated. */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, NULL);
    raise(sig);
}

/* --- JNI entry points --- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_nativeSetupCrashHandler(
        JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;

    static int s_registered = 0;
    if (s_registered) return;
    s_registered = 1;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = native_crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND | SA_NODEFER;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, &g_old_segv);
    sigaction(SIGABRT, &sa, &g_old_abrt);
    sigaction(SIGBUS,  &sa, &g_old_bus);
    sigaction(SIGFPE,  &sa, &g_old_fpe);
    sigaction(SIGILL,  &sa, &g_old_ill);
}
