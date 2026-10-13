#!/system/bin/sh
# ============================================================================
# VorteX Sandbox Bootstrap  (proot + LD_PRELOAD 影子写)
# ----------------------------------------------------------------------------
# 目标：
#   1. 常规目录（真实 $HOME、$PREFIX）以**链接视图**呈现：读取走真实文件，但一切写入
#      被 LD_PRELOAD 拦截、copy-up 到临时存储区后虚拟写——写调用返回成功，真实底层
#      数据不变（影子写入机制）。这正是 overlayfs 的 copy-up / whiteout 语义在
#      用户态的等价实现（无 root 的 Android 无法挂 overlayfs）。
#   2. 沙箱内保留用户完整的 Termux 环境：直接复用真实 $PREFIX、$HOME，不复制、不重装，
#      因此沙箱近乎零磁盘占用（旧实现要拷 95MB $PREFIX + 整份 $HOME 快照）。
#   3. 提供虚拟 ROOT：proot -0 让所有进程以 uid 0 视角运行，并伪造 /proc/self
#      （SELinux 上下文 u:r:su:s0、status Uid=0）与 su/sudo 模拟器，让脚本确信运行在
#      真实 root 环境中，而特权命令只在沙箱容器内模拟执行。
#   4. 「必要更改区」(essential/) 持久化：仅含 su/sudo 模拟器与伪装 ROOT 所需的文件
#      篡改组件，由本脚本幂等播种，重启沙箱后仍在。
#   5. 「临时存储区」(tmp/) 易失：影子写副本、删除 whiteout、运行时 /proc 影子、导入
#      样本都落于此；会话退出（EXIT trap）整体清空，一切修改即消失。
#
# 最小快照拆分（用户需求落点）：
#   - essential/  ← 持久，仅 su/sudo 及篡改组件（必要更改区）
#   - tmp/        ← 易失，影子写 / whiteout / 运行时 / 导入（临时存储区）
#   - 用户区（$HOME / $PREFIX）← 链接视图，写入被影子化
#
# 用法：
#   vortex_sandbox.sh --interactive        进入交互式沙箱 shell（退出即重置）
#   vortex_sandbox.sh --run "CMD"          在沙箱内执行单条命令（供插件 / Agent 调用）
#   vortex_sandbox.sh --check              仅打印当前隔离能力（供自检 / UI 展示）
#
# 环境变量：
#   VORTEX_ROOT              沙箱根目录（默认取脚本所在目录的父目录）
#   VORTEX_REAL_HOME         真实 HOME 绝对路径（默认 /data/data/com.termux/files/home）
#   VORTEX_REAL_PREFIX       真实 PREFIX 绝对路径（默认 /data/data/com.termux/files/usr）
#   VORTEX_ISOLATE_STORAGE   1=内存储也影子化（默认 1）；0=与真实环境共享 /storage/emulated/0
#   VORTEX_FORCE_NO_PROOT    1=强制禁用 proot（仅用于对照排查）
# ============================================================================

VORTEX_ROOT="${VORTEX_ROOT:-$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)}"
[ -z "$VORTEX_ROOT" ] && VORTEX_ROOT="$HOME/.vortex"

REAL_HOME="${VORTEX_REAL_HOME:-/data/data/com.termux/files/home}"
REAL_PREFIX="${VORTEX_REAL_PREFIX:-/data/data/com.termux/files/usr}"
REAL_STORAGE="/storage/emulated/0"

# 「必要更改区」：持久化，仅装 su/sudo 模拟器与伪装 ROOT 组件。
ESSENTIAL="$VORTEX_ROOT/essential"
# 「临时存储区」：会话级、易失。影子写副本 / whiteout / 运行时 /proc / 导入样本。
TMP="$VORTEX_ROOT/tmp"
RUN_PROC="$TMP/proc"            # 影子 /proc（虚拟 ROOT 身份伪造，运行时生成）
RUN_STORAGE="$TMP/storage"      # 影子内存储（空占位）
# su/sudo 模拟器所在（持久，种在 essential/）。
TOOLS_DIR="$ESSENTIAL"
# LD_PRELOAD 影子写拦截库（由宿主从 APK 解出）。
SHADOW_LIB="${VORTEX_SHADOW_LIB:-$VORTEX_ROOT/lib/libvortex_shadow.so}"

