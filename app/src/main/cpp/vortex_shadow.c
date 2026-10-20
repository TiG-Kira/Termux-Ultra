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
#include <sys/syscall.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

#ifndef RENAME_EXCHANGE
#define RENAME_EXCHANGE (1u << 1)
#endif
#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1u << 0)
#endif

#define VORTEX_MAX_ROOTS 16

/* ---- 运行时配置（构造函数里读取一次）---------------------------------- */
static int          g_enabled = 0;
static char         g_tmp[PATH_MAX];
static char         g_roots[VORTEX_MAX_ROOTS][PATH_MAX];
static int          g_nroots = 0;
static char         g_notice[PATH_MAX];
static char         g_notified[PATH_MAX];

/* ---- 伪 ROOT 身份重定向 --------------------------------------------------
 *
 * 沙箱**不再**用 proot 把整个 /proc 换成软链农场。那样做时 /proc/self/fd、
 * /proc/self/maps、/proc/self/comm 等条目都是指向 proot 虚拟路径
 * (/dev/.vortex_realproc/...) 的软链，而 proot 解析软链目标时不套用 bind，
 * 必然 ENOENT —— 动态链接器的 readlink("/proc/self/fd/N") 于是整片失败，
 * 沙箱初始化时刷出大量
 *   WARNING: linker: unable to get realpath for the library "..."
 * 现在 /proc 保持真实，只把「root 身份」这两条线索按精确路径重定向到伪文件。 */
static char g_fake_status[PATH_MAX];
static char g_fake_attr[PATH_MAX];

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
DEF_REAL(int,    fstatat,  (int, const char*, struct stat*, int));
DEF_REAL(int,    access,   (const char*, int));
DEF_REAL(int,    faccessat,(int, const char*, int, int));
DEF_REAL(int,    chmod,    (const char*, mode_t));
DEF_REAL(int,    remove,   (const char*));
DEF_REAL(DIR*,   opendir,  (const char*));
DEF_REAL(DIR*,   fdopendir,(int));
DEF_REAL(int,    dirfd,    (DIR*));
DEF_REAL(struct dirent*, readdir,  (DIR*));
DEF_REAL(int,    readdir_r,(DIR*, struct dirent*, struct dirent**));
DEF_REAL(int,    closedir, (DIR*));
DEF_REAL(void,   rewinddir,(DIR*));
DEF_REAL(void,   seekdir,  (DIR*, long));
DEF_REAL(long,   telldir,  (DIR*));
DEF_REAL(char*,  getcwd,   (char*, size_t));
#undef DEF_REAL

/* ---- 早期兜底：真实指针在构造函数运行前为 NULL ----------------------------
 *
 * 陷阱：bionic 的 libc 在 `linker64 call_constructors` 期间、**本库构造函数执行
 * 之前**的 preinit（__libc_init_globals → __libc_init_setjmp_cookie →
 * __libc_safe_arc4random_buf）里就会 open("/dev/urandom") 初始化 arc4random 与栈保护
 * cookie。此时 real_open 尚未由 dlsym 解析（仍为 NULL），直接调用等于跳向空指针
 * → SIGSEGV（表现为沙箱会话秒退 139）。
 *
 * 因此真实指针为空时绝不能直接调用：open 系列改走原始 syscall（不依赖 libc / dlsym，
 * 进程刚启动即可安全执行）；其余函数在 preinit 阶段不会被调用，一律返回良性错误，
 * 而不是让进程崩溃。
 */
static int vortex_raw_openat(int dirfd, const char* path, int flags, mode_t mode) {
    return (int)syscall(SYS_openat, dirfd, path, (unsigned long)flags, (unsigned long)mode);
}

/* 「真实指针尚未就绪」的统一良性失败（EAGAIN：稍后再试，而不是崩溃）。 */
static int vortex_not_ready(void) {
    errno = EAGAIN;
    return -1;
}

static int vortex_stat_real(const char* path, struct stat* buf, int follow);

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
    real_fstatat  = (int(*)(int, const char*, struct stat*, int)) dlsym(RTLD_NEXT, "fstatat");
    real_access   = (int(*)(const char*, int))             dlsym(RTLD_NEXT, "access");
    real_faccessat= (int(*)(int, const char*, int, int))   dlsym(RTLD_NEXT, "faccessat");
    real_chmod    = (int(*)(const char*, mode_t))          dlsym(RTLD_NEXT, "chmod");
    real_remove   = (int(*)(const char*))                  dlsym(RTLD_NEXT, "remove");
    real_opendir  = (DIR*(*)(const char*))                 dlsym(RTLD_NEXT, "opendir");
    real_fdopendir= (DIR*(*)(int))                         dlsym(RTLD_NEXT, "fdopendir");
    real_dirfd    = (int(*)(DIR*))                         dlsym(RTLD_NEXT, "dirfd");
    real_readdir  = (struct dirent*(*)(DIR*))              dlsym(RTLD_NEXT, "readdir");
    real_readdir_r= (int(*)(DIR*, struct dirent*, struct dirent**)) dlsym(RTLD_NEXT, "readdir_r");
    real_closedir = (int(*)(DIR*))                         dlsym(RTLD_NEXT, "closedir");
    real_rewinddir= (void(*)(DIR*))                        dlsym(RTLD_NEXT, "rewinddir");
    real_seekdir  = (void(*)(DIR*, long))                  dlsym(RTLD_NEXT, "seekdir");
    real_telldir  = (long(*)(DIR*))                        dlsym(RTLD_NEXT, "telldir");
    real_getcwd   = (char*(*)(char*, size_t))              dlsym(RTLD_NEXT, "getcwd");
}

/* ---- 路径工具 ----------------------------------------------------------- */

/* 把 cwd + 相对路径 拼成绝对路径（不解析 .. / .，仅拼接）。供后续归一化处理。 */
static void vortex_abs_path(const char* path, char* out, size_t outsz) {
    if (path && path[0] == '/') {
        snprintf(out, outsz, "%s", path);
        return;
    }
    if (real_getcwd) {
        char cwd[PATH_MAX];
        if (real_getcwd(cwd, sizeof cwd)) {
            size_t l = strlen(cwd);
            if (l && cwd[l - 1] != '/' && outsz > l + 1) {
                snprintf(out, outsz, "%s/%s", cwd, path ? path : "");
                return;
            }
            snprintf(out, outsz, "%s%s", cwd, path ? path : "");
            return;
        }
    }
    snprintf(out, outsz, "%s", path ? path : "");
}

/* 原地归一化绝对路径：压缩重复 '/'、消解 '.' 与 '..'。
 *
 * 【为什么必须归一化】is_intercepted 是**前缀匹配**：不归一化时
 * `/data/data/com.termux/files/home/../usr/bin/rm` 的前缀是 `$HOME`，但其真实
 * 目标是 `$PREFIX` 下的文件 —— 影子层会按错误的 rel 记账，真实文件反而
 * **穿透**到磁盘（删除/写入直接落盘）。同理 `a/../b` 的多次访问必须得到
 * 同一 rel，否则同一文件会被记成两个影子副本。
 *
 * 实现为**双指针原地压缩**（不另开大数组）：读指针只前进，写指针始终
 * ≤ 读指针所在的字节，故不会覆盖尚未读取的内容。若用 `char* parts[]`
 * 之类的临时数组，栈占用会到十几 KB，在深调用链里足以打穿栈金丝雀。 */
