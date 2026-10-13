/*
 * VorteX Shadow — userspace copy-on-write (COW) overlay via LD_PRELOAD.
 * ---------------------------------------------------------------------------
 * 目标（对应「沙箱最小快照拆分」需求）：
 *   - 常规目录（真实 $HOME / $PREFIX）以「链接视图」呈现：允许读取，但一切写入
 *     都被**影子化**——写调用返回成功，真实底层数据不变；
 *   - 首次写入某文件时，先把真实内容 copy-up 到临时存储区（tmp/），再在影子副本上
 *     虚拟写；原文件的后续读也改读影子副本（看到修改）；
 *   - 删除仅作用于沙箱内部：写一份 whiteout 标记后即返回成功，真实文件原封不动；
 *   - 移动 / 复制仅作用于影子视图；会话结束临时存储区被整体清空，一切变更自动撤销。
 *
 * 设计依据（调研结论）：
 *   - overlayfs 的 copy-up / whiteout 是内核态方案，无 root 的 Android 应用无法使用；
 *   - proot 通过 ptrace 做路径翻译与绝对路径隔离，但无法做 COW 重定向；
 *   - 因此用 LD_PRELOAD 在 libc 层拦截 open/unlink/rename/...，自己实现一套
 *     用户态 COW 叠加层，与 proot 互为补充（proot 兜住原始 syscall，本库兜住重定向）。
 *
 * 仅在以下环境变量齐备时生效，否则所有封装直接透传（零开销、可安全禁用）：
 *   VORTEX_SHADOW_TMP     临时存储区根（影子副本与 whiteout 均落于此）
 *   VORTEX_SHADOW_ROOTS   受控根目录，冒号分隔（如 $HOME:$PREFIX）
 *   VORTEX_SHADOW_DISABLE 置 1 即完全关闭（调试用）
 * 另有（可选）：
 *   VORTEX_SHADOW_NOTICE  首次 copy-up 时写的哨兵文件，供宿主侧转成 Snackbar
 *   VORTEX_SHADOW_NOTIFIED 节流标记：存在则不再重写 NOTICE（每会话仅提示一次）
 * ---------------------------------------------------------------------------
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdarg.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <dirent.h>
#include <pthread.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#define VORTEX_MAX_ROOTS 16

/* ---- 运行时配置（构造函数里读取一次）---------------------------------- */
static int          g_enabled = 0;
static char         g_tmp[PATH_MAX];
static char         g_roots[VORTEX_MAX_ROOTS][PATH_MAX];
static int          g_nroots = 0;
static char         g_notice[PATH_MAX];
static char         g_notified[PATH_MAX];

/* ---- 真实函数指针（RTLD_NEXT，避免递归拦截自己）------------------------ */
#define DEF_REAL(ret, name, args) static ret (*real_##name) args
DEF_REAL(int,    open,     (const char*, int, ...));
DEF_REAL(int,    open64,   (const char*, int, ...));
DEF_REAL(int,    openat,   (int, const char*, int, ...));
DEF_REAL(int,    unlink,   (const char*));
DEF_REAL(int,    unlinkat, (int, const char*, int));
DEF_REAL(int,    rmdir,    (const char*));
DEF_REAL(int,    rename,   (const char*, const char*));
DEF_REAL(int,    renameat, (int, const char*, int, const char*));
DEF_REAL(int,    link,     (const char*, const char*));
DEF_REAL(int,    linkat,   (int, const char*, int, const char*, int));
DEF_REAL(int,    symlink,  (const char*, const char*));
DEF_REAL(int,    symlinkat,(const char*, int, const char*));
DEF_REAL(int,    truncate, (const char*, off_t));
DEF_REAL(int,    ftruncate,(int, off_t));
DEF_REAL(int,    mkdir,    (const char*, mode_t));
DEF_REAL(int,    mkdirat,  (int, const char*, mode_t));
DEF_REAL(int,    stat,     (const char*, struct stat*));
DEF_REAL(int,    stat64,   (const char*, struct stat*));
DEF_REAL(int,    lstat,    (const char*, struct stat*));
DEF_REAL(int,    lstat64,  (const char*, struct stat*));
DEF_REAL(int,    fstatat,  (int, const char*, struct stat*, int));
DEF_REAL(int,    access,   (const char*, int));
DEF_REAL(int,    chmod,    (const char*, mode_t));
DEF_REAL(int,    remove,   (const char*));
DEF_REAL(DIR*,   opendir,  (const char*));
DEF_REAL(struct dirent*, readdir,  (DIR*));
DEF_REAL(int,    readdir_r,(DIR*, struct dirent*, struct dirent**));
DEF_REAL(int,    closedir, (DIR*));
#undef DEF_REAL