PROCWATCH_PIDFILE="$VORTEX_ROOT/.vortex_procwatch_pid"
PROCWATCH_PENDING_K=16
REAL_PROC_ALIAS="/dev/.vortex_realproc"

# 手动会话存活标记：--interactive 启动时创建，退出回收时摘除。
# 一次性运行（--run）与手动会话共用 tmp/ 和 procwatch：其 EXIT trap 见到本标记即
# 只清哨兵、不碰 tmp/ 与 watcher——否则会把活会话的 /proc 影子连锅端。
ACTIVE_SESSION_MARKER="$TMP/.vortex_active_session"
VORTEX_SESSION_OWNER=0

ISOLATE_STORAGE="${VORTEX_ISOLATE_STORAGE:-1}"

# 关键：**必须自建 PATH**。会话由 libterminal 直接 exec 本脚本，继承的 PATH 可能为空或
# 不含 $PREFIX/bin，此时 `command -v proot` 会找不到 proot → 静默降级成弱隔离（危险）。
# 所以这里无条件把 $PREFIX/bin 放到最前，保证 proot 探测可靠。同时把 essential/ 前置，
# 让 `su` / `sudo` 优先解析到沙箱模拟器（即便脚本用相对 PATH 调用）。
case ":$PATH:" in
    *":$REAL_PREFIX/bin:"*) ;;
    *) PATH="$REAL_PREFIX/bin:$PATH" ;;
esac
case ":$PATH:" in
    *":$ESSENTIAL:"*) ;;
    *) PATH="$ESSENTIAL:$PATH" ;;
esac
export PATH

mkdir -p "$ESSENTIAL" "$TMP" 2>/dev/null

NOTICE_FILE="$VORTEX_ROOT/.vortex_storage_notice"

# ---------------------------------------------------------------------------
# 环境准备
# ---------------------------------------------------------------------------

have_proot() {
    [ "$VORTEX_FORCE_NO_PROOT" = "1" ] && return 1
    command -v proot >/dev/null 2>&1
}

# 幂等播种「必要更改区」：仅生成 su/sudo 模拟器脚本（持久）。
# 与旧实现不同，这里**不拷贝任何用户数据**，因此瞬时完成、且跨会话保留。
seed_essential() {
    mkdir -p "$ESSENTIAL" 2>/dev/null || return 1
    [ -x "$ESSENTIAL/su" ] && [ -x "$ESSENTIAL/sudo" ] && return 0

    cat > "$ESSENTIAL/su" <<'VORTEX_EOF'
#!/system/bin/sh
# VorteX 沙箱 su 模拟器：只在沙箱容器内执行，绝不调用真实 su。
vc_cmd=""
while [ $# -gt 0 ]; do
    case "$1" in
        -c) vc_cmd="$2"; shift 2 ;;
        -*) shift ;;
        *) break ;;
    esac
done
if [ -n "$vc_cmd" ]; then
    exec "${SHELL:-/system/bin/sh}" -c "$vc_cmd"
fi
if [ $# -gt 0 ] && ! command -v "$1" >/dev/null 2>&1; then
    shift
fi
if [ $# -gt 0 ]; then
    "$@"
    exit $?
fi
exec "${SHELL:-/system/bin/sh}" -i
VORTEX_EOF

    cat > "$ESSENTIAL/sudo" <<'VORTEX_EOF'
#!/system/bin/sh
# VorteX 沙箱 sudo 模拟器：只在沙箱容器内执行，绝不调用真实 sudo。
vc_login=0
vc_shell=0
while [ $# -gt 0 ]; do
    case "$1" in
        -u|-g) shift 2 ;;
        -i) vc_login=1; shift ;;
        -s) vc_shell=1; shift ;;
        -V|--version)
            echo "VorteX sandbox sudo (simulated) 1.0"
            exit 0 ;;
        -h|--help)
            echo "usage: sudo [-u user] [-g group] [-i|-s] command [args]"
            exit 0 ;;
        -n|-E|-H|-k|-K|-b|-S) shift ;;
        --) shift; break ;;
        -*) shift ;;
        *) break ;;
    esac
done
if [ $# -eq 0 ]; then
    if [ "$vc_login" = 1 ] || [ "$vc_shell" = 1 ]; then
        exec "${SHELL:-/system/bin/sh}" -i
    fi
    exit 0
fi
"$@"
exit $?
VORTEX_EOF

    chmod 0755 "$ESSENTIAL/su" "$ESSENTIAL/sudo" 2>/dev/null || return 1
    return 0
}