static void vortex_canonicalize(char* p) {
    if (!p || p[0] != '/') return;
    char* w = p + 1;                 /* 保留开头的 '/' */
    char* r = p + 1;
    for (;;) {
        while (*r == '/') r++;       /* 跳过多余斜杠 */
        if (*r == '\0') break;
        char* seg_end = r;
        while (*seg_end && *seg_end != '/') seg_end++;
        size_t len = (size_t)(seg_end - r);
        if (len == 1 && r[0] == '.') {
            r = seg_end;
            continue;                /* 跳过 '.' */
        }
        if (len == 2 && r[0] == '.' && r[1] == '.') {
            r = seg_end;
            if (w > p + 1) {
                /* 回退到该段之前的分隔符位置（停在 '/' 上，由下一轮重写）。
                 * 注意必须**停在分隔符处**：若停到段首，下一轮的 `if (w != p+1)`
                 * 会再写一个 '/'，路径里就会残留 `//`（如 `/tmp//dg`）。 */
                w--;
                while (w > p + 1 && *w != '/') w--;
            }
            continue;
        }
        if (w != p + 1) *w++ = '/';
        memmove(w, r, len);
        w += len;
        r = seg_end;
    }
    *w = '\0';
}

/* 判断 path 是否落在某受控根下；是则返回根序号并写出 rel（可为空串=根本身）。
 *
 * 调用方必须**先**把路径拼成绝对路径并归一化（vortex_abs_path + vortex_canonicalize）：
 * 否则相对路径一律不拦（穿透），含 `..` 的路径会匹配到错误的根（记账错乱）。 */
static int is_intercepted(const char* path, char* rel, size_t relsz) {
    if (!g_enabled || !path || path[0] != '/') return -1;
    for (int i = 0; i < g_nroots; i++) {
        size_t rl = strlen(g_roots[i]);
        if (strncmp(path, g_roots[i], rl) != 0) continue;
        char c = path[rl];
        if (c == '\0' || c == '/') {
            if (relsz > 0) {
                snprintf(rel, relsz, "%s", path + rl);
                /* 归一化：去掉尾部多余 '/'；「根本身」（含尾斜杠）的 rel 视为空。
                 * 否则 rel="/" 会映射到影子/白化的**容器目录本身**，导致该目录
                 * 被误判为 whiteout（stat 返回 ENOENT），也无法正确合并枚举。 */
                size_t n = strlen(rel);
                while (n > 1 && rel[n - 1] == '/') rel[--n] = '\0';
                if (n == 1 && rel[0] == '/') rel[0] = '\0';
            }
            return i;
        }
    }
    return -1;
}

/* 伪 ROOT 身份重定向：/proc/self(/thread-self)/status 与 .../attr/current 指向伪文件。
 * 命中返回伪文件绝对路径，否则 NULL。与影子写无关，独立生效。 */
static const char* vortex_fake_proc_target(const char* path) {
    if (!path || path[0] != '/') return NULL;
    if (g_fake_status[0] &&
        (strcmp(path, "/proc/self/status") == 0 ||
         strcmp(path, "/proc/thread-self/status") == 0)) {
        return g_fake_status;
    }
    if (g_fake_attr[0] &&
        (strcmp(path, "/proc/self/attr/current") == 0 ||
         strcmp(path, "/proc/thread-self/attr/current") == 0)) {
        return g_fake_attr;
    }
    return NULL;
}

/* 影子副本路径：<tmp>/shadow/<idx>/<rel>；rel 为空（受控根本身）时即影子根目录。 */
static void shadow_path(int idx, const char* rel, char* out) {
    if (rel[0] == '\0')
        snprintf(out, PATH_MAX, "%s/shadow/%d", g_tmp, idx);
    else
        snprintf(out, PATH_MAX, "%s/shadow/%d/%s", g_tmp, idx, rel);
}

/* whiteout 标记路径：<tmp>/whiteout/<idx>/<rel>.wht
 *
 * 为什么带后缀：标记必须能与「其子项标记的路径中间层」共存。若直接用
 * `<rel>` 本身当标记文件，则「目录 dir 被删」（whiteout/0/dir 是文件）与
 * 「dir/child 被删」（需要 whiteout/0/dir 作为目录）互相矛盾 —— 目录一旦
 * 有被删子项，它自身的删除标记就再也写不进去（或反过来把子项标记挤掉）。
 * 用 .wht 后缀后，路径树里的目录纯粹是结构节点，永远不与标记文件冲突。 */
static void whiteout_path(int idx, const char* rel, char* out) {
    if (rel[0] == '\0')
        snprintf(out, PATH_MAX, "%s/whiteout/%d.wht", g_tmp, idx);
    else
        snprintf(out, PATH_MAX, "%s/whiteout/%d/%s.wht", g_tmp, idx, rel);
}

/* 该条目的 whiteout 标记是否存在。
 * 必须区分「标记文件」与「其它子项 whiteout 的父容器目录」：后者只是路径中间层，
 * 不代表本条目被删除。否则删除过 `dir/child` 之后，`dir` 自身会被误判为已删除
 * （stat 返回 ENOENT、枚举里整个目录消失）。 */
static int vortex_whiteout_exists(int idx, const char* rel) {
    char wp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    struct stat st;
    if (vortex_stat_real(wp, &st, 0) != 0) return 0;
    return S_ISREG(st.st_mode);
}

/* 任一**祖先目录**被 whiteout 覆盖则返回 1（overlay 语义：目录删除即整个子树隐藏）。
 * 没有这一层判定，`rm -rf dir` 之后直接 stat `dir/file` 仍能命中真实文件 —— 沙箱
 * 里目录已经没了，里面的文件却还能读到。 */
static int vortex_whiteout_covered(int idx, const char* rel) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", rel);
    for (char* slash = strrchr(tmp, '/'); slash; slash = strrchr(tmp, '/')) {
        *slash = '\0';
        if (tmp[0] == '\0') break;
        if (vortex_whiteout_exists(idx, tmp)) return 1;
    }
    return 0;
}

/* 逐级建父目录。**不含末节本身**：末节由调用方按自身类型创建（普通文件 / 软链 /
 * 目录），若在此一律建成目录，新建文件或软链的写入会直接得到 EISDIR。 */
static void ensure_parent_dirs(const char* path) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", path);
    for (char* p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            real_mkdirat(AT_FDCWD, tmp, 0755);
            *p = '/';
        }
    }
}