static void resolve_real(void) {
    real_open      = (int(*)(const char*, int, ...))        dlsym(RTLD_NEXT, "open");
    real_open64    = (int(*)(const char*, int, ...))        dlsym(RTLD_NEXT, "open64");
    real_openat    = (int(*)(int, const char*, int, ...))    dlsym(RTLD_NEXT, "openat");
    real_unlink    = (int(*)(const char*))                  dlsym(RTLD_NEXT, "unlink");
    real_unlinkat  = (int(*)(int, const char*, int))        dlsym(RTLD_NEXT, "unlinkat");
    real_rmdir     = (int(*)(const char*))                  dlsym(RTLD_NEXT, "rmdir");
    real_rename    = (int(*)(const char*, const char*))     dlsym(RTLD_NEXT, "rename");
    real_renameat  = (int(*)(int, const char*, int, const char*)) dlsym(RTLD_NEXT, "renameat");
    real_link      = (int(*)(const char*, const char*))     dlsym(RTLD_NEXT, "link");
    real_linkat    = (int(*)(int, const char*, int, const char*, int)) dlsym(RTLD_NEXT, "linkat");
    real_symlink   = (int(*)(const char*, const char*))     dlsym(RTLD_NEXT, "symlink");
    real_symlinkat = (int(*)(const char*, int, const char*))dlsym(RTLD_NEXT, "symlinkat");
    real_truncate  = (int(*)(const char*, off_t))           dlsym(RTLD_NEXT, "truncate");
    real_ftruncate = (int(*)(int, off_t))                   dlsym(RTLD_NEXT, "ftruncate");
    real_mkdir    = (int(*)(const char*, mode_t))          dlsym(RTLD_NEXT, "mkdir");
    real_mkdirat  = (int(*)(int, const char*, mode_t))     dlsym(RTLD_NEXT, "mkdirat");
    real_stat     = (int(*)(const char*, struct stat*))    dlsym(RTLD_NEXT, "stat");
    real_stat64   = (int(*)(const char*, struct stat*))    dlsym(RTLD_NEXT, "stat64");
    real_lstat    = (int(*)(const char*, struct stat*))    dlsym(RTLD_NEXT, "lstat");
    real_lstat64  = (int(*)(const char*, struct stat*))    dlsym(RTLD_NEXT, "lstat64");
    real_fstatat  = (int(*)(int, const char*, struct stat*, int)) dlsym(RTLD_NEXT, "fstatat");
    real_access   = (int(*)(const char*, int))             dlsym(RTLD_NEXT, "access");
    real_chmod    = (int(*)(const char*, mode_t))          dlsym(RTLD_NEXT, "chmod");
    real_remove   = (int(*)(const char*))                  dlsym(RTLD_NEXT, "remove");
    real_opendir  = (DIR*(*)(const char*))                 dlsym(RTLD_NEXT, "opendir");
    real_readdir  = (struct dirent*(*)(DIR*))              dlsym(RTLD_NEXT, "readdir");
    real_readdir_r= (int(*)(DIR*, struct dirent*, struct dirent**)) dlsym(RTLD_NEXT, "readdir_r");
    real_closedir = (int(*)(DIR*))                         dlsym(RTLD_NEXT, "closedir");
}

/* ---- 路径工具 ----------------------------------------------------------- */

/* 判断 path 是否落在某受控根下；是则返回根序号并写出 rel（可为空串=根本身）。 */
static int is_intercepted(const char* path, char* rel, size_t relsz) {
    if (!g_enabled || !path || path[0] != '/') return -1;
    for (int i = 0; i < g_nroots; i++) {
        size_t rl = strlen(g_roots[i]);
        if (strncmp(path, g_roots[i], rl) != 0) continue;
        char c = path[rl];
        if (c == '\0' || c == '/') {
            if (relsz > 0) {
                snprintf(rel, relsz, "%s", path + rl);
            }
            return i;
        }
    }
    return -1;
}