# 内存储重定位：把真实内存储路径 bind 到一个**空临时目录**。
#
# 沙箱的定位是「用户 Termux 环境的隔离预演」，内存储不在其列；且需求明确要求
# 沙箱内坚决不允许访问 Android 内部存储。bind 到空目录：路径可见、内容为空，
# 用户的任何访问都读不到东西，同时写哨兵由宿主侧转成 Snackbar 告知原因。
seed_storage() {
    [ "$ISOLATE_STORAGE" = "1" ] || return 0
    mkdir -p "$RUN_STORAGE" 2>/dev/null
    notice_storage_blocked
}

notice_storage_blocked() {
    [ -f "$VORTEX_ROOT/.vortex_storage_notified" ] && return 0
    echo "blocked" > "$NOTICE_FILE" 2>/dev/null || true
    : > "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 虚拟 ROOT 身份伪造：影子 /proc + su/sudo 模拟
# ---------------------------------------------------------------------------
# 目标：让沙箱内的脚本确信自己运行在**真实的 Android root 设备**上：
#   1. cat /proc/self/attr/current   → u:r:su:s0（root shell 的 SELinux 上下文）
#   2. /proc/self/status 的 Uid/Gid  → 0
#   3. su / sudo                      → 只在沙箱容器内模拟执行，绝不触达真实特权命令
#
# 原理：proot 的路径解析逐组件进行，一旦某前缀命中绑定，剩余组件直接落到绑定目标上。
# 因此整体把 /proc 绑到影子目录 $RUN_PROC 后，影子内预置伪造的 self / thread-self，
# 其余条目软链到真实 /proc 的进程别名 $REAL_PROC_ALIAS；显式 /proc/<pid> 由后台 watcher
# 持续补建。非本应用 UID 的进程保持真实软链，避免把别人的进程伪装成 root 而露馅。
#
# 降级：影子 /proc 建不起来时退回真实 -b /proc（身份伪造失效但隔离不受影响）。

fake_status_file() {
    [ -r "$1" ] || return 1
    fs_tmp="$2.tmp.$$"
    : > "$fs_tmp" || return 1
    while IFS= read -r fs_line || [ -n "$fs_line" ]; do
        case "$fs_line" in
            Uid:*)    printf 'Uid:\t0\t0\t0\t0\n' ;;
            Gid:*)    printf 'Gid:\t0\t0\t0\t0\n' ;;
            Groups:*) printf 'Groups:\t0\n' ;;
            *)        printf '%s\n' "$fs_line" ;;
        esac
    done < "$1" >> "$fs_tmp" || { rm -f "$fs_tmp"; return 1; }
    mv -f "$fs_tmp" "$2" || { rm -f "$fs_tmp"; return 1; }
    return 0
}

write_fake_attr() {
    mkdir -p "$1" 2>/dev/null || return 1
    printf 'u:r:su:s0' > "$1/current" || return 1
    for wa_f in exec fscreate keycreate prev sockcreate lsm; do
        [ -e "$1/$wa_f" ] || : > "$1/$wa_f" 2>/dev/null
    done
    return 0
}

shadow_pid_view() {
    sv_d="$1"
    sv_src="$2"
    sv_suffix="${sv_src#/proc}"
    mkdir -p "$sv_d" 2>/dev/null || return 1
    write_fake_attr "$sv_d/attr" || return 1
    fake_status_file "$sv_src/status" "$sv_d/status" || rm -f "$sv_d/status"
    for sv_n in cwd exe root; do
        [ -e "$sv_src/$sv_n" ] || [ -L "$sv_src/$sv_n" ] || continue
        sv_t=$(readlink "$sv_src/$sv_n" 2>/dev/null)
        [ -n "$sv_t" ] && ln -sfn "$sv_t" "$sv_d/$sv_n" 2>/dev/null
    done
    for sv_e in "$sv_src"/*; do
        [ -e "$sv_e" ] || [ -L "$sv_e" ] || continue
        sv_n="${sv_e##*/}"
        case "$sv_n" in attr|status|cwd|exe|root) continue ;; esac
        ln -sfn "$REAL_PROC_ALIAS$sv_suffix/$sv_n" "$sv_d/$sv_n" 2>/dev/null
    done
    return 0
}