/* 把真实文件 copy-up 到影子副本（目录建空目录，软链复制链接，普通文件拷内容）。 */
static int vortex_copy_up(const char* real, const char* shadow) {
    ensure_parent_dirs(shadow);
    struct stat st;
    if (vortex_stat_real(real, &st, 0) == 0) {
        if (S_ISDIR(st.st_mode)) {
            real_mkdirat(AT_FDCWD, shadow, st.st_mode & 07777);
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

/* 用真实原语递归删除（只用于清理 whiteout 容器目录，绝不作用于受控根下的真实文件）。 */
static void vortex_rm_rf(const char* path) {
    struct stat st;
    if (vortex_stat_real(path, &st, 0) != 0) return;
    if (!S_ISDIR(st.st_mode)) {
        real_unlinkat(AT_FDCWD, path, 0);
        return;
    }
    DIR* d = real_opendir(path);
    if (d) {
        struct dirent* e;
        while ((e = real_readdir(d))) {
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
            char child[PATH_MAX];
            snprintf(child, sizeof child, "%s/%s", path, e->d_name);
            vortex_rm_rf(child);
        }
        real_closedir(d);
    }
    real_unlinkat(AT_FDCWD, path, AT_REMOVEDIR);
}

/* 写 whiteout 标记（删除即成功，真实文件不动）。 */
static void set_whiteout(int idx, const char* rel) {
    char wp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    ensure_parent_dirs(wp);
    /* 该位置可能已被子项 whiteout 用作父容器目录（先删 dir/child，再删 dir）。
     * 整体 whiteout 后子项标记已冗余，清掉目录再落标记文件，保证标记恒为普通文件。 */
    struct stat st;
    if (vortex_stat_real(wp, &st, 0) == 0 && !S_ISREG(st.st_mode)) {
        vortex_rm_rf(wp);
    }
    int fd = real_open(wp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) close(fd);
}

/* 清 whiteout（文件被重新创建 / 改写时）。 */
static void clear_whiteout(int idx, const char* rel) {
    char wp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    real_unlink(wp);
}

/* 把 dir_rel 这级真实目录里、**本会话尚未改动**（无影子副本）的子项逐个 whiteout，
 * 让它们继续不出现在沙箱视图中。用于「目录被删除后又被重建」的场景。 */
static void vortex_hide_real_children(int idx, const char* dir_rel) {
    char realdir[PATH_MAX];
    snprintf(realdir, sizeof realdir, "%s/%s", g_roots[idx], dir_rel);
    DIR* d = real_opendir(realdir);
    if (!d) return;
    struct dirent* e;
    while ((e = real_readdir(d))) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        char child_rel[PATH_MAX], child_shadow[PATH_MAX];
        snprintf(child_rel, sizeof child_rel, "%s/%s", dir_rel, e->d_name);
        shadow_path(idx, child_rel, child_shadow);
        if (real_access(child_shadow, F_OK) == 0) continue;  /* 本会话已改动：保留 */
        set_whiteout(idx, child_rel);
    }
    real_closedir(d);
}

/* 逐级清除祖先 whiteout 标记，并**重新隐藏被删目录的原有子项**。
 *
 * 语义：`rm -rf dir` 之后 dir 及其全部内容在沙箱里消失；若随后 `mkdir dir` 或
 * `echo x > dir/new`，目录被「重新创建」——此时旧子项**不该**重新出现（真实文件
 * 系统里它们已被删掉了）。因此每清除一级祖先标记，就遍历该级真实目录，为每个
 * **尚未被本会话改动**（无影子副本）的子项补一条 whiteout，把它们继续挡在视图外。
 * 仅清到**最深的未标记祖先**为止——再往上仍属被删的整棵子树，语义上保持隐藏。 */
static void clear_whiteout_covered(int idx, const char* rel) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof tmp, "%s", rel);
    for (char* slash = strrchr(tmp, '/'); slash; slash = strrchr(tmp, '/')) {
        *slash = '\0';
        if (tmp[0] == '\0') break;
        if (!vortex_whiteout_exists(idx, tmp)) break;   /* 未标记：祖先链在此恢复可见 */
        vortex_hide_real_children(idx, tmp);            /* 保持旧子项的「已删除」视图 */
        clear_whiteout(idx, tmp);
    }
}

/* 影子化删除：删影子副本 + 写 whiteout，返回成功。
 * 目录的影子副本要**递归**清掉（里面可能堆着 copy-up 出来的子项），
 * 否则该目录在沙箱内删除后，shadow/ 里仍残留整棵子树。 */
static int vortex_shadow_delete(int idx, const char* rel) {
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    struct stat st;
    if (vortex_stat_real(sp, &st, 1) == 0 && S_ISDIR(st.st_mode)) {
        vortex_rm_rf(sp);
    } else {
        real_unlink(sp);
        real_rmdir(sp);
    }
    set_whiteout(idx, rel);
    return 0;
}

/* 删除分派：把路径归一化后落影子删除。
 * 返回  0 = 已影子删除（成功）；
 *       1 = 未命中受控根（调用方应透传真实删除）；
 *      -1 = 命中但失败（errno 已设：EBUSY=根目录 / ENOENT=已删除）。 */
static int vortex_delete_dispatch(const char* path) {
    char canon[PATH_MAX];
    vortex_abs_path(path, canon, sizeof canon);
    vortex_canonicalize(canon);
    char rel[PATH_MAX];
    int idx = is_intercepted(canon, rel, sizeof rel);
    if (idx < 0) return 1;                  /* 未命中：调用方透传真实删除 */
    if (rel[0] == '\0') {                   /* 受控根本身不可删 */
        errno = EBUSY;
        return -1;
    }
    if (vortex_whiteout_exists(idx, rel)) {
        errno = ENOENT;                     /* 已删：重复删除报不存在 */
        return -1;
    }
    vortex_shadow_delete(idx, rel);
    return 0;
}

/* ---- open / open64 / openat -------------------------------------------- */

static int vortex_dispatch_open(const char* path, int flags, mode_t mode) {
    /* 伪 ROOT 身份：root 身份线索不走影子层，直接读伪文件。 */
    const char* fake = vortex_fake_proc_target(path);
    if (fake) {
        int ffd = real_open(fake, flags, mode);
        if (ffd >= 0) return ffd;
    }
    /* 相对路径 / 含 `..` 的路径一律先拼绝对 + 归一化：
     * 否则 `rm ./x`、`cd ~/../usr && rm x` 这类相对访问会漏过前缀匹配直接穿透。 */
    char canon[PATH_MAX];
    vortex_abs_path(path, canon, sizeof canon);
    vortex_canonicalize(canon);
    char rel[PATH_MAX];
    int idx = is_intercepted(canon, rel, sizeof rel);
    if (idx < 0) return real_open(path, flags, mode);
    if (rel[0] == '\0') return real_open(path, flags, mode); /* 根目录本身不拦 */

    char wp[PATH_MAX], sp[PATH_MAX];
    whiteout_path(idx, rel, wp);
    shadow_path(idx, rel, sp);

    int write_op = (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND)) != 0;

    if (vortex_whiteout_exists(idx, rel)) {
        /* 已被影子删除的条目：读取一律 ENOENT（沙箱里确实没了）。
         * 但**写入必须重建**——`echo hi > f`（先 shell 展开/检测再写）、
         * 编辑器「删除后另存」等场景会先 open 探测再创建；若此处返回
         * ENOENT，删除后就再也写不进任何文件（表现为「写入被阻止」）。 */
        if (!write_op) {
            errno = ENOENT;
            return -1;
        }
        /* 重建：清掉 whiteout，从真实文件重新 copy-up（保留原内容），
         * 再在影子副本上按 flags 打开。 */
        clear_whiteout(idx, rel);
        if (real_access(sp, F_OK) != 0) {
            if (vortex_copy_up(canon, sp) != 0) {
                ensure_parent_dirs(sp);
                int fd = real_open(sp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
                if (fd >= 0) close(fd);
            }
        }
        vortex_notify_shadowing();
        return real_open(sp, flags, mode);
    }
    /* 祖先目录被删：整棵子树在沙箱里不可见（读取 ENOENT）；但**写入要重建**，
     * 否则 `rm -rf dir` 之后再也无法 `mkdir -p dir` 之类的重建操作落地。 */
    if (vortex_whiteout_covered(idx, rel)) {
        if (!write_op) {
            errno = ENOENT;
            return -1;
        }
        clear_whiteout_covered(idx, rel);   /* 逐级清掉祖先标记 */
    }

    if (write_op) {
        /* O_EXCL 语义：沙箱视图里**已存在**才报 EEXIST。视图存在 = 影子副本在，
         * 或真实文件在（且未被 whiteout——whiteout 分支已在上面处理过）。
         * 原实现先 copy-up 建好影子再拿 O_CREAT|O_EXCL 打开，导致 `cp` 之类的
         * 工具一律 EEXIST。这里自行判定后剥掉 O_EXCL 再打开。 */
        if ((flags & O_EXCL) &&
            (real_access(sp, F_OK) == 0 || real_access(canon, F_OK) == 0)) {
            errno = EEXIST;
            return -1;
        }
        int oflags = flags & ~O_EXCL;
        if (real_access(sp, F_OK) != 0) {
            if (vortex_copy_up(canon, sp) != 0) {
                /* 拷贝失败（真实路径缺失等）则退化为建立空影子。 */
                ensure_parent_dirs(sp);
                int fd = real_open(sp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
                if (fd >= 0) close(fd);
            }
        }
        clear_whiteout(idx, rel);
        vortex_notify_shadowing();
        return real_open(sp, oflags, mode);
    }

    /* 只读：影子存在则读影子（看到修改），否则透传真实文件。
     *
     * **目录是例外，绝不能被影子目录顶替**：影子目录里只有本会话改动过的那几个子项，
     * 一旦把 open() 指向它，调用方拿到的就是一个「只剩零星子项」的目录 —— 连 libc
     * 自己的 opendir() 内部走的 openat 也一并中招，于是**往目录里新建一个文件后，
     * 该目录原有的真实文件全部消失**（ls / 通配符都只剩新建的那个）。
     * 目录内容一律在**枚举层**（opendir / fdopendir → readdir）做「真实 + 影子」合并；
     * 只有当真实目录本身不存在（沙箱内新建的目录）时才打开影子目录。 */
    if (real_access(sp, F_OK) == 0) {
        struct stat sst, rst;
        int shadow_is_dir = (vortex_stat_real(sp, &sst, 1) == 0 && S_ISDIR(sst.st_mode));
        int real_is_dir   = (vortex_stat_real(canon, &rst, 1) == 0 && S_ISDIR(rst.st_mode));
        if (!(shadow_is_dir && real_is_dir)) {
            return real_open(sp, flags, mode);
        }
        /* 真实目录在：开真实目录，子项差异交给枚举层合并。 */
    }
    return real_open(canon, flags, mode);
}

int open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap);
    }
    if (!real_open) return vortex_raw_openat(AT_FDCWD, path, flags, mode);
    return vortex_dispatch_open(path, flags, mode);
}