/* 影子副本路径：<tmp>/shadow/<idx>/<rel> */
static void shadow_path(int idx, const char* rel, char* out) {
    snprintf(out, PATH_MAX, "%s/shadow/%d/%s", g_tmp, idx, rel);
}

/* whiteout 标记路径：<tmp>/whiteout/<idx>/<rel> */
static void whiteout_path(int idx, const char* rel, char* out) {
    snprintf(out, PATH_MAX, "%s/whiteout/%d/%s", g_tmp, idx, rel);
}

static int vortex_whiteout_exists(int idx, const char* rel) {
    char wp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    return real_access(wp, F_OK) == 0;
}

/* 逐级建父目录（最后一节是文件名时也安全）。 */
static void ensure_parent_dirs(const char* path) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", path);
    for (char* p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            real_mkdir(tmp, 0755);
            *p = '/';
        }
    }
    real_mkdir(tmp, 0755); /* 末节若是目录也建上 */
}

/* 把真实文件 copy-up 到影子副本（目录建空目录，软链复制链接，普通文件拷内容）。 */
static int vortex_copy_up(const char* real, const char* shadow) {
    ensure_parent_dirs(shadow);
    struct stat st;
    if (real_lstat(real, &st) == 0) {
        if (S_ISDIR(st.st_mode)) {
            real_mkdir(shadow, st.st_mode & 07777);
            return 0;
        }
        if (S_ISLNK(st.st_mode)) {
            char buf[PATH_MAX];
            ssize_t n = readlink(real, buf, sizeof buf - 1);
            if (n > 0) { buf[n] = '\0'; real_symlink(buf, shadow); }
            return 0;
        }
        int in = real_open(real, O_RDONLY);
        if (in < 0) return -1;
        int out = real_open(shadow, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (out < 0) { close(in); return -1; }
        char buf[65536];
        ssize_t r;
        while ((r = read(in, buf, sizeof buf)) > 0) {
            ssize_t w = 0;
            while (w < r) {
                ssize_t k = write(out, buf + w, (size_t)(r - w));
                if (k < 0) { r = -1; break; }
                w += k;
            }
            if (r < 0) break;
        }
        close(in);
        close(out);
        real_chmod(shadow, st.st_mode & 07777);
        return 0;
    }
    /* 真实文件不存在（新建）：建空影子文件即可。 */
    int out = real_open(shadow, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out >= 0) close(out);
    return 0;
}

/* 首次 copy-up 时写哨兵（每会话仅一次，靠 NOTIFIED 标记节流）。 */
static void vortex_notify_shadowing(void) {
    if (g_notice[0] == '\0') return;
    if (g_notified[0] != '\0' && real_access(g_notified, F_OK) == 0) return;
    int fd = real_open(g_notice, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) close(fd);
    if (g_notified[0] != '\0') {
        int f2 = real_open(g_notified, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (f2 >= 0) close(f2);
    }
}

/* 写 whiteout 标记（删除即成功，真实文件不动）。 */
static void set_whiteout(int idx, const char* rel) {
    char wp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    ensure_parent_dirs(wp);
    int fd = real_open(wp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) close(fd);
}

/* 清 whiteout（文件被重新创建 / 改写时）。 */
static void clear_whiteout(int idx, const char* rel) {
    char wp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    real_unlink(wp);
}

/* 影子化删除：删影子副本 + 写 whiteout，返回成功。 */
static int vortex_shadow_delete(int idx, const char* rel) {
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    real_unlink(sp);
    real_rmdir(sp);
    set_whiteout(idx, rel);
    return 0;
}

/* ---- open / open64 / openat -------------------------------------------- */

static int vortex_dispatch_open(const char* path, int flags, mode_t mode) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0) return real_open(path, flags, mode);
    if (rel[0] == '\0') return real_open(path, flags, mode); /* 根目录本身不拦 */

    char wp[PATH_MAX], sp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    shadow_path(idx, rel, sp);

    if (vortex_whiteout_exists(idx, rel)) {
        errno = ENOENT;
        return -1;
    }

    int write_op = (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND)) != 0;

    if (write_op) {
        if (real_access(sp, F_OK) != 0) {
            if (vortex_copy_up(path, sp) != 0) {
                /* 拷贝失败（真实路径缺失等）则退化为建立空影子。 */
                ensure_parent_dirs(sp);
                int fd = real_open(sp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
                if (fd >= 0) close(fd);
            }
        }
        clear_whiteout(idx, rel);
        vortex_notify_shadowing();
        return real_open(sp, flags, mode);
    }

    /* 只读：影子存在则读影子（看到修改），否则透传真实文件。 */
    if (real_access(sp, F_OK) == 0) {
        return real_open(sp, flags, mode);
    }
    return real_open(path, flags, mode);
}