make_pending_view() {
    mv_d="$1"
    mv_pid="$2"
    mkdir -p "$mv_d/attr" 2>/dev/null || return 1
    write_fake_attr "$mv_d/attr" || return 1
    fake_status_file /proc/self/status "$mv_d/status" 2>/dev/null || rm -f "$mv_d/status"
    for mv_e in /proc/self/*; do
        [ -e "$mv_e" ] || [ -L "$mv_e" ] || continue
        mv_n="${mv_e##*/}"
        case "$mv_n" in attr|status) continue ;; esac
        ln -sfn "$REAL_PROC_ALIAS/$mv_pid/$mv_n" "$mv_d/$mv_n" 2>/dev/null
    done
    return 0
}

read_real_uid() {
    while IFS= read -r ru_line; do
        case "$ru_line" in
            Uid:*) set -- $ru_line; printf '%s' "$2"; return 0 ;;
        esac
    done < "$1" 2>/dev/null
    return 1
}

add_pending_views() {
    ap_max="$1"
    ap_pm=$(cat /proc/sys/kernel/pid_max 2>/dev/null)
    case "$ap_pm" in ''|*[!0-9]*) ap_pm=32768 ;; esac
    ap_n=0
    while [ "$ap_n" -lt "$PROCWATCH_PENDING_K" ] && [ "$ap_max" -lt "$ap_pm" ]; do
        ap_max=$((ap_max + 1))
        ap_n=$((ap_n + 1))
        ap_d="$RUN_PROC/$ap_max"
        [ -e "$ap_d" ] || [ -L "$ap_d" ] && continue
        make_pending_view "$ap_d" "$ap_max" || return 1
        sc_pending="$sc_pending $ap_max "
    done
    return 0
}

sc_pending=""
procwatch_scan() {
    sc_max=0
    for sc_e in /proc/[0-9]*; do
        [ -d "$sc_e" ] || continue
        sc_pid="${sc_e##*/}"
        case "$sc_pid" in *[!0-9]*) continue ;; esac
        [ "$sc_pid" -gt "$sc_max" ] 2>/dev/null && sc_max=$sc_pid
        sc_d="$RUN_PROC/$sc_pid"

        if [ -d "$sc_d" ]; then
            case " $sc_pending " in
                *" $sc_pid "*)
                    sc_uid=$(read_real_uid "$sc_e/status")
                    if [ "$sc_uid" = "$SC_OWN_UID" ]; then
                        shadow_pid_view "$sc_d" "$sc_e"
                    else
                        rm -rf "$sc_d" 2>/dev/null
                        ln -sfn "$REAL_PROC_ALIAS/$sc_pid" "$sc_d" 2>/dev/null
                    fi
                    sc_pending=$(printf '%s' "$sc_pending" | sed "s/ $sc_pid / /g")
                    ;;
            esac
            continue
        fi
        if [ -L "$sc_d" ]; then
            continue
        fi

        sc_uid=$(read_real_uid "$sc_e/status")
        if [ "$sc_uid" = "$SC_OWN_UID" ]; then
            shadow_pid_view "$sc_d" "$sc_e"
        else
            ln -sfn "$REAL_PROC_ALIAS/$sc_pid" "$sc_d" 2>/dev/null
        fi
    done

    for sc_d in "$RUN_PROC"/[0-9]*; do
        [ -e "$sc_d" ] || [ -L "$sc_d" ] || continue
        sc_n="${sc_d##*/}"
        [ -d "/proc/$sc_n" ] && continue
        case " $sc_pending " in *" $sc_n "*) continue ;; esac
        rm -rf "$sc_d" 2>/dev/null
    done

    add_pending_views "$sc_max"
    return 0
}

procwatch_loop() {
    while :; do
        [ -f "$PROCWATCH_PIDFILE" ] || exit 0
        [ -d "$RUN_PROC" ] || exit 0
        procwatch_scan
        sleep 0.2
    done
}

start_procwatch() {
    if [ -f "$PROCWATCH_PIDFILE" ]; then
        kill_procwatch_pid "$(cat "$PROCWATCH_PIDFILE" 2>/dev/null)"
        rm -f "$PROCWATCH_PIDFILE"
    fi
    : > "$PROCWATCH_PIDFILE" 2>/dev/null || return 1
    ( procwatch_loop ) >/dev/null 2>&1 &
    echo $! > "$PROCWATCH_PIDFILE" 2>/dev/null
    return 0
}