int open64(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap);
    }
    if (!real_open) return vortex_raw_openat(AT_FDCWD, path, flags, mode);
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
        va_list ap; va_start(ap, flags); mode = (mode_t)va_arg(ap, int); va_end(ap);
    }
    if (!real_open) return vortex_raw_openat(dirfd, path, flags, mode);
    char abspath[PATH_MAX];
    if (resolve_at(dirfd, path, abspath)) {
        return vortex_dispatch_open(abspath, flags, mode);
    }
    return real_openat(dirfd, path, flags, mode);
}

/* _FORTIFY_SOURCE 会把「两参 open」「三参 openat」分别改写成 __open_2 / __openat_2。
 * toybox（ls/find/du…）正是经这两个入口打开目录；只拦 open/openat 会让它们绕过影子层、
 * 拿到未合并的真实目录 —— 表现为「沙箱里看不到新建目录，ls 报 No such file or directory」。
 * 二者语义与 open/openat 等价（此时必无 O_CREAT 的 mode 参数），直接转接本层即可。
 *
 * 【致命细节】函数体内**绝不能写字面量的 open(path, flags) / openat(dirfd, path, flags)
 * 调用**：本库自身也在 -D_FORTIFY_SOURCE=2（AGP 的 CMake 构建默认带上）下编译，
 * fortify 头文件会把这两处字面量调用**原样改写回 __open_2 / __openat_2** —— 拦截器
 * 调用自身，无限递归/自旋：
 *   - arm64 尾调用编译成 `b .`（原地跳转）→ 首个调用者（toybox / termux bash）静默挂死，
 *     表现为「横幅之后永远等不到提示符」；
 *   - x86_64 编译成 callq → 栈耗尽 SIGSEGV，会话退出码 139。
 * 手动 ndk-clang 直编（不带 fortify）复现不了，必须以 Gradle/CMake 产物反汇编为准。
 * 这里直接调用内部实现（普通函数调用，fortify 不会改写无 open 语义的调用点）。 */
int __open_2(const char* path, int flags) {
    if (!real_open) return vortex_raw_openat(AT_FDCWD, path, flags, 0);
    return vortex_dispatch_open(path, flags, 0);
}

int __openat_2(int dirfd, const char* path, int flags) {
    if (!real_open) return vortex_raw_openat(dirfd, path, flags, 0);
    char abspath[PATH_MAX];
    if (resolve_at(dirfd, path, abspath)) {
        return vortex_dispatch_open(abspath, flags, 0);
    }
    return real_openat(dirfd, path, flags, 0);
}

/* ---- unlink / rmdir / remove / unlinkat -------------------------------- */

/* 透传规则（避免 libc ↔ 本库 环形递归）：
 * bionic 里 `xxx()` 常常只是 `xxxat()` 的薄封装（mkdir→mkdirat、rename→renameat、
 * link→linkat、symlink→symlinkat、stat/lstat→fstatat）。若透传时回调 libc 的
 * `xxx()`，libc 会再进入我们拦截的 `xxxat()`，而 `xxxat()` 分派又回到 `xxx()` ——
 * 无限递归直至栈溢出（SIGSEGV）。因此本组函数透传**一律直接调用 `xxxat` 原语**
 * （调用链末端，不会再回环）。 */

int unlink(const char* path) {
    if (!real_unlinkat) return vortex_not_ready();
    int r = vortex_delete_dispatch(path);
    if (r > 0) return real_unlinkat(AT_FDCWD, path, 0);   /* 未命中：真实删除 */
    if (r == 0) return 0;                                  /* 已影子删除 */
    return -1;                                             /* EBUSY/ENOENT */
}

int rmdir(const char* path) {
    if (!real_unlinkat) return vortex_not_ready();
    int r = vortex_delete_dispatch(path);
    if (r > 0) return real_unlinkat(AT_FDCWD, path, AT_REMOVEDIR);
    if (r == 0) return 0;
    return -1;
}

int remove(const char* path) {
    if (!real_remove) return vortex_not_ready();
    int r = vortex_delete_dispatch(path);
    if (r > 0) return real_remove(path);
    if (r == 0) return 0;
    return -1;
}

int unlinkat(int dirfd, const char* path, int flag) {
    if (!real_unlinkat) return vortex_not_ready();
    char abspath[PATH_MAX];
    if (!resolve_at(dirfd, path, abspath)) return real_unlinkat(dirfd, path, flag);
    int r = vortex_delete_dispatch(abspath);
    if (r > 0) return real_unlinkat(dirfd, path, flag);   /* 未命中：真实删除 */
    if (r == 0) return 0;
    return -1;
}

/* ---- rename / link / symlink ------------------------------------------- */

/* 前向声明：vortex_shadow_move 在下方定义前需调用。 */
static int vortex_copy_up_relative(int idx, const char* rel, const char* shadow_dst);

/* 把「真实根 + rel」这棵子树递归复制到 shadow_dst（含目录结构、文件内容与软链）。
 * 用于 mv 整个目录：影子目录里只有本会话改动过的零星子项，必须先把真实子树
 * 物化出来，否则移动后目录里凭空少掉大量文件。 */
