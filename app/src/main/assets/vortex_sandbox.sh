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
#   3. 提供虚拟 ROOT：proot -0 让所有进程以 uid 0 视角运行；root 身份线索
#      （/proc/self/attr/current=u:r:su:s0、/proc/self/status 的 Uid=0）由影子库按
#      精确路径重定向到伪文件，su/sudo 为容器内模拟器，特权命令绝不触达真实系统。
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
#   VORTEX_FAKE_STATUS       伪 /proc/self/status 文件（内部：本脚本生成后交给影子库）
#   VORTEX_FAKE_ATTR         伪 /proc/self/attr/current 文件（内部）
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
RUN_STORAGE="$TMP/storage"      # 影子内存储（空占位）
# su/sudo 模拟器所在（持久，种在 essential/）。
TOOLS_DIR="$ESSENTIAL"
# LD_PRELOAD 影子写拦截库（由宿主从 APK 解出）。
SHADOW_LIB="${VORTEX_SHADOW_LIB:-$VORTEX_ROOT/lib/libvortex_shadow.so}"

# root 身份伪文件：影子库按精确路径把 /proc/self/status 与 /proc/self/attr/current
# 重定向到这两个普通文件（不再覆盖整个 /proc）。
FAKE_STATUS="$TMP/.vortex_fake_status"
FAKE_ATTR="$TMP/.vortex_fake_attr_current"

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
# 虚拟 ROOT 身份伪造：伪身份文件 + su/sudo 模拟
# ---------------------------------------------------------------------------
# 目标：让沙箱内的脚本确信自己运行在**真实的 Android root 设备**上：
#   1. cat /proc/self/attr/current   → u:r:su:s0（root shell 的 SELinux 上下文）
#   2. /proc/self/status 的 Uid/Gid  → 0
#   3. su / sudo                      → 只在沙箱容器内模拟执行，绝不触达真实特权命令
#
# 原理（旧实现已替换）：**不再**用 proot 把整个 /proc 绑成软链农场。那样会让
# /proc/self/fd、/proc/self/maps、/proc/self/comm 等条目全部变成指向 proot 虚拟路径
# (/dev/.vortex_realproc/...) 的软链；proot 解析软链目标时不套用 bind，必然 ENOENT，
# 于是动态链接器的 readlink("/proc/self/fd/N") 整片失败，沙箱初始化时刷出大量
#   WARNING: linker: unable to get realpath for the library "..."
# 且软链农场快照的是外层 shell 的 pid，cwd/exe/root 会指错进程。
#
# 现在只生成两个普通文件，由影子库（LD_PRELOAD）按**精确路径**重定向：
#   /proc/self/status       → $FAKE_STATUS
#   /proc/self/attr/current → $FAKE_ATTR
# /proc 本体保持真实，其余条目语义完好。

# 在真实 /proc/self/status 基础上把 Uid/Gid/Groups 改写成 0。
fake_status_file() {
    [ -r "$1" ] || return 1
    fs_tmp="$2.tmp.$$"
    : > "$fs_tmp" || return 1
    while IFS= read -r fs_line || [ -n "$fs_line" ]; do
        case "$fs_line" in
            Uid:*)    printf 'Uid:	0	0	0	0
' ;;
            Gid:*)    printf 'Gid:	0	0	0	0
' ;;
            Groups:*) printf 'Groups:	0
' ;;
            *)        printf '%s
' "$fs_line" ;;
        esac
    done < "$1" >> "$fs_tmp" || { rm -f "$fs_tmp"; return 1; }
    mv -f "$fs_tmp" "$2" || { rm -f "$fs_tmp"; return 1; }
    return 0
}

# 幂等生成伪身份文件（会话级，随 tmp/ 一起清空）。
seed_fake_proc() {
    mkdir -p "$TMP" 2>/dev/null || return 1
    fake_status_file /proc/self/status "$FAKE_STATUS" || return 1
    printf 'u:r:su:s0' > "$FAKE_ATTR" || return 1
    return 0
}

# ---------------------------------------------------------------------------
# proot bind 参数构造
# ---------------------------------------------------------------------------
# 关键差异（相对旧实现）：
#   - **不再** `-b $RUN:$REAL_HOME` / `-b $RUN_PREFIX:$REAL_PREFIX`：用户区不再拷贝，
#     而是以链接视图呈现，写入由 LD_PRELOAD 影子化。这样彻底去掉 95MB $PREFIX 拷贝
#     与整份 $HOME 快照，沙箱近乎零磁盘占用。
#   - **不再**把整个 /proc 绑成软链农场：那会让 /proc/self/fd 等条目 ENOENT，动态
#     链接器 readlink("/proc/self/fd/N") 整片失败并刷出 linker 告警。root 身份改由
#     影子库按精确路径重定向（见上方「虚拟 ROOT 身份伪造」）。
#   - su/sudo 模拟器从 essential/（持久）绑定到 /system/bin|/system/xbin|/sbin，
#     即便脚本绕过 PATH 用绝对路径调用，拿到的也是模拟器。
#   - 影子写库经 LD_PRELOAD 注入沙箱子进程（而非 proot 自身），避免干扰 proot 的
#     内部临时文件 / --link2symlink 链接农场。

build_proot_binds() {
    PROOT_BINDS_STR="-b /dev -b /sys -b /system -b /apex"

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
    seed_fake_proc || echo "[VorteX Sandbox] 警告：伪 ROOT 身份文件生成失败，/proc/self 身份伪造将失效。" >&2

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
    VORTEX_FAKE_STATUS="$FAKE_STATUS"
    VORTEX_FAKE_ATTR="$FAKE_ATTR"
    SHADOW_LIB_OK=0
    [ -f "$SHADOW_LIB" ] && SHADOW_LIB_OK=1
    export HOME PREFIX PATH LD_LIBRARY_PATH TMPDIR SHELL TERM LANG
    export VORTEX_SANDBOX VORTEX_VIRTUAL_ROOT VORTEX_ROOT VORTEX_PROOT
    export VORTEX_SHADOW_TMP VORTEX_SHADOW_ROOTS VORTEX_SHADOW_NOTICE VORTEX_SHADOW_NOTIFIED
    export VORTEX_FAKE_STATUS VORTEX_FAKE_ATTR

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
                VORTEX_FAKE_STATUS="$VORTEX_FAKE_STATUS" \
                VORTEX_FAKE_ATTR="$VORTEX_FAKE_ATTR" \
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
    seed_fake_proc
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
        export VORTEX_FAKE_STATUS="$FAKE_STATUS"
        export VORTEX_FAKE_ATTR="$FAKE_ATTR"
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
        echo "fake_proc=$FAKE_STATUS,$FAKE_ATTR (影子库按精确路径重定向 /proc/self)"
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
    rm -rf "$TMP" 2>/dev/null
    rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
    rm -f "$TMP/.vortex_shadowing" "$TMP/.vortex_shadow_notified" 2>/dev/null
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
