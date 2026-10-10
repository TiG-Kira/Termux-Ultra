#!/system/bin/sh
# ============================================================================
# VorteX Sandbox Bootstrap
# ----------------------------------------------------------------------------
# 在 Termux 用户环境之外提供一层隔离的「测试环境」：
#   - $HOME 被重定向到沙箱可写层（run/home），所有写入均局限于此，不污染真实环境；
#   - 提供虚拟 ROOT：通过 PATH 前置的 id/whoami/su/sudo 包装脚本，让非 ROOT 用户
#     也能以 uid 0 视角执行命令（预演危险脚本 / 正式应用上线前的演练）；
#   - 会话结束（交互模式退出）时自动重置可写层回最小化初始快照。
#
# 用法：
#   vortex_sandbox.sh --interactive   进入交互式沙箱 shell（退出即重置）
#   vortex_sandbox.sh --run "CMD"     在沙箱内执行单条命令（供插件 / Agent 调用）
#   vortex_sandbox.sh                 默认等同 --interactive
#
# 环境变量（由 VorteXSandbox 管理器注入）：
#   VORTEX_ROOT        沙箱根目录（默认 ~/../files/vortex）
#   VORTEX_USE_PROOT   置 1 时优先尝试 proot -0 真实虚拟 root（需已安装 proot）
# ============================================================================

# 沙箱根目录：优先取外部注入，否则回退到应用私有目录
if [ -z "$VORTEX_ROOT" ]; then
    VORTEX_ROOT="$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)"
    [ -z "$VORTEX_ROOT" ] && VORTEX_ROOT="$HOME/.vortex"
fi

SNAP="$VORTEX_ROOT/snapshot/home"
RUN="$VORTEX_ROOT/run/home"
VORTEX_BIN="$RUN/.vortex/bin"

# 确保目录存在
mkdir -p "$SNAP" "$RUN" "$VORTEX_BIN" 2>/dev/null

seed_run() {
    # 仅在可写层为空时从快照复制，避免每次都做全量拷贝
    if [ -z "$(ls -A "$RUN" 2>/dev/null)" ]; then
        cp -a "$SNAP/." "$RUN/" 2>/dev/null || cp -r "$SNAP/." "$RUN/" 2>/dev/null || true
    fi
}

reset_run() {
    # 会话结束：清空可写层并重新播种最小化初始快照
    rm -rf "$RUN"/* 2>/dev/null
    rm -rf "$RUN"/.[!.]* 2>/dev/null
    seed_run
}

setup_vortex() {
    seed_run

    # 重定向用户环境到沙箱可写层
    export HOME="$RUN"
    export VORTEX_SANDBOX=1
    export VORTEX_VIRTUAL_ROOT=1

    # 虚拟 ROOT 包装脚本（PATH 前置，覆盖任意进程对 id/whoami/su/sudo 的调用）
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

    # 可选：若已安装 proot 且显式启用，尝试真实虚拟 root（失败则回退到上面的轻量方案）
    if command -v proot >/dev/null 2>&1 && [ "$VORTEX_USE_PROOT" = "1" ]; then
        exec proot -0 -b "$RUN:/data/data/com.termux/files/home" bash -i
    fi
}

case "$1" in
    --run)
        shift
        CMD="$1"
        setup_vortex
        # 在沙箱内执行命令；可写层在应用生命周期内保留（与插件「无状态命令」模型一致）
        bash -c "$CMD"
        ;;
    --interactive|"")
        setup_vortex
        printf '\n[VorteX Sandbox] 已进入隔离沙箱（虚拟 ROOT，会话结束自动重置）\n'
        printf '[VorteX Sandbox] 所有修改均局限于沙箱，不会写入真实用户环境。\n\n'
        bash -i
        # 交互 shell 退出 → 自动重置
        reset_run
        ;;
    *)
        echo "VorteX Sandbox: unknown argument '$1'" >&2
        exit 1
        ;;
esac
