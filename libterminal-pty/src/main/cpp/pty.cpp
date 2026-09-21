#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>
#include <string>
#include <climits>

#define UNUSED(x) x [[maybe_unused]]

// 抛出 Java 异常的辅助函数
static int throw_runtime_exception(JNIEnv* env, char const* message) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message);
    }
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        const std::vector<std::pair<std::string, std::string>>& env_vars,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height) {

    int ptm = posix_openpt(O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx (posix_openpt failed)");

    char devname[64];
    if (grantpt(ptm)) {
        close(ptm);
        return throw_runtime_exception(env, "grantpt failed");
    }
    if (unlockpt(ptm)) {
        close(ptm);
        return throw_runtime_exception(env, "unlockpt failed");
    }
    if (ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm);
        return throw_runtime_exception(env, "ptsname_r failed");
    }

    // 启用 UTF-8 模式并禁用软件流控
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        if (tcsetattr(ptm, TCSANOW, &tios) != 0) {
            close(ptm);
            return throw_runtime_exception(env, "tcsetattr failed");
        }
    }

    // 设置初始窗口大小
    struct winsize sz = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    if (ioctl(ptm, TIOCSWINSZ, &sz) != 0) {
        close(ptm);
        return throw_runtime_exception(env, "ioctl(TIOCSWINSZ) failed");
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        // 父进程
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // 子进程
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, nullptr);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(127);

        if (dup2(pts, STDIN_FILENO) < 0 ||
            dup2(pts, STDOUT_FILENO) < 0 ||
            dup2(pts, STDERR_FILENO) < 0) {
            const char msg[] = "dup2 failed\n";
            write(STDERR_FILENO, msg, sizeof(msg) - 1);
            _exit(127);
        }
        close(pts);

        struct rlimit rl;
        int max_fd = 1024;
        if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur < INT_MAX) {
            max_fd = (int) rl.rlim_cur;
        }
        for (int fd = STDERR_FILENO + 1; fd < max_fd; fd++) {
            close(fd);
        }

        for (const auto& var : env_vars) {
            setenv(var.first.c_str(), var.second.c_str(), 1);
        }
        setenv("PWD", cwd, 1);

        if (chdir(cwd) != 0) {
            dprintf(STDERR_FILENO, "chdir(\"%s\") failed\n", cwd);
        }

        execvp(cmd, argv);

        dprintf(STDERR_FILENO, "exec(\"%s\") failed\n", cmd);
        _exit(127);
    }
}

