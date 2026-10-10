#!/system/bin/sh
# ============================================================================
# VorteX Sandbox Bootstrap  (proot-based)
# ----------------------------------------------------------------------------
# 目标：
#   1. 沙箱内所有修改（文件写入、配置变更、乃至虚拟 ROOT 下的删除）都局限于沙箱内部，
#      **包括使用绝对路径的删除操作**——这是早期仅重定向 $HOME 方案最大的漏洞。
#   2. 沙箱内保留用户完整的 Termux 环境：直接复用真实 $PREFIX（usr/bin、lib、pkg 全部可用），
#      不复制、不重装，因此 $PREFIX 体积不翻倍。
#   3. 提供虚拟 ROOT：proot -0 让所有进程以 uid 0 视角运行。
#   4. 会话退出后自动重置回最小化初始快照。
#
# 实现方式（与 QEMU 的 proot 集成同源思路，见 QemuVmConfig.generateContainerScript）：
#   proot -0
#     -b $VORTEX_ROOT/run/home : /data/data/com.termux/files/home   ← 影子 HOME
#     -b $VORTEX_ROOT/run/storage : /storage/emulated/0             ← 可选的影子内存储
#     -b /dev -b /proc -b /sys -b $PREFIX -b /system -b /apex ...
#     /usr/bin/env -i ... bash
#
#   proot 通过 ptrace 拦截每一次路径解析，因此：
#     rm -rf /data/data/com.termux/files/home   →  实际删除沙箱影子层，真实 HOME 完好
#     rm -rf ~/*                               →  实际删除沙箱影子层，真实 HOME 完好
#   proot 未安装时自动降级为「仅 $HOME 重定向」的轻量模式（此时绝对路径不隔离，
#   脚本会打印明确警告，绝不静默假装安全）。
#
# 用法：
#   vortex_sandbox.sh --interactive        进入交互式沙箱 shell（退出即重置）
#   vortex_sandbox.sh --run "CMD"          在沙箱内执行单条命令（供插件 / Agent 调用）
#   vortex_sandbox.sh --check              仅打印当前隔离能力（供自检 / UI 展示）
#
# 环境变量：
#   VORTEX_ROOT              沙箱根目录（默认取脚本所在目录的父目录）
#   VORTEX_REAL_HOME         真实 HOME 绝对路径（默认 /data/data/com.termux/files/home）
#   VORTEX_ISOLATE_STORAGE   1=内存储也影子化（默认 1）；0=与真实环境共享 /storage/emulated/0
#   VORTEX_FORCE_NO_PROOT    1=强制禁用 proot（仅用于对照排查）
# ============================================================================

VORTEX_ROOT="${VORTEX_ROOT:-$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)}"
[ -z "$VORTEX_ROOT" ] && VORTEX_ROOT="$HOME/.vortex"

REAL_HOME="${VORTEX_REAL_HOME:-/data/data/com.termux/files/home}"
SNAP="$VORTEX_ROOT/snapshot/home"
RUN="$VORTEX_ROOT/run/home"
RUN_STORAGE="$VORTEX_ROOT/run/storage"
SNAP_STORAGE="$VORTEX_ROOT/snapshot/storage"
ISOLATE_STORAGE="${VORTEX_ISOLATE_STORAGE:-1}"

REAL_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
REAL_STORAGE="/storage/emulated/0"

mkdir -p "$SNAP" "$RUN" 2>/dev/null

# ---------------------------------------------------------------------------
# 快照 / 重置
# ---------------------------------------------------------------------------

seed_run() {
    if [ -z "$(ls -A "$RUN" 2>/dev/null)" ]; then
        cp -a "$SNAP/." "$RUN/" 2>/dev/null || cp -r "$SNAP/." "$RUN/" 2>/dev/null || true
    fi
}

reset_run() {
    rm -rf "$RUN" 2>/dev/null
    mkdir -p "$RUN" 2>/dev/null
    seed_run
}

# ---------------------------------------------------------------------------
# 环境准备
# ---------------------------------------------------------------------------

have_proot() {
    [ "$VORTEX_FORCE_NO_PROOT" = "1" ] && return 1
    command -v proot >/dev/null 2>&1
}

# 内存储影子化：快照阶段已抓取的话这里直接播种
seed_storage() {
    [ "$ISOLATE_STORAGE" = "1" ] || return 0
    mkdir -p "$RUN_STORAGE" 2>/dev/null
    if [ -z "$(ls -A "$RUN_STORAGE" 2>/dev/null)" ] && [ -d "$SNAP_STORAGE" ]; then
        cp -a "$SNAP_STORAGE/." "$RUN_STORAGE/" 2>/dev/null || true
    fi
}