static int vortex_copy_tree(const char* real, const char* shadow) {
    struct stat st;
    if (vortex_stat_real(real, &st, 1) != 0) return -1;
    if (S_ISDIR(st.st_mode)) {
        real_mkdir(shadow, st.st_mode & 07777);
        DIR* d = real_opendir(real);
        if (!d) return -1;
        struct dirent* e;
        char rp[PATH_MAX], sp[PATH_MAX];
        while ((e = real_readdir(d))) {
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
            snprintf(rp, sizeof rp, "%s/%s", real, e->d_name);
            snprintf(sp, sizeof sp, "%s/%s", shadow, e->d_name);
            vortex_copy_tree(rp, sp);
        }
        real_closedir(d);
        return 0;
    }
    return vortex_copy_up(real, shadow);
}

/* 把 old_rel 的影子目录（本会话已改动的子项）覆盖复制到目标影子目录。 */
static void vortex_overlay_tree(int idx, const char* old_rel, const char* dst_shadow) {
    char spo[PATH_MAX];
    shadow_path(idx, old_rel, spo);
    DIR* d = real_opendir(spo);
    if (!d) return;
    struct dirent* e;
    char sp[PATH_MAX];
    while ((e = real_readdir(d))) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        snprintf(sp, sizeof sp, "%s/%s", spo, e->d_name);
        char sdst[PATH_MAX];
        snprintf(sdst, sizeof sdst, "%s/%s", dst_shadow, e->d_name);
        vortex_copy_tree(sp, sdst);
    }
    real_closedir(d);
}

