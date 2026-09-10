#!/system/bin/sh
# ============================================================================
# Termux Advanced Security Hook
#
# 设计（v3）：
#   - source 本脚本时【立即注册 trap DEBUG】，但绝不碰用户的 PROMPT_COMMAND
#     → oh-my-bash 等依赖 PROMPT_COMMAND 的主题/插件完全不受影响
#   - __TAS_READY 标志区分阶段：
#       source 初始化阶段（__TAS_READY 未设置）→ DEBUG trap 立即 return 0，
#       零开销，不干扰 .bashrc / oh-my-bash / 主题加载
#       交互阶段（__TAS_READY=1）→ 只对【本地模式匹配的疑似危险/脚本命令】
#       做 TCP 检测，其余命令零开销直接放行 → 不卡、不拖慢终端
#   - 脚本执行（bash x.sh / ./x.sh）→ CHECK_SCRIPT（Agent 判定，最长等 35s）
#   - 危险命令（su / rm -rf 等）→ CHECK_CMD（静态匹配，3s 超时）
#   - 任何 TCP 异常一律 ALLOW，绝不阻断 shell
# ============================================================================

if [ -n "$__TAS_LOADED" ]; then
    return 0 2>/dev/null || exit 0
fi
__TAS_LOADED=1

PORT_FILE="${TERMUX_SECURITY_PORT_FILE:-/data/data/com.termux/files/sock/termux-security.port}"

# 只在交互 shell 打印日志
tas_log() {
    case "$-" in
        *i*) printf '[TAS] %s\n' "$*" >&2 ;;
    esac
}

tas_log "Loading Termux Advanced Security Module..."

# ---------------------------------------------------------------------------
# TCP 通信：CHECK_CMD 用 3s 超时；CHECK_SCRIPT（Agent 判定）用 35s 超时
# 任何异常一律 ALLOW，绝不阻断 shell
# ---------------------------------------------------------------------------
tas_call() {
    local method="$1"; shift
    local port
    port=$(cat "$PORT_FILE" 2>/dev/null) || { TAS_RESULT="ALLOW"; return 0; }
    [ -z "$port" ] && { TAS_RESULT="ALLOW"; return 0; }

    local req="$method"
    local line
    for line in "$@"; do req="${req}"$'\n'"${line}"; done
    req="${req}"$'\n'"END"$'\n'

    local timeout=3
    [ "$method" = "CHECK_SCRIPT" ] && timeout=35

    local resp=""
    local done=0
    TAS_REASON=""
    TAS_ERROR=""
    if [ -n "$BASH_VERSION" ]; then
        # 注意：不能先 exec 3>&- 关 fd！<> 打开的 fd 关掉后无法读响应
        if exec 3<>/dev/tcp/127.0.0.1/$port 2>/dev/null; then
            printf '%s' "$req" >&3
            # read -t 有读超时；行数上限保证任何情况下都会终止
            local count=0
            while [ $count -lt 10 ] && IFS= read -t "$timeout" -r line <&3; do
                count=$((count+1))
                resp="${resp}"$'\n'"${line}"
                if [ "$line" = "END" ]; then done=1; break; fi
            done
            exec 3<&- 2>/dev/null
        fi
    elif command -v nc >/dev/null 2>&1; then
        resp=$(printf '%s' "$req" | nc -w "$timeout" 127.0.0.1 "$port" 2>/dev/null) || resp=""
        case "$resp" in
            *"END"*) done=1 ;;
        esac
    fi

    # 没读到完整 END 响应（超时/连接失败/服务端异常）→ 一律 ALLOW，绝不卡住 shell
    if [ "$done" -ne 1 ]; then TAS_RESULT="ALLOW"; return 0; fi
    resp="${resp#$'\n'}"
    TAS_RESULT=$(printf '%s\n' "$resp" | head -n1)
    TAS_REASON=$(printf '%s\n' "$resp" | grep -E '^reason=' | head -n1 | sed 's/^reason=//')
    TAS_ERROR=$(printf '%s\n' "$resp" | grep -E '^error=' | head -n1 | sed 's/^error=//')
}