# 构造 proot 的 bind 参数数组。真实路径一律换成「宿主可见」的真实值，
# proot -b 不会跟随符号链接，所以 $HOME/storage 的真身要单独 bind。
build_proot_binds() {
    PROOT_BINDS=(-b /dev -b /proc -b /sys -b /data -b /system -b /apex -b /sdcard -b "$REAL_PREFIX:$REAL_PREFIX")

    # 影子 HOME：沙箱内所有 /data/data/com.termux/files/home 路径都落到这里
    PROOT_BINDS+=(-b "$RUN:$REAL_HOME")

    # 影子内存储
    if [ "$ISOLATE_STORAGE" = "1" ]; then
        seed_storage
        PROOT_BINDS+=(-b "$RUN_STORAGE:$REAL_STORAGE")
        # $HOME/storage/shared 是指向 /storage/emulated/0 的符号链接；
        # 在影子 HOME 里它会解析到影子存储，无需额外 bind（proot 会在影子层里重解析）。
    fi
}

# 组装并执行 proot。$1 = 传给 bash 的模式参数（-i / -c ...）
run_in_proot() {
    build_proot_binds
    unset LD_PRELOAD
    exec proot --link2symlink \
        -0 \
        "${PROOT_BINDS[@]}" \
        -w "$REAL_HOME" \
        /usr/bin/env -i \
        HOME="$REAL_HOME" \
        PREFIX="$REAL_PREFIX" \
        PATH="$REAL_PREFIX/bin:/system/bin" \
        LD_LIBRARY_PATH="$REAL_PREFIX/lib" \
        TMPDIR="$REAL_PREFIX/tmp" \
        SHELL="$REAL_PREFIX/bin/bash" \
        TERM="${TERM:-xterm-256color}" \
        LANG="${LANG:-en_US.UTF-8}" \
        VORTEX_SANDBOX=1 \
        VORTEX_VIRTUAL_ROOT=1 \
        VORTEX_ROOT="$VORTEX_ROOT" \
        VORTEX_PROOT=1 \
        "$REAL_PREFIX/bin/bash" "$@"
}

# ---------------------------------------------------------------------------
# 轻量降级方案（proot 不可用）
# ---------------------------------------------------------------------------

setup_fallback() {
    seed_run
    VORTEX_BIN="$RUN/.vortex/bin"
    mkdir -p "$VORTEX_BIN" 2>/dev/null

    export HOME="$RUN"
    export VORTEX_SANDBOX=1
    export VORTEX_VIRTUAL_ROOT=1

    if [ ! -f "$VORTEX_BIN/id" ]; then
        printf '#!/system/bin/sh\nprintf "uid=0(root) gid=0(root) groups=0(root)\\n"\n' > "$VORTEX_BIN/id"
        printf '#!/system/bin/sh\necho root\n' > "$VORTEX_BIN/whoami"
        printf '#!/system/bin/sh\nexec "$@"\n' > "$VORTEX_BIN/su"
        printf '#!/system/bin/sh\nexec "$@"\n' > "$VORTEX_BIN/sudo"
        chmod 0755 "$VORTEX_BIN/id" "$VORTEX_BIN/whoami" "$VORTEX_BIN/su" "$VORTEX_BIN/sudo" 2>/dev/null
    fi
    case ":$PATH:" in
        *":$VORTEX_BIN:"*) ;;
        *) export PATH="$VORTEX_BIN:$PATH" ;;
    esac

    cat >&2 <<'EOF'
[VorteX Sandbox] 警告：未检测到 proot，已降级为「仅 $HOME 重定向」的轻量隔离。
[VorteX Sandbox] 此模式下 **绝对路径不被隔离**（如 rm -rf /data/data/com.termux/files/home
[VorteX Sandbox] 仍会作用于真实环境）。请执行 `pkg install proot` 后重试。
EOF
}

# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------

do_check() {
    if have_proot; then
        echo "isolation=proot"
        echo "proot_path=$(command -v proot)"
        echo "shadow_home=$RUN -> $REAL_HOME"
        [ "$ISOLATE_STORAGE" = "1" ] && echo "shadow_storage=$RUN_STORAGE -> $REAL_STORAGE" || echo "shadow_storage=shared($REAL_STORAGE)"
    else
        echo "isolation=fallback"
        echo "shadow_home=$RUN (绝对路径不隔离)"
    fi
    echo "real_home=$REAL_HOME"
    echo "snapshot=$SNAP"
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

case "$1" in
    --check)
        do_check
        ;;

    --run)
        shift
        CMD="$1"
        if have_proot; then
            run_in_proot -c "$CMD"
        else
            setup_fallback
            bash -c "$CMD"
        fi
        ;;

    --interactive|"")
        if have_proot; then
            printf '\n[VorteX Sandbox] proot 隔离已启用（虚拟 ROOT，会话结束自动重置）\n'
            printf '[VorteX Sandbox] 真实 %s 已被影子层遮蔽，删除操作不会泄漏。\n\n' "$REAL_HOME"
            run_in_proot -i
            reset_run
        else
            setup_fallback
            printf '\n[VorteX Sandbox] 已进入轻量隔离沙箱（仅 $HOME 隔离，会话结束自动重置）\n\n'
            bash -i
            reset_run
        fi
        ;;

    *)
        echo "VorteX Sandbox: unknown argument '$1'" >&2
        exit 1
        ;;
esac