static int vortex_shadow_move(int idx, const char* rel_old, const char* rel_new) {
    char spo[PATH_MAX], spn[PATH_MAX], wpo[PATH_MAX], wpn[PATH_MAX];
    shadow_path(idx, rel_old, spo);
    shadow_path(idx, rel_new, spn);
    whiteout_path(idx, rel_old, wpo);
    whiteout_path(idx, rel_new, wpn);
    real_unlink(spn);
    real_unlink(wpn);

    /* 目录：先把整棵子树（真实 + 本会话影子改动）物化到目标影子目录，再删旧。 */
    char real_old[PATH_MAX];
    snprintf(real_old, sizeof real_old, "%s/%s", g_roots[idx], rel_old);
    struct stat rst;
    if (vortex_stat_real(real_old, &rst, 0) == 0 && S_ISDIR(rst.st_mode)) {
        vortex_copy_tree(real_old, spn);
        /* 再把本会话对该目录做出的影子改动叠上去（覆盖复制）。 */
        vortex_overlay_tree(idx, rel_old, spn);
    } else if (real_access(spo, F_OK) == 0) {
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
    if (!real_renameat) return vortex_not_ready();
    char co[PATH_MAX], cn[PATH_MAX];
    vortex_abs_path(oldpath, co, sizeof co); vortex_canonicalize(co);
    vortex_abs_path(newpath, cn, sizeof cn); vortex_canonicalize(cn);
    char ro[PATH_MAX], rn[PATH_MAX];
    int io = is_intercepted(co, ro, sizeof ro);
    int in = is_intercepted(cn, rn, sizeof rn);
    if (io >= 0 && in >= 0 && io == in && ro[0] && rn[0]) {
        return vortex_shadow_move(io, ro, rn);
    }
    if (io >= 0 && in >= 0 && io != in && ro[0] && rn[0]) {
        /* 跨受控根移动（如 mv $HOME/f $PREFIX/f）：新根 copy-up 旧内容，
         * 旧根影子删除。两侧都只动影子层。源若只是影子文件（沙箱内新建、
         * 真实盘上从未存在），必须从影子副本搬，不能从真实路径读。 */
        char spn[PATH_MAX], spo[PATH_MAX];
        shadow_path(in, rn, spn);
        shadow_path(io, ro, spo);
        if (real_access(spo, F_OK) == 0) {
            ensure_parent_dirs(spn);
            real_unlink(spn);
            real_rename(spo, spn);
        } else {
            vortex_copy_up(co, spn);
        }
        clear_whiteout(in, rn);
        vortex_shadow_delete(io, ro);
        return 0;
    }
    if (io >= 0 && ro[0]) {
        /* 旧在受控根、新在根外：把旧内容搬进新位置（真实写新位置——新位置本就不在
         * 受控范围），再影子删除旧。绝不能只删旧不搬内容，否则文件凭空丢失。
         * 源可能是影子文件（真实盘上不存在），此时直接搬影子副本。 */
        char spo[PATH_MAX];
        shadow_path(io, ro, spo);
        if (real_access(spo, F_OK) == 0) {
            ensure_parent_dirs(cn);
            real_unlink(cn);
            if (real_rename(spo, cn) != 0) return -1;
        } else {
            if (vortex_copy_up(co, cn) != 0) return -1;
        }
        vortex_shadow_delete(io, ro);
        return 0;
    }
    if (in >= 0 && rn[0]) {
        /* 新在受控根、旧在根外：把**旧的内容** copy-up 到新影子。
         * 【原 bug】旧实现 copy 的是 newpath（尚不存在的目的地），得到空文件。 */
        char spn[PATH_MAX];
        shadow_path(in, rn, spn);
        vortex_copy_up(co, spn);
        clear_whiteout(in, rn);
        vortex_notify_shadowing();
        return 0;
    }
    return real_renameat(AT_FDCWD, co, AT_FDCWD, cn);
}

int renameat(int dirfd, const char* oldpath, int newdirfd, const char* newpath) {
    if (!real_renameat) return vortex_not_ready();
    char a[PATH_MAX], b[PATH_MAX];
    if (!resolve_at(dirfd, oldpath, a)) return real_renameat(dirfd, oldpath, newdirfd, newpath);
    if (!resolve_at(newdirfd, newpath, b)) return real_renameat(dirfd, oldpath, newdirfd, newpath);
    return rename(a, b);
}

/* renameat2（RENAME_EXCHANGE / RENAME_NOREPLACE 等，mv、coreutils 高级用法会直连）。
 * 原库完全没拦它 —— 沙箱内 `mv` 走到 syscall 层就**穿透**真实磁盘。透传一律用
 * **原始 syscall**（内核层，绝不会再回环进本库），不依赖 libc 是否导出该符号。 */
int renameat2(int dirfd, const char* oldpath, int newdirfd, const char* newpath, unsigned int flags) {
    char a[PATH_MAX], b[PATH_MAX];
    if (!resolve_at(dirfd, oldpath, a)) goto raw;
    if (!resolve_at(newdirfd, newpath, b)) goto raw;
    if (flags != 0) {
        char ra[PATH_MAX], rb[PATH_MAX];
        int ia = is_intercepted(a, ra, sizeof ra);
        int ib = is_intercepted(b, rb, sizeof rb);
        /* 两侧都在影子层内：EXCHANGE 语义可用影子操作模拟；跨层则拒绝。 */
        if (flags == RENAME_EXCHANGE && ia >= 0 && ib >= 0 && ra[0] && rb[0]) {
            char spa[PATH_MAX], spb[PATH_MAX];
            shadow_path(ia, ra, spa);
            shadow_path(ib, rb, spb);
            char tmpswap[PATH_MAX];
            snprintf(tmpswap, sizeof tmpswap, "%s/.vortex_swap.%d", g_tmp, (int)getpid());
            real_rename(spa, tmpswap);
            real_rename(spb, spa);
            real_rename(tmpswap, spb);
            return 0;
        }
        /* 其余带 flag 的调用若涉及受控根：拒绝而不是穿透。 */
        if (ia >= 0 || ib >= 0) { errno = EINVAL; return -1; }
    }
    return rename(a, b);
raw:
#ifdef SYS_renameat2
    return (int)syscall(SYS_renameat2, dirfd, oldpath, newdirfd, newpath, flags);
#else
    errno = ENOSYS;
    return -1;
#endif
}

int link(const char* oldpath, const char* newpath) {
    if (!real_linkat) return vortex_not_ready();
    char co[PATH_MAX], cn[PATH_MAX];
    vortex_abs_path(oldpath, co, sizeof co); vortex_canonicalize(co);
    vortex_abs_path(newpath, cn, sizeof cn); vortex_canonicalize(cn);
    char rn[PATH_MAX];
    int in = is_intercepted(cn, rn, sizeof rn);
    if (in >= 0 && rn[0]) {
        char spn[PATH_MAX], spo[PATH_MAX];
        shadow_path(in, rn, spn);
        /* 源可能是影子文件（真实盘上不存在）：逐根探查源影子副本。 */
        spo[0] = '\0';
        char srel[PATH_MAX];
        int si = is_intercepted(co, srel, sizeof srel);
        if (si >= 0 && srel[0]) shadow_path(si, srel, spo);
        if (real_access(spn, F_OK) != 0) {
            if (spo[0] != '\0' && real_access(spo, F_OK) == 0) {
                ensure_parent_dirs(spn);
                vortex_copy_up(spo, spn);   /* 源在影子层：直接从影子副本复制 */
            } else {
                vortex_copy_up(co, spn);
            }
        }
        clear_whiteout(in, rn);
        vortex_notify_shadowing();
        return 0;
    }
    return real_linkat(AT_FDCWD, co, AT_FDCWD, cn, 0);
}

int linkat(int dirfd, const char* oldpath, int newdirfd, const char* newpath, int flags) {
    if (!real_linkat) return vortex_not_ready();
    char a[PATH_MAX], b[PATH_MAX];
    if (!resolve_at(dirfd, oldpath, a)) return real_linkat(dirfd, oldpath, newdirfd, newpath, flags);
    if (!resolve_at(newdirfd, newpath, b)) return real_linkat(dirfd, oldpath, newdirfd, newpath, flags);
    return link(a, b);
}

int symlink(const char* target, const char* linkpath) {
    if (!real_symlinkat) return vortex_not_ready();
    char cn[PATH_MAX];
    vortex_abs_path(linkpath, cn, sizeof cn); vortex_canonicalize(cn);
    char rn[PATH_MAX];
    int in = is_intercepted(cn, rn, sizeof rn);
    if (in >= 0 && rn[0]) {
        char spn[PATH_MAX];
        shadow_path(in, rn, spn);
        real_unlink(spn);
        ensure_parent_dirs(spn);
        real_symlink(target, spn);
        clear_whiteout(in, rn);
        vortex_notify_shadowing();
        return 0;
    }
    return real_symlinkat(target, AT_FDCWD, cn);
}

int symlinkat(const char* target, int newdirfd, const char* linkpath) {
    if (!real_symlinkat) return vortex_not_ready();
    char b[PATH_MAX];
    if (!resolve_at(newdirfd, linkpath, b)) return real_symlinkat(target, newdirfd, linkpath);
    return symlink(target, b);
}

/* ---- truncate / ftruncate ---------------------------------------------- */

int truncate(const char* path, off_t length) {
    if (!real_truncate) return vortex_not_ready();
    char canon[PATH_MAX];
    vortex_abs_path(path, canon, sizeof canon); vortex_canonicalize(canon);
    char rel[PATH_MAX];
    int idx = is_intercepted(canon, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_truncate(canon, length);
    if (vortex_whiteout_exists(idx, rel) || vortex_whiteout_covered(idx, rel)) {
        errno = ENOENT;
        return -1;
    }
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    if (real_access(sp, F_OK) != 0) {
        if (vortex_copy_up(canon, sp) != 0) return -1;
    }
    clear_whiteout(idx, rel);
    vortex_notify_shadowing();
    return real_truncate(sp, length);
}

int ftruncate(int fd, off_t length) {
    if (!real_ftruncate) return vortex_not_ready();
    char fdlink[64];
    snprintf(fdlink, sizeof fdlink, "/proc/self/fd/%d", fd);
    char path[PATH_MAX];
    ssize_t n = readlink(fdlink, path, PATH_MAX - 1);
    if (n > 0) {
        path[n] = '\0';
        vortex_canonicalize(path);
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
    if (!real_mkdirat) return vortex_not_ready();
    char canon[PATH_MAX];
    vortex_abs_path(path, canon, sizeof canon); vortex_canonicalize(canon);
    char rel[PATH_MAX];
    int idx = is_intercepted(canon, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_mkdirat(AT_FDCWD, canon, mode);
    if (vortex_whiteout_exists(idx, rel)) {
        /* 目录被删除后重建：先保持旧子项的「已删除」视图，再清 whiteout 建影子目录。 */
        vortex_hide_real_children(idx, rel);
        clear_whiteout(idx, rel);
    }
    /* 祖先目录被删：先逐级还原祖先链，再在影子层建目录。 */
    clear_whiteout_covered(idx, rel);
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    ensure_parent_dirs(sp);
    struct stat st;
    if (real_mkdir(sp, mode) != 0) {
        /* 已存在目录：成功复用；已存在其它类型（文件/软链）：EEXIST。 */
        if (real_access(sp, F_OK) == 0 &&
            vortex_stat_real(sp, &st, 0) == 0 && S_ISDIR(st.st_mode)) {
            return 0;
        }
        return -1;
    }
    vortex_notify_shadowing();
    return 0;
}

int mkdirat(int dirfd, const char* path, mode_t mode) {
    if (!real_mkdirat) return vortex_not_ready();
    char a[PATH_MAX];
    if (!resolve_at(dirfd, path, a)) return real_mkdirat(dirfd, path, mode);
    return mkdir(a, mode);
}

/* ---- stat / lstat / fstatat / access ----------------------------------- */

/* 真实 stat 透传**必须**走 fstatat 原语，不能回调 libc 的 stat()/lstat()：
 * bionic 的 stat()/lstat() 本身就是 fstatat() 的薄封装，回调它们会经
 * libc → fstatat（本方拦截）→ 本分派 形成无限递归 → 栈溢出 SIGSEGV。
 * fstatat 是这条调用链的原语，不会再回调 stat/lstat。 */
static int vortex_stat_real(const char* path, struct stat* buf, int follow) {
    return real_fstatat(AT_FDCWD, path, buf, follow ? 0 : AT_SYMLINK_NOFOLLOW);
}

static int vortex_dispatch_stat(const char* path, struct stat* buf, int follow) {
    const char* fake = vortex_fake_proc_target(path);
    if (fake) return vortex_stat_real(fake, buf, follow);
    char canon[PATH_MAX];
    vortex_abs_path(path, canon, sizeof canon); vortex_canonicalize(canon);
    char rel[PATH_MAX];
    int idx = is_intercepted(canon, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') {
        return vortex_stat_real(canon, buf, follow);
    }
    if (vortex_whiteout_exists(idx, rel)) {
        errno = ENOENT;
        return -1;
    }
    if (vortex_whiteout_covered(idx, rel)) {
        errno = ENOENT;
        return -1;
    }
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    if (real_access(sp, F_OK) == 0) {
        return vortex_stat_real(sp, buf, follow);
    }
    return vortex_stat_real(canon, buf, follow);
}

int stat(const char* path, struct stat* buf) {
    if (!real_fstatat) return vortex_not_ready();
    return vortex_dispatch_stat(path, buf, 1);
}

int stat64(const char* path, struct stat64* buf) {
    if (!real_fstatat) return vortex_not_ready();
    return vortex_dispatch_stat(path, (struct stat*)buf, 1);
}

int lstat(const char* path, struct stat* buf) {
    if (!real_fstatat) return vortex_not_ready();
    return vortex_dispatch_stat(path, buf, 0);
}

int lstat64(const char* path, struct stat64* buf) {
    if (!real_fstatat) return vortex_not_ready();
    return vortex_dispatch_stat(path, (struct stat*)buf, 0);
}

int fstatat(int dirfd, const char* path, struct stat* buf, int flag) {
    if (!real_fstatat) return vortex_not_ready();
    char a[PATH_MAX];
    if (!resolve_at(dirfd, path, a)) return real_fstatat(dirfd, path, buf, flag);
    int follow = (flag & AT_SYMLINK_NOFOLLOW) ? 0 : 1;
    return vortex_dispatch_stat(a, buf, follow);
}

int access(const char* path, int mode) {
    if (!real_access) return vortex_not_ready();
    const char* fake = vortex_fake_proc_target(path);
    if (fake) return real_access(fake, mode);
    char canon[PATH_MAX];
    vortex_abs_path(path, canon, sizeof canon); vortex_canonicalize(canon);
    char rel[PATH_MAX];
    int idx = is_intercepted(canon, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_access(canon, mode);
    if (vortex_whiteout_exists(idx, rel) || vortex_whiteout_covered(idx, rel)) {
        errno = ENOENT;
        return -1;
    }
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    if (real_access(sp, F_OK) == 0) return real_access(sp, mode);
    return real_access(canon, mode);
}

/* toybox 的 rm/install/cp 等做权限探测走 faccessat；不拦就会绕过影子层，
 * 对影子独有路径误报 ENOENT —— 典型症状是 `rm -r` 判定目录不存在、拒绝递归删除。 */
int faccessat(int dirfd, const char* path, int mode, int flags) {
    if (!real_faccessat) return vortex_not_ready();
    char a[PATH_MAX];
    if (!resolve_at(dirfd, path, a)) return real_faccessat(dirfd, path, mode, flags);
    vortex_canonicalize(a);
    const char* fake = vortex_fake_proc_target(a);
    if (fake) return real_faccessat(AT_FDCWD, fake, mode, flags);
    char rel[PATH_MAX];
    int idx = is_intercepted(a, rel, sizeof rel);
    if (idx < 0 || rel[0] == '\0') return real_faccessat(AT_FDCWD, a, mode, flags);
    if (vortex_whiteout_exists(idx, rel)) {
        errno = ENOENT;
        return -1;
    }
    char sp[PATH_MAX];
    shadow_path(idx, rel, sp);
    /* 透传一律走 real_faccessat 原语，避免 libc access→faccessat 回环递归。 */
    if (real_faccessat(AT_FDCWD, sp, F_OK, 0) == 0) {
        return real_faccessat(AT_FDCWD, sp, mode, flags);
    }
    return real_faccessat(AT_FDCWD, a, mode, flags);
}

/* ---- 目录枚举（让「删除」在 ls 中也可见）------------------------------ */

/* 自定义目录流：合并真实目录 + 影子目录，剔除 whiteout 项。
 *
 * 同时服务于两条入口：`opendir`（按路径枚举）与 `fdopendir`（按 fd 反查路径枚举）。
 * 后者是 toybox 的 `ls`/`find`/`du` 的实际枚举方式（`open(O_DIRECTORY)` + `fdopendir`），
 * 只拦 `opendir` 会让这些命令看到未经合并的原始目录。 */
struct vortex_dir {
    unsigned int magic;   /* 标记：非受控目录返回的是真实 DIR*，readdir 需据此分流 */
    int    fd;            /* fdopendir 来源的真实 fd（closedir 负责关闭）；opendir 来源为 -1 */
    char** names;
    unsigned char* types; /* 与 names 同序：真实/影子目录项自带的 d_type */
    ino_t* inos;          /* 与 names 同序：真实/影子目录项自带的 d_ino */
    int    count;
    int    pos;
    struct dirent ent;
};

#define VORTEX_DIR_MAGIC 0x56525844u  /* "VRXD" */

/* 某些文件系统（以及合成项）会给出 0 号 inode，而 **bash 的 glob 实现把
 * d_ino == 0 的目录项当作「已删除」直接跳过**。合成流若一律填 0，沙箱内的
 * 通配符展开就会全部落空（`ls` 却正常，因为 toybox 不检查 d_ino）——典型症状
 * 是 oh-my-bash 只加载一半、提示函数 command not found、补全失效。
 * 故 0 号一律替换为按名字散列的稳定合成值（同一次/多次枚举都保持一致）。 */
static ino_t vortex_synth_ino(const char* name) {
    unsigned long long h = 1469598103934665603ULL;   /* FNV-1a 64 */
    for (const unsigned char* p = (const unsigned char*)name; *p; p++) {
        h ^= *p;
        h *= 1099511628211ULL;
    }
    return (ino_t)(h | (1ULL << 62));   /* 恒非零 */
}

static void vortex_dir_add(char*** names, unsigned char** types, ino_t** inos,
                           int* count, const char* name, unsigned char type, ino_t ino) {
    /* 去重：影子项覆盖真实项（同名只留一次）。 */
    for (int i = 0; i < *count; i++) {
        if (strcmp((*names)[i], name) == 0) return;
    }
    *names = realloc(*names, (size_t)(*count + 1) * sizeof(char*));
    *types = realloc(*types, (size_t)(*count + 1) * sizeof(unsigned char));
    *inos  = realloc(*inos,  (size_t)(*count + 1) * sizeof(ino_t));
    (*names)[*count] = strdup(name);
    (*types)[*count] = type;
    (*inos)[*count]  = ino ? ino : vortex_synth_ino(name);
    (*count)++;
}

/* 组装合并目录流（真实条目剔除 whiteout；影子条目覆盖同名项并补上新建项）。
 * 失败返回 NULL，由调用方回落真实实现。 */
static DIR* vortex_dir_build(int idx, const char* rel) {
    struct vortex_dir* vd = calloc(1, sizeof(struct vortex_dir));
    if (!vd) return NULL;
    vd->magic = VORTEX_DIR_MAGIC;
    vd->fd = -1;

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
            vortex_dir_add(&vd->names, &vd->types, &vd->inos, &vd->count,
                           e->d_name, e->d_type, e->d_ino);
        }
        real_closedir(rd);
    }
    /* 影子目录条目（覆盖真实同名项，并补上新建项）。 */
    DIR* sd = real_opendir(shadp);
    if (sd) {
        struct dirent* e;
        while ((e = real_readdir(sd))) {
            if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
            vortex_dir_add(&vd->names, &vd->types, &vd->inos, &vd->count,
                           e->d_name, e->d_type, e->d_ino);
        }
        real_closedir(sd);
    }
    return (DIR*)vd;
}

static DIR* vortex_opendir_impl(const char* path) {
    char canon[PATH_MAX];
    vortex_abs_path(path, canon, sizeof canon); vortex_canonicalize(canon);
    char rel[PATH_MAX];
    int idx = is_intercepted(canon, rel, sizeof rel);
    if (idx < 0) return real_opendir(canon);
    /* 目录自身被 whiteout：opendir 内部的 openat 是 libc 内部调用，**不经过**
     * 本库的 open 拦截**，此处必须自行判定，否则已删除目录仍能被枚举。 */
    if (rel[0] && vortex_whiteout_exists(idx, rel)) {
        errno = ENOENT;
        return NULL;
    }
    DIR* d = vortex_dir_build(idx, rel);
    return d ? d : real_opendir(canon);
}

DIR* opendir(const char* path) {
    if (!real_opendir) { errno = EAGAIN; return NULL; }
    return vortex_opendir_impl(path);
}

/* fd 指向的绝对路径（/proc/self/fd/N），并剥掉 " (deleted)" 后缀。成功返回 0。 */
static int vortex_fd_path(int fd, char* out, size_t outsz) {
    char link[64];
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, out, outsz - 1);
    if (n <= 0 || (size_t)n >= outsz) return -1;
    out[n] = '\0';
    static const char kDeleted[] = " (deleted)";
    size_t dl = sizeof kDeleted - 1;
    if ((size_t)n > dl && memcmp(out + n - dl, kDeleted, dl) == 0) out[n - dl] = '\0';
    return 0;
}

/* 影子副本路径 → (idx, rel)：<tmp>/shadow/<idx>/<rel>。命中返回根序号，否则 -1。
 * 目录一旦被 copy-up，后续 open 返回的是影子 fd，需要据此还原出对应真实根的合并视图。 */
static int vortex_shadow_to_rel(const char* path, char* rel, size_t relsz) {
    char prefix[PATH_MAX];
    snprintf(prefix, sizeof prefix, "%s/shadow/", g_tmp);
    size_t pl = strlen(prefix);
    if (strncmp(path, prefix, pl) != 0) return -1;
    const char* rest = path + pl;
    char* end = NULL;
    long idx = strtol(rest, &end, 10);
    if (end == rest || (*end != '\0' && *end != '/')) return -1;
    if (idx < 0 || idx >= g_nroots) return -1;
    if (relsz) snprintf(rel, relsz, "%s", end);   /* end 以 '/' 开头，或为空串 */
    return (int)idx;
}

/* toybox 的 ls/find/du 用 open(O_DIRECTORY)+fdopendir 枚举，必须一并接管，
 * 否则它们看到的是未经合并的原始目录（新建文件不出现、已删文件仍出现）。 */
DIR* fdopendir(int fd) {
    if (!real_fdopendir) { errno = EAGAIN; return NULL; }
    char path[PATH_MAX];
    if (vortex_fd_path(fd, path, sizeof path) == 0) {
        char rel[PATH_MAX];
        int idx = is_intercepted(path, rel, sizeof rel);
        if (idx < 0) idx = vortex_shadow_to_rel(path, rel, sizeof rel);
        /* 只接管「真实存在的目录」；普通文件 fd 交回真实实现以保持 ENOTDIR 语义。 */
        struct stat st;
        if (idx >= 0 && vortex_stat_real(path, &st, 1) == 0 && S_ISDIR(st.st_mode)) {
            DIR* d = vortex_dir_build(idx, rel);
            if (d) { ((struct vortex_dir*)d)->fd = fd; return d; }
        }
    }
    return real_fdopendir(fd);
}

struct dirent* readdir(DIR* dirp) {
    if (!real_readdir) { errno = EAGAIN; return NULL; }
    /* 仅对我们自己的流做合并；其它（含真实返回的非 vortex_dir）交回真实实现。 */
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL || vd->magic != VORTEX_DIR_MAGIC) return real_readdir(dirp);
    if (vd->pos >= vd->count) return NULL;
    const int i = vd->pos++;
    const char* nm = vd->names[i];
    memset(&vd->ent, 0, sizeof vd->ent);
    strncpy(vd->ent.d_name, nm, sizeof vd->ent.d_name - 1);
    vd->ent.d_name[sizeof vd->ent.d_name - 1] = '\0';
    vd->ent.d_type = vd->types ? vd->types[i] : (unsigned char)DT_UNKNOWN;
    vd->ent.d_ino  = (vd->inos && vd->inos[i]) ? vd->inos[i] : vortex_synth_ino(nm);
    vd->ent.d_reclen = (unsigned short)(sizeof vd->ent);
    vd->ent.d_off = vd->pos;
    return &vd->ent;
}

int readdir_r(DIR* dirp, struct dirent* entry, struct dirent** result) {
    if (!real_readdir_r) return vortex_not_ready();
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL || vd->magic != VORTEX_DIR_MAGIC) return real_readdir_r(dirp, entry, result);
    struct dirent* e = readdir(dirp);
    if (!e) { *result = NULL; return 0; }
    *entry = *e;
    *result = entry;
    return 0;
}