extern "C" {

JNIEXPORT jint JNICALL Java_com_termux_app_compose_terminal_pty_PtyFactory_createSubprocess(
        JNIEnv* env,
        jclass UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height) {

    std::vector<std::string> argv_strings;
    std::vector<char*> argv;
    std::vector<std::pair<std::string, std::string>> env_vars;

    // 处理 args
    jsize args_size = args ? env->GetArrayLength(args) : 0;
    if (args_size < 0 || args_size > 65535) {
        throw_runtime_exception(env, "Invalid args array size");
        return -1;
    }

    if (args_size > 0) {
        argv_strings.reserve(args_size);
        argv.reserve(args_size + 1);
        for (int i = 0; i < args_size; ++i) {
            jstring arg_java_string = (jstring) env->GetObjectArrayElement(args, i);
            if (!arg_java_string) {
                throw_runtime_exception(env, "Null argument string detected");
                return -1;
            }

            char const* arg_utf8 = env->GetStringUTFChars(arg_java_string, nullptr);
            if (!arg_utf8) {
                env->DeleteLocalRef(arg_java_string);
                throw_runtime_exception(env, "GetStringUTFChars failed for argv");
                return -1;
            }

            argv_strings.emplace_back(arg_utf8);
            env->ReleaseStringUTFChars(arg_java_string, arg_utf8);
            env->DeleteLocalRef(arg_java_string);
        }
        for (auto& s : argv_strings) {
            argv.push_back(const_cast<char*>(s.c_str()));
        }
    }
    argv.push_back(nullptr); // execvp 必须以 nullptr 结尾

    // 处理 envVars
    jsize env_size = envVars ? env->GetArrayLength(envVars) : 0;
    if (env_size < 0 || env_size > 65535) {
        throw_runtime_exception(env, "Invalid env array size");
        return -1;
    }

    if (env_size > 0) {
        env_vars.reserve(env_size);
        for (int i = 0; i < env_size; ++i) {
            jobjectArray innerArray = (jobjectArray) env->GetObjectArrayElement(envVars, i);
            if (!innerArray) {
                throw_runtime_exception(env, "env element is null");
                return -1;
            }

            if (env->GetArrayLength(innerArray) < 2) {
                env->DeleteLocalRef(innerArray);
                throw_runtime_exception(env, "env element length must be at least 2");
                return -1;
            }

            jstring env_key_java_string = (jstring) env->GetObjectArrayElement(innerArray, 0);
            jstring env_value_java_string = (jstring) env->GetObjectArrayElement(innerArray, 1);

            if (!env_key_java_string || !env_value_java_string) {
                if (env_key_java_string) env->DeleteLocalRef(env_key_java_string);
                if (env_value_java_string) env->DeleteLocalRef(env_value_java_string);
                env->DeleteLocalRef(innerArray);
                throw_runtime_exception(env, "env key or value string is null");
                return -1;
            }

            char const* env_key_utf8 = env->GetStringUTFChars(env_key_java_string, nullptr);
            char const* env_value_utf8 = env->GetStringUTFChars(env_value_java_string, nullptr);

            if (!env_key_utf8 || !env_value_utf8) {
                if (env_key_utf8) env->ReleaseStringUTFChars(env_key_java_string, env_key_utf8);
                if (env_value_utf8) env->ReleaseStringUTFChars(env_value_java_string, env_value_utf8);
                env->DeleteLocalRef(env_key_java_string);
                env->DeleteLocalRef(env_value_java_string);
                env->DeleteLocalRef(innerArray);
                throw_runtime_exception(env, "GetStringUTFChars failed for env");
                return -1;
            }

            env_vars.emplace_back(env_key_utf8, env_value_utf8);

            env->ReleaseStringUTFChars(env_key_java_string, env_key_utf8);
            env->ReleaseStringUTFChars(env_value_java_string, env_value_utf8);
            env->DeleteLocalRef(env_key_java_string);
            env->DeleteLocalRef(env_value_java_string);
            env->DeleteLocalRef(innerArray);
        }
    }

    char const* cmd_utf8 = env->GetStringUTFChars(cmd, nullptr);
    char const* cwd_utf8 = env->GetStringUTFChars(cwd, nullptr);

    if (!cmd_utf8 || !cwd_utf8) {
        if (cmd_utf8) env->ReleaseStringUTFChars(cmd, cmd_utf8);
        if (cwd_utf8) env->ReleaseStringUTFChars(cwd, cwd_utf8);
        throw_runtime_exception(env, "GetStringUTFChars failed for cmd/cwd");
        return -1;
    }

    int procId = 0;
    int ptm = create_subprocess(
        env,
        cmd_utf8,
        cwd_utf8,
        argv.data(),
        env_vars,
        &procId,
        rows,
        columns,
        cell_width,
        cell_height
    );

    env->ReleaseStringUTFChars(cmd, cmd_utf8);
    env->ReleaseStringUTFChars(cwd, cwd_utf8);

    if (ptm >= 0) {
        int* pProcId = (int*) env->GetPrimitiveArrayCritical(processIdArray, nullptr);
        if (pProcId) {
            *pProcId = procId;
            env->ReleasePrimitiveArrayCritical(processIdArray, pProcId, 0);
        } else {
            throw_runtime_exception(env, "GetPrimitiveArrayCritical failed");
            close(ptm);
            ptm = -1;
        }
    }

    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_app_compose_terminal_pty_PtyFactory_setPtyWindowSize(
        JNIEnv* UNUSED(env),
        jclass UNUSED(clazz),
        jint fd,
        jint rows,
        jint cols,
        jint cell_width,
        jint cell_height) {
    struct winsize sz = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) cols,
        .ws_xpixel = (unsigned short) (cols * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT jint JNICALL Java_com_termux_app_compose_terminal_pty_PtyFactory_waitFor(
        JNIEnv* UNUSED(env),
        jclass UNUSED(clazz),
        jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    }
    return 0;
}

} // extern "C"