stop_procwatch() {
    if [ -f "$PROCWATCH_PIDFILE" ]; then
        kill_procwatch_pid "$(cat "$PROCWATCH_PIDFILE" 2>/dev/null)"
        rm -f "$PROCWATCH_PIDFILE"
    fi
    return 0
}

# 只杀「确实是本脚本 procwatch」的进程：pidfile 里的 PID 可能已被系统回收给无关
# 进程（其他会话的 shell 等），无身份校验的 kill 会误杀——曾致正常会话崩坏。
# 校验不通过时仅删 pidfile：旧 watcher 每轮自检 pidfile 消失即自行退场（≤0.2s）。
kill_procwatch_pid() {
    [ -n "$1" ] || return 0
    [ "$1" = "$$" ] && return 0
    grep -qa "vortex_sandbox" "/proc/$1/cmdline" 2>/dev/null || return 0
    kill "$1" 2>/dev/null
    return 0
}

seed_shadow_proc() {
    mkdir -p "$RUN_PROC" 2>/dev/null || return 1
    SC_OWN_UID=$(read_real_uid /proc/self/status) || SC_OWN_UID=0
    shadow_pid_view "$RUN_PROC/self" "/proc/self" || return 1
    shadow_pid_view "$RUN_PROC/thread-self" "/proc/self" || return 1
    sc_pending=""
    procwatch_scan || return 1
    start_procwatch || return 1
    return 0
}

# ---------------------------------------------------------------------------
# proot bind 参数构造
# ---------------------------------------------------------------------------
# 关键差异（相对旧实现）：
#   - **不再** `-b $RUN:$REAL_HOME` / `-b $RUN_PREFIX:$REAL_PREFIX`：用户区不再拷贝，
#     而是以链接视图呈现，写入由 LD_PRELOAD 影子化。这样彻底去掉 95MB $PREFIX 拷贝
#     与整份 $HOME 快照，沙箱近乎零磁盘占用。
#   - su/sudo 模拟器从 essential/（持久）绑定到 /system/bin|/system/xbin|/sbin，
#     即便脚本绕过 PATH 用绝对路径调用，拿到的也是模拟器。
#   - 影子写库经 LD_PRELOAD 注入沙箱子进程（而非 proot 自身），避免干扰 proot 的
#     内部临时文件 / --link2symlink 链接农场。

build_proot_binds() {
    PROOT_BINDS_STR="-b /dev -b /sys -b /system -b /apex"

    if seed_shadow_proc; then
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $RUN_PROC:/proc -b /proc:$REAL_PROC_ALIAS"
    else
        echo "[VorteX Sandbox] 警告：影子 /proc 不可用，/proc/self 身份伪造已停用。" >&2
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b /proc"
    fi

    if [ "$ISOLATE_STORAGE" = "1" ]; then
        seed_storage
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $RUN_STORAGE:$REAL_STORAGE"
    fi

    if seed_essential; then
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $ESSENTIAL/su:/system/bin/su -b $ESSENTIAL/su:/system/xbin/su -b $ESSENTIAL/su:/sbin/su"
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $ESSENTIAL/sudo:/system/bin/sudo -b $ESSENTIAL/sudo:/system/xbin/sudo -b $ESSENTIAL/sudo:/sbin/sudo"
    else
        echo "[VorteX Sandbox] 警告：必要更改区播种失败，su/sudo 模拟器将不可用。" >&2
    fi
}