int closedir(DIR* dirp) {
    if (!real_closedir) return vortex_not_ready();
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL || vd->magic != VORTEX_DIR_MAGIC) return real_closedir(dirp);
    if (vd->fd >= 0) close(vd->fd);   /* fdopendir 来源：fd 归本流所有，在此关闭 */
    for (int i = 0; i < vd->count; i++) free(vd->names[i]);
    free(vd->names);
    free(vd->types);
    free(vd->inos);
    free(vd);
    return 0;
}

/* dirfd 语义：受控流返回自身持有的 fd（opendir 来源为 -1）；否则交回真实实现。 */
int dirfd(DIR* dirp) {
    if (!real_dirfd) { errno = EAGAIN; return -1; }
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL || vd->magic != VORTEX_DIR_MAGIC) return real_dirfd(dirp);
    return vd->fd;
}

/* 以下三个按 magic 分流：受控目录走我们的流，否则交回真实实现，
 * 避免把自定义结构体当真实 DIR* 写入而崩溃。 */
void rewinddir(DIR* dirp) {
    if (!real_rewinddir) return;
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL || vd->magic != VORTEX_DIR_MAGIC) { real_rewinddir(dirp); return; }
    vd->pos = 0;
}

void seekdir(DIR* dirp, long loc) {
    if (!real_seekdir) return;
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL || vd->magic != VORTEX_DIR_MAGIC) { real_seekdir(dirp, loc); return; }
    if (loc < 0) loc = 0;
    if (loc > vd->count) loc = vd->count;
    vd->pos = (int)loc;
}

long telldir(DIR* dirp) {
    if (!real_telldir) return vortex_not_ready();
    struct vortex_dir* vd = (struct vortex_dir*)dirp;
    if (vd == NULL || vd->magic != VORTEX_DIR_MAGIC) return real_telldir(dirp);
    return (long)vd->pos;
}

/* ---- 构造函数：读取配置 ------------------------------------------------ */

__attribute__((constructor)) static void vortex_init(void) {
    resolve_real();

    /* 伪 ROOT 身份重定向与影子写相互独立：即使影子层未启用（缺 VORTEX_SHADOW_*），
     * root 身份线索也要照常伪造，故先于下面的启用判定读取。 */
    const char* fstat = getenv("VORTEX_FAKE_STATUS");
    const char* fattr = getenv("VORTEX_FAKE_ATTR");
    if (fstat) snprintf(g_fake_status, sizeof g_fake_status, "%s", fstat);
    if (fattr) snprintf(g_fake_attr, sizeof g_fake_attr, "%s", fattr);

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
        if (vortex_stat_real(tok, &st, 1) != 0 || !S_ISDIR(st.st_mode)) continue;
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