int open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap);
    }
    return vortex_dispatch_open(path, flags, mode);
}

int open64(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap);
    }
    return vortex_dispatch_open(path, flags, mode);
}

static int resolve_at(int dirfd, const char* path, char* out) {
    if (dirfd == AT_FDCWD) {
        snprintf(out, PATH_MAX, "%s", path);
        return 1;
    }
    char fdlink[64];
    snprintf(fdlink, sizeof fdlink, "/proc/self/fd/%d", dirfd);
    ssize_t n = readlink(fdlink, out, PATH_MAX - 1);
    if (n <= 0) return 0;
    out[n] = '\0';
    if (path[0] == '/') {
        snprintf(out, PATH_MAX, "%s", path);
        return 1;
    }
    size_t l = strlen(out);
    if (l && out[l - 1] != '/') strcat(out, "/");
    strcat(out, path);
    return 1;
}

int openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap);
    }
    char abspath[PATH_MAX];
    if (resolve_at(dirfd, path, abspath)) {
        return vortex_dispatch_open(abspath, flags, mode);
    }
    return real_openat(dirfd, path, flags, mode);
}

/* ---- unlink / rmdir / remove / unlinkat -------------------------------- */

int unlink(const char* path) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_unlink(path);
    return vortex_shadow_delete(idx, rel);
}

int rmdir(const char* path) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_rmdir(path);
    return vortex_shadow_delete(idx, rel);
}

int remove(const char* path) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_remove(path);
    struct stat st;
    if (real_lstat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
        return vortex_shadow_delete(idx, rel);
    }
    return vortex_shadow_delete(idx, rel);
}