# 组装并执行 proot。$1 = 传给 bash 的模式参数（-i / -c ...）
run_in_proot() {
    build_proot_binds

    _host_path="$PATH"
    _proot_bin=$(command -v proot 2>/dev/null)
    [ -n "$_proot_bin" ] || _proot_bin=proot
    HOME="$REAL_HOME"
    PREFIX="$REAL_PREFIX"
    PATH="$ESSENTIAL:$REAL_PREFIX/bin:/system/bin"
    LD_LIBRARY_PATH="$REAL_PREFIX/lib"
    TMPDIR="$REAL_PREFIX/tmp"
    SHELL="$REAL_PREFIX/bin/bash"
    TERM="${TERM:-xterm-256color}"
    LANG="${LANG:-en_US.UTF-8}"
    VORTEX_SANDBOX=1
    VORTEX_VIRTUAL_ROOT=1
    VORTEX_ROOT="$VORTEX_ROOT"
    VORTEX_PROOT=1
    # 影子写所需的全部配置：根目录（真实 $HOME、$PREFIX）、临时存储区、哨兵路径。
    # 仅注入到沙箱子进程（经 env 命令），proot 自身不加载该库，以免其内部临时文件
    # 与 --link2symlink 链接农场被误影子化。
    VORTEX_SHADOW_TMP="$TMP"
    VORTEX_SHADOW_ROOTS="$REAL_HOME:$REAL_PREFIX"
    VORTEX_SHADOW_NOTICE="$TMP/.vortex_shadowing"
    VORTEX_SHADOW_NOTIFIED="$TMP/.vortex_shadow_notified"
    SHADOW_LIB_OK=0
    [ -f "$SHADOW_LIB" ] && SHADOW_LIB_OK=1
    export HOME PREFIX PATH LD_LIBRARY_PATH TMPDIR SHELL TERM LANG
    export VORTEX_SANDBOX VORTEX_VIRTUAL_ROOT VORTEX_ROOT VORTEX_PROOT
    export VORTEX_SHADOW_TMP VORTEX_SHADOW_ROOTS VORTEX_SHADOW_NOTICE VORTEX_SHADOW_NOTIFIED

    _popt_link2symlink=""
    if "$_proot_bin" --link2symlink --version >/dev/null 2>&1; then
        _popt_link2symlink="--link2symlink"
    fi

    if [ "$SHADOW_LIB_OK" = "1" ]; then
        # 子进程经 env 继承 LD_PRELOAD 与影子写配置；proot 本身不加载（避免干扰）。
        "$_proot_bin" $_popt_link2symlink \
            -0 \
            $PROOT_BINDS_STR \
            -w "$REAL_HOME" \
            "$REAL_PREFIX/bin/env" LD_PRELOAD="$SHADOW_LIB" \
                VORTEX_SHADOW_TMP="$VORTEX_SHADOW_TMP" \
                VORTEX_SHADOW_ROOTS="$VORTEX_SHADOW_ROOTS" \
                VORTEX_SHADOW_NOTICE="$VORTEX_SHADOW_NOTICE" \
                VORTEX_SHADOW_NOTIFIED="$VORTEX_SHADOW_NOTIFIED" \
                "$REAL_PREFIX/bin/bash" "$@"
    else
        echo "[VorteX Sandbox] 警告：影子写库缺失，写入将直接落到真实文件（隔离失效）。" >&2
        "$_proot_bin" $_popt_link2symlink \
            -0 \
            $PROOT_BINDS_STR \
            -w "$REAL_HOME" \
            "$REAL_PREFIX/bin/bash" "$@"
    fi
    _vortex_rc=$?
    PATH="$_host_path"
    export PATH
    return $_vortex_rc
}

# ---------------------------------------------------------------------------
# 轻量降级方案（proot 不可用）：仍注入 LD_PRELOAD 影子写
# ---------------------------------------------------------------------------
# 无 proot 时**绝对路径不被屏蔽**（无法靠 ptrace 兜住 rm -rf 真实路径），但 LD_PRELOAD
# 影子写依旧生效：对受控目录的写入仍被 copy-up / 白化，返回成功而不落盘。

setup_fallback() {
    seed_essential
    export HOME="$REAL_HOME"
    export VORTEX_SANDBOX=1
    export VORTEX_VIRTUAL_ROOT=1

    case ":$PATH:" in
        *":$ESSENTIAL:"*) ;;
        *) export PATH="$ESSENTIAL:$PATH" ;;
    esac

    if [ -f "$SHADOW_LIB" ]; then
        export LD_PRELOAD="$SHADOW_LIB"
        export VORTEX_SHADOW_TMP="$TMP"
        export VORTEX_SHADOW_ROOTS="$REAL_HOME:$REAL_PREFIX"
        export VORTEX_SHADOW_NOTICE="$TMP/.vortex_shadowing"
        export VORTEX_SHADOW_NOTIFIED="$TMP/.vortex_shadow_notified"
    else
        echo "[VorteX Sandbox] 警告：影子写库缺失，轻量模式下写入将落到真实文件。" >&2
    fi

    cat >&2 <<'EOF'
[VorteX Sandbox] 警告：未检测到 proot，已降级为「仅 LD_PRELOAD 影子写」的轻量隔离。
[VorteX Sandbox] 此模式下 **绝对路径不被 ptrace 屏蔽**；请执行 `pkg install proot` 后重试
[VorteX Sandbox] 以获得完整隔离。受控目录（$HOME / $PREFIX）内的写入仍会被影子化。
EOF
}

# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------

do_check() {
    if have_proot; then
        echo "isolation=proot+shadow"
        echo "shadow_home=$REAL_HOME (链接视图，写入被影子化到 $TMP)"
        echo "shadow_prefix=$REAL_PREFIX (链接视图，写入被影子化到 $TMP)"
        echo "essential=$ESSENTIAL (持久：su/sudo 模拟器)"
        echo "shadow_proc=$RUN_PROC (attr/current=u:r:su:s0, status Uid=0)"
        echo "su_sudo=simulated (容器内模拟执行，不调用真实特权命令)"
        echo "shadow_lib=${SHADOW_LIB_OK:-unknown}"
    else
        echo "isolation=fallback+shadow"
        echo "shadow_home=$REAL_HOME (绝对路径不屏蔽，仅 LD_PRELOAD 影子写)"
        echo "su_sudo=simulated (轻量模式只模拟、不执行)"
    fi
    echo "real_home=$REAL_HOME"
    echo "essential=$ESSENTIAL"
    [ "$ISOLATE_STORAGE" = "1" ] && echo "shadow_storage=$RUN_STORAGE -> $REAL_STORAGE" || echo "shadow_storage=shared($REAL_STORAGE)"
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
# 无论正常退出、命令失败还是被信号打断，都必须清掉**临时存储区**（tmp/）。
# 「必要更改区」(essential/) 持久保留——它只装 su/sudo 等伪装 ROOT 组件。
# 用 trap 而非在分支里手写 —— 后者在 exec / 信号路径上会漏掉。
trap 'reset_run' EXIT INT TERM

reset_run() {
    if [ "$VORTEX_SESSION_OWNER" = "1" ]; then
        # 手动会话退出：摘自己的标记，走完整回收。
        rm -f "$ACTIVE_SESSION_MARKER" 2>/dev/null
        stop_procwatch
        rm -rf "$TMP" 2>/dev/null
        rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
        rm -f "$TMP/.vortex_shadowing" "$TMP/.vortex_shadow_notified" 2>/dev/null
    elif [ -f "$ACTIVE_SESSION_MARKER" ]; then
        # 一次性运行退出，但手动会话还活着：tmp/ 与 procwatch 是它的运行时，不能动。
        rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
        rm -f "$TMP/.vortex_shadowing" "$TMP/.vortex_shadow_notified" 2>/dev/null
    else
        stop_procwatch
        rm -rf "$TMP" 2>/dev/null
        rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
        rm -f "$TMP/.vortex_shadowing" "$TMP/.vortex_shadow_notified" 2>/dev/null
    fi
}

case "$1" in
    --check)
        trap - EXIT INT TERM
        do_check
        ;;

    --run)
        shift
        CMD="$1"
        if have_proot; then
            run_in_proot -c "$CMD"
        else
            setup_fallback
            "$REAL_PREFIX/bin/bash" -c "$CMD"
        fi
        ;;

    --interactive|"")
        # 标记本进程为手动会话所有者：退出时完整回收；其间到来的一次性运行
        # （--run）见到标记则只清哨兵，不碰共享的 tmp/ 与 procwatch。
        : > "$ACTIVE_SESSION_MARKER" 2>/dev/null
        VORTEX_SESSION_OWNER=1
        if have_proot; then
            printf '\n[VorteX Sandbox] proot 隔离 + LD_PRELOAD 影子写已启用（虚拟 ROOT，会话结束自动清空）\n'
            printf '[VorteX Sandbox] 真实 %s 以链接视图呈现：写入被影子化到临时存储区，底层数据不变。\n' "$REAL_HOME"
            printf '[VorteX Sandbox] 身份伪造已启用：/proc/self/attr/current=u:r:su:s0，uid=0；\n'
            printf '[VorteX Sandbox] su/sudo 为沙箱内模拟执行（持久于 essential/），不调用真实特权命令。\n'
            printf '[VorteX Sandbox] 本会话的所有改动在退出时彻底消失，临时存储区 (tmp/) 被删除。\n\n'
            run_in_proot -i
        else
            setup_fallback
            printf '\n[VorteX Sandbox] 已进入轻量隔离沙箱（仅 LD_PRELOAD 影子写）\n\n'
            "$REAL_PREFIX/bin/bash" -i
        fi
        ;;

    *)
        echo "VorteX Sandbox: unknown argument '$1'" >&2
        exit 1
        ;;
esac