# ---------------------------------------------------------------------------
# 本地模式预筛：只有疑似危险 / 脚本执行的命令才走 TCP，其余直接放行
# ---------------------------------------------------------------------------
tas_needs_check() {
    local cmd="$1"
    case "$cmd" in
        # 脚本执行
        bash\ *|sh\ *|zsh\ *|ksh\ *|dash\ *|fish\ *) return 0 ;;
        source\ *) return 0 ;;
        ./*|../*) return 0 ;;
        /*) return 0 ;;
        *\.sh*|*\.bash*|*\.zsh*|*\.py*|*\.rb*|*\.pl*|*\.js\ *) return 0 ;;
        # 危险命令
        su|su\ *|sudo*|pkexec\ *|doas*) return 0 ;;
        rm\ -rf*|rm\ -fr*|rm\ -f\ -r*|rm\ -r\ -f*) return 0 ;;
        mkfs*|mkswap*|fdisk\ /dev*|sfdisk*) return 0 ;;
        dd\ of=*|dd\ if=*of=*|dd\ if=/dev*) return 0 ;;
        shutdown*|reboot*|poweroff*|halt*) return 0 ;;
        *"chmod -R 777 /"*|*"chmod 777 /"*) return 0 ;;
        *">/dev/sd"*|*">/dev/mmc"*|*">/dev/block"*) return 0 ;;
        *"mv /*"*|*"rm /*"*|*"cp /*"*) return 0 ;;
        *) return 1 ;;
    esac
}

# ---------------------------------------------------------------------------
# 检测并拦截（脚本走 CHECK_SCRIPT，其余走 CHECK_CMD）
# ---------------------------------------------------------------------------
tas_check_and_block() {
    local cmd="$1"
    local method="CHECK_CMD"
    case "$cmd" in
        bash\ *|sh\ *|zsh\ *|ksh\ *|dash\ *|fish\ *|source\ *|./*|../*|/*|*\.sh*|*\.py*)
            method="CHECK_SCRIPT"
            printf '\n[Termux Security] 正在检测脚本...\n' >&2
            ;;
    esac
    tas_call "$method" "$cmd"
    if [ "$TAS_RESULT" = "DENY" ]; then
        printf '\n[Termux Security] BLOCKED: %s\n' "$cmd" >&2
        [ -n "$TAS_REASON" ] && printf '[Termux Security] reason: %s\n' "$TAS_REASON" >&2
        printf '\n' >&2
        return 1
    fi
    # 服务器端检测异常：命令自动放行，但把异常原因显示出来
    if [ -n "$TAS_ERROR" ]; then
        printf '\n[Termux Security] 检测异常（已自动放行）: %s\n' "$TAS_ERROR" >&2
    fi
    return 0
}

# ---------------------------------------------------------------------------
# bash：trap DEBUG 立即注册，但 __TAS_READY 未设置前全部跳过
# ---------------------------------------------------------------------------
if [ -n "$BASH_VERSION" ]; then
    tas_log "  shell: bash $BASH_VERSION"
    tas_log "  port file: $PORT_FILE"

    tas_preexec() {
        # source 初始化阶段 → 零开销跳过（不干扰 oh-my-bash 等加载）
        [ -z "$__TAS_READY" ] && return 0
        [ -n "$BASH_COMMAND" ] || return 0
        [ -f "$PORT_FILE" ] || return 0
        case "$BASH_COMMAND" in
            tas_*|__tas_*|trap\ *DEBUG*|shopt\ *extdebug*) return 0 ;;
        esac
        # 本地预筛：只有疑似危险/脚本命令才 TCP，其余零开销
        if tas_needs_check "$BASH_COMMAND"; then
            # 相对路径转绝对路径（server 的 cwd 不是 shell 的 cwd）
            local cmd="$BASH_COMMAND"
            case "$cmd" in
                ./*) cmd="${PWD%/}/${cmd#./}" ;;
            esac
            tas_check_and_block "$cmd"
            [ "$TAS_RESULT" = "DENY" ] && return 1
        fi
        return 0
    }

    shopt -s extdebug 2>/dev/null
    trap 'tas_preexec' DEBUG
    tas_log "  trap DEBUG registered."

    # 初始化完成（必须是最后一行：export 自身触发的 DEBUG trap 在设置前检查，安全）
    export __TAS_READY=1
fi

# ---------------------------------------------------------------------------
# zsh
# ---------------------------------------------------------------------------
if [ -n "$ZSH_VERSION" ]; then
    tas_log "  shell: zsh $ZSH_VERSION"
    export __TAS_READY=1
    tas_zsh_preexec() {
        [ -z "$__TAS_READY" ] && return 0
        [ -f "$PORT_FILE" ] || return 0
        [ -n "$1" ] || return 0
        if tas_needs_check "$1"; then
            tas_check_and_block "$1"
            [ "$TAS_RESULT" = "DENY" ] && return 1
        fi
        return 0
    }
    if ! printf '%s\n' "${preexec_functions[@]}" 2>/dev/null | grep -q 'tas_zsh_preexec'; then
        preexec_functions+=(tas_zsh_preexec)
    fi
    tas_log "  preexec registered."
fi