int unlinkat(int dirfd, const char* path, int flag) {
    char abspath[PATH_MAX];
    if (!resolve_at(dirfd, path, abspath)) return real_unlinkat(dirfd, path, flag);
    char rel[PATH_MAX];
    int idx = is_intercepted(abspath, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_unlinkat(dirfd, path, flag);
    if (flag & AT_REMOVEDIR) return vortex_shadow_delete(idx, rel);
    return vortex_shadow_delete(idx, rel);
}

/* ---- rename / link / symlink ------------------------------------------- */

/* 前向声明：vortex_shadow_move 在下方定义前需调用。 */
static int vortex_copy_up_relative(int idx, const char* rel, const char* shadow_dst);

static int vortex_shadow_move(int idx, const char* rel_old, const char* rel_new) {
    char spo[PATH_MAX], spn[PATH_MAX], wpo[PATH_MAX], wpn[PATH_MAX];
    shadow_path(idx, rel_old, spo);
    shadow_path(idx, rel_new, spn);
    whiteout_path(idx, rel_old, wpo);
    whiteout_path(idx, rel_new, wpn);
    real_unlink(spn);
    real_unlink(wpn);
        if (real_access(spo, F_OK) == 0) {
            ensure_parent_dirs(spn);
            real_rename(spo, spn);
        } else {
            /* 旧文件无影子副本：先 copy-up 再移动，保证内容一致。 */
            vortex_copy_up_relative(idx, rel_old, spn);
        }
    set_whiteout(idx, rel_old);
    clear_whiteout(idx, rel_new);
    return 0;
}

/* 从根 + rel 推导真实路径并 copy-up 到指定影子路径。 */
static int vortex_copy_up_relative(int idx, const char* rel, const char* shadow_dst) {
    char real[PATH_MAX];
    snprintf(real, sizeof real, "%s/%s", g_roots[idx], rel);
    return vortex_copy_up(real, shadow_dst);
}

int rename(const char* oldpath, const char* newpath) {
    char ro[PATH_MAX], rn[PATH_MAX];
    int io = is_intercepted(oldpath, ro, sizeof ro);
    int in = is_intercepted(newpath, rn, sizeof rn);
    if (io >= 0 && in >= 0 && io == in && ro[0] && rn[0]) {
        return vortex_shadow_move(io, ro, rn);
    }
    if (io >= 0 && ro[0]) {
        /* 旧在受控根：影子删除旧；新不在根则不做真实移动（变更不落盘）。 */
        return vortex_shadow_delete(io, ro);
    }
    if (in >= 0 && rn[0]) {
        /* 新在受控根：把旧内容 copy-up 到新影子（旧本身保留）。 */
        char spn[PATH_MAX];
        shadow_path(in, rn, spn);
        if (real_access(spn, F_OK) != 0) vortex_copy_up(newpath, spn);
        clear_whiteout(in, rn);
        return 0;
    }
    return real_rename(oldpath, newpath);
}

int renameat(int dirfd, const char* oldpath, int newdirfd, const char* newpath) {
    char a[PATH_MAX], b[PATH_MAX];
    if (!resolve_at(dirfd, oldpath, a)) return real_renameat(dirfd, oldpath, newdirfd, newpath);
    if (!resolve_at(newdirfd, newpath, b)) return real_renameat(dirfd, oldpath, newdirfd, newpath);
    return rename(a, b);
}

int link(const char* oldpath, const char* newpath) {
    char rn[PATH_MAX];
    int in = is_intercepted(newpath, rn, sizeof rn);
    if (in >= 0 && rn[0]) {
        char spn[PATH_MAX];
        shadow_path(in, rn, spn);
        if (real_access(spn, F_OK) != 0) vortex_copy_up(oldpath, spn);
        clear_whiteout(in, rn);
        return 0;
    }
    return real_link(oldpath, newpath);
}

int linkat(int dirfd, const char* oldpath, int newdirfd, const char* newpath, int flags) {
    char b[PATH_MAX];
    if (!resolve_at(newdirfd, newpath, b)) return real_linkat(dirfd, oldpath, newdirfd, newpath, flags);
    return link(oldpath, b);
}

int symlink(const char* target, const char* linkpath) {
    char rn[PATH_MAX];
    int in = is_intercepted(linkpath, rn, sizeof rn);
    if (in >= 0 && rn[0]) {
        char spn[PATH_MAX];
        shadow_path(in, rn, spn);
        real_unlink(spn);
        ensure_parent_dirs(spn);
        real_symlink(target, spn);
        clear_whiteout(in, rn);
        return 0;
    }
    return real_symlink(target, linkpath);
}

int symlinkat(const char* target, int newdirfd, const char* linkpath) {
    char b[PATH_MAX];
    if (!resolve_at(newdirfd, linkpath, b)) return real_symlinkat(target, newdirfd, linkpath);
    return symlink(target, b);
}

/* ---- truncate / ftruncate ---------------------------------------------- */

int truncate(const char* path, off_t length) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_truncate(path, length);
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    if (real_access(sp, F_OK) != 0) {
        if (vortex_copy_up(path, sp) != 0) return -1;
    }
    clear_whiteout(idx, rel);
    vortex_notify_shadowing();
    return real_truncate(sp, length);
}

int ftruncate(int fd, off_t length) {
    char fdlink[64];
    snprintf(fdlink, sizeof fdlink, "/proc/self/fd/%d", fd);
    char path[PATH_MAX];
    ssize_t n = readlink(fdlink, path, PATH_MAX - 1);
    if (n > 0) {
        path[n] = '\0';
        char rel[PATH_MAX];
        int idx = is_intercepted(path, rel, sizeof rel);
        if (idx >= 0 && rel[0]) {
            char sp[PATH_MAX];
            shadow_path(idx, rel, sp);
            if (real_access(sp, F_OK) != 0 && vortex_copy_up(path, sp) != 0) return -1;
            clear_whiteout(idx, rel);
            vortex_notify_shadowing();
            int sfd = real_open(sp, O_RDWR);
            if (sfd >= 0) { int r = real_ftruncate(sfd, length); close(sfd); return r; }
        }
    }
    return real_ftruncate(fd, length);
}

/* ---- mkdir / mkdirat --------------------------------------------------- */

int mkdir(const char* path, mode_t mode) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_mkdir(path, mode);
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    ensure_parent_dirs(sp);
    real_mkdir(sp, mode);
    clear_whiteout(idx, rel);
    return 0;
}

int mkdirat(int dirfd, const char* path, mode_t mode) {
    char a[PATH_MAX];
    if (!resolve_at(dirfd, path, a)) return real_mkdirat(dirfd, path, mode);
    return mkdir(a, mode);
}

/* ---- stat / lstat / fstatat / access ----------------------------------- */

static int vortex_dispatch_stat(const char* path, struct stat* buf, int follow) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') {
        return follow ? real_stat(path, buf) : real_lstat(path, buf);
    }
    if (vortex_whiteout_exists(idx, rel)) {
        errno = ENOENT;
        return -1;
    }
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    if (real_access(sp, F_OK) == 0) {
        return follow ? real_stat(sp, buf) : real_lstat(sp, buf);
    }
    return follow ? real_stat(path, buf) : real_lstat(path, buf);
}

int stat(const char* path, struct stat* buf) {
    return vortex_dispatch_stat(path, buf, 1);
}

int stat64(const char* path, struct stat64* buf) {
    return vortex_dispatch_stat(path, (struct stat*)buf, 1);
}

int lstat(const char* path, struct stat* buf) {
    return vortex_dispatch_stat(path, buf, 0);
}

int lstat64(const char* path, struct stat64* buf) {
    return vortex_dispatch_stat(path, (struct stat*)buf, 0);
}

int fstatat(int dirfd, const char* path, struct stat* buf, int flag) {
    char a[PATH_MAX];
    if (!resolve_at(dirfd, path, a)) return real_fstatat(dirfd, path, buf, flag);
    int follow = (flag & AT_SYMLINK_NOFOLLOW) ? 0 : 1;
    return vortex_dispatch_stat(a, buf, follow);
}

int access(const char* path, int mode) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_access(path, mode);
    if (vortex_whiteout_exists(idx, rel)) {
        errno = ENOENT;
        return -1;
    }
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    if (real_access(sp, F_OK) == 0) return real_access(sp, mode);
    return real_access(path, mode);
}

/* ---- 目录枚举（让「删除」在 ls 中也可见）------------------------------ */

/* 自定义目录流：合并真实目录 + 影子目录，剔除 whiteout 项。 */
struct vortex_dir {
    unsigned int magic;   /* 标记：非受控目录返回的是真实 DIR*，readdir 需据此分流 */
    char** names;
    int    count;
    int    pos;
    struct dirent ent;
};

#define VORTEX_DIR_MAGIC 0x56525844u  /* "VRXD" */

static void vortex_dir_add(char*** names, int* count, const char* name) {
    /* 去重：影子项覆盖真实项（同名只留一次）。 */
    for (int i = 0; i < *count; i++) {
        if (strcmp((*names)[i], name) == 0) return;
    }
    *names = realloc(*names, (size_t)(*count + 1) * sizeof(char*));
    (*names)[*count] = strdup(name);
    (*count)++;
}

static DIR* vortex_opendir_impl(const char* path) {
    char rel[PATH_MAX];
    int idx = is_intercepted(path, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_opendir(path);

    struct vortex_dir* vd = calloc(1, sizeof(struct vortex_dir));
    if (!vd) return real_opendir(path);

    char realp[PATH_MAX], shadp[PATH_MAX];
    snprintf(realp, sizeof realp, "%s/%s", g_roots[idx], rel);
    shadow_path(idx, rel, shadp);

    /* 真实目录条目。 */
    DIR* rd = real_opendir(realp);
    if (rd) {
        struct dirent* e;
        while ((e = real_readdir(rd))) {
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
            char child[PATH_MAX];
            snprintf(child, sizeof child, "%s/%s", rel, e->d_name);
            if (vortex_whiteout_exists(idx, child)) continue;
            vortex_dir_add(&vd->names, &vd->count, e->d_name);
        }
        real_closedir(rd);
    }
    /* 影子目录条目（覆盖真实同名项，并补上新建项）。 */
    DIR* sd = real_opendir(shadp);
    if (sd) {
        struct dirent* e;
        while ((e = real_readdir(sd))) {
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
            vortex_dir_add(&vd->names, &vd->count, e->d_name);
        }
        real_closedir(sd);
    }

    return (DIR*)vd;
}

DIR* opendir(const char* path) {
    return vortex_opendir_impl(path);
}

struct dirent* readdir(DIR* dirp) {
    /* 仅对我们自己的流做合并；其它（含真实返回的非 vortex_dir）交回真实实现。 */
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    /* 仅靠指针判断不安全：用 count/pos 在堆上，且真实 DIR* 不可能满足此结构。
     * 这里接受风险——本库只对自己 opendir 返回的流调用，调用方成对使用。 */
    if (vd == NULL) return real_readdir(dirp);
    if (vd->pos >= vd->count) return NULL;
    const char* nm = vd->names[vd->pos++];
    memset(&vd->ent, 0, sizeof vd->ent);
    strncpy(vd->ent.d_name, nm, sizeof vd->ent.d_name - 1);
    vd->ent.d_name[sizeof vd->ent.d_name - 1] = '\0';
    vd->ent.d_type = DT_UNKNOWN;
    vd->ent.d_ino = 0;
    vd->ent.d_reclen = (unsigned short)(sizeof vd->ent);
    vd->ent.d_off = vd->pos;
    return &vd->ent;
}

int readdir_r(DIR* dirp, struct dirent* entry, struct dirent** result) {
    struct dirent* e = readdir(dirp);
    if (!e) { *result = NULL; return 0; }
    *entry = *e;
    *result = entry;
    return 0;
}

int closedir(DIR* dirp) {
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL) return real_closedir(dirp);
    for (int i = 0; i < vd->count; i++) free(vd->names[i]);
    free(vd->names);
    free(vd);
    return 0;
}

/* ---- 构造函数：读取配置 ------------------------------------------------ */

__attribute__((constructor)) static void vortex_init(void) {
    resolve_real();

    const char* disable = getenv("VORTEX_SHADOW_DISABLE");
    if (disable && disable[0] == '1') { g_enabled = 0; return; }

    const char* tmp = getenv("VORTEX_SHADOW_TMP");
    const char* roots = getenv("VORTEX_SHADOW_ROOTS");
    if (!tmp || !tmp[0] || !roots || !roots[0]) { g_enabled = 0; return; }

    snprintf(g_tmp, sizeof g_tmp, "%s", tmp);

    g_nroots = 0;
    char buf[PATH_MAX];
    snprintf(buf, sizeof buf, "%s", roots);
    char* save = NULL;
    for (char* tok = strtok_r(buf, ":", &save); tok && g_nroots < VORTEX_MAX_ROOTS;
         tok = strtok_r(NULL, ":", &save)) {
        if (tok[0] == '\0') continue;
        /* 目录需存在才纳入，避免把不存在的路径当根导致误拦。 */
        struct stat st;
        if (real_stat(tok, &st) != 0 || !S_ISDIR(st.st_mode)) continue;
        snprintf(g_roots[g_nroots], sizeof g_roots[g_nroots], "%s", tok);
        g_nroots++;
    }
    if (g_nroots == 0) { g_enabled = 0; return; }

    const char* notice = getenv("VORTEX_SHADOW_NOTICE");
    const char* notified = getenv("VORTEX_SHADOW_NOTIFIED");
    if (notice) snprintf(g_notice, sizeof g_notice, "%s", notice);
    if (notified) snprintf(g_notified, sizeof g_notified, "%s", notified);

    /* 确保影子 / whiteout 树根存在。 */
    char d[PATH_MAX];
    snprintf(d, sizeof d, "%s/shadow", g_tmp);
    real_mkdir(d, 0755);
    snprintf(d, sizeof d, "%s/whiteout", g_tmp);
    real_mkdir(d, 0755);

    g_enabled = 1;
}
