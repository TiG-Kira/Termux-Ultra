#!/system/bin/sh
# ============================================================================
# Termux Advanced Security — Shell Hook
#
# bash: trap DEBUG + extdebug（直接 trap，但用 __TAS_READY 标志保护初始化期间）
# zsh : preexec_functions（官方，直接注册）
# ============================================================================

# 防重复 source
if [ -n "$__TAS_LOADED" ]; then
    return 0 2>/dev/null || exit 0
fi
__TAS_LOADED=1

# 初始化保护标志：source 期间所有 DEBUG trap 都跳过
# 脚本执行到最后才设 __TAS_READY=1
__TAS_READY=""

PORT_FILE="${TERMUX_SECURITY_PORT_FILE:-/data/data/com.termux/files/sock/termux-security.port}"

# ---------------------------------------------------------------------------
# 初始化提示（排查 signal 9 的进度条）
# ---------------------------------------------------------------------------

tas_log() {
    case "$-" in *i*) printf '[TAS] %s\n' "$*" >&2 ;; esac
}

tas_log "Loading Termux Advanced Security Module..."
tas_log "  hook: $(readlink -f "$0" 2>/dev/null || echo "$0")"
tas_log "  port: $PORT_FILE"

# ---------------------------------------------------------------------------
# TCP 通信（bash 用 /dev/tcp 内置，不需要 nc；zsh fallback nc）
# ---------------------------------------------------------------------------

tas_get_port() {
    [ -f "$PORT_FILE" ] && cat "$PORT_FILE" 2>/dev/null
}

tas_call() {
    local method="$1"; shift
    local port
    port=$(tas_get_port) || { TAS_RESULT="ALLOW"; return 0; }
    [ -z "$port" ] && { TAS_RESULT="ALLOW"; return 0; }

    local req="$method"
    for line in "$@"; do req="${req}"$'\n'"${line}"; done
    req="${req}"$'\n'"END"$'\n'

    local resp=""
    local line

    if [ -n "$BASH_VERSION" ]; then
        if exec 3<>/dev/tcp/127.0.0.1/$port 2>/dev/null; then
            printf '%s' "$req" >&3
            exec 3>&-
            while IFS= read -t 2 -r line <&3; do
                resp="${resp}"$'\n'"${line}"
                [ "$line" = "END" ] && break
            done
            exec 3<&-
        fi
    elif command -v nc >/dev/null 2>&1; then
        resp=$(printf '%s' "$req" | nc -w 2 127.0.0.1 "$port" 2>/dev/null) || resp=""
    fi

    if [ -z "$resp" ]; then
        TAS_RESULT="ALLOW"
        return 0
    fi
    resp="${resp#$'\n'}"
    TAS_RESULT=$(printf '%s\n' "$resp" | head -n1)
    TAS_REASON=$(printf '%s\n' "$resp" | grep -E '^reason=' | head -n1 | sed 's/^reason=//')
}

# ---------------------------------------------------------------------------
# 命令检测
# ---------------------------------------------------------------------------

tas_check_and_block() {
    local cmd="$1"
    [ -z "$cmd" ] && return 0

    case "$cmd" in
        tas_*|__termux_*|*SECURITY_HOOK*) return 0 ;;
        exit|logout|clear|pwd|help|history|bind|alias|unalias|type|which|echo|printf|export|set|unset|cd|pushd|popd|dirs|jobs|fg|bg|wait|disown|kill|ulimit|umask|return|break|continue|eval|exec|source|\.|\[|test|trap|shopt|case|if|then|else|fi|for|while|do|done|function|time) return 0 ;;
    esac

    tas_call CHECK_CMD "$cmd"

    if [ "$TAS_RESULT" = "DENY" ]; then
        printf '\n[Termux Security] BLOCKED: %s\n' "$cmd" >&2
        [ -n "$TAS_REASON" ] && printf '[Termux Security] reason: %s\n' "$TAS_REASON" >&2
        printf '\n' >&2
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# bash：trap DEBUG + extdebug（直接 trap，但 __TAS_READY 保护初始化）
# ---------------------------------------------------------------------------

if [ -n "$BASH_VERSION" ]; then
    tas_log "  shell: bash $BASH_VERSION"

    tas_bash_preexec() {
        [ -z "$BASH_COMMAND" ] && return 0
        # 初始化保护！__TAS_READY=1 之前（source hook 期间）所有 DEBUG trap 跳过
        if [ -z "$__TAS_READY" ]; then
            return 0
        fi
        case "$BASH_COMMAND" in
            tas_*|__termux_*|*PROMPT_COMMAND*|trap\ *DEBUG*|shopt\ *extdebug*) return 0 ;;
        esac
        case "$-" in *i*) ;; *) return 0 ;; esac

        tas_check_and_block "$BASH_COMMAND"
        if [ "$TAS_RESULT" = "DENY" ]; then
            return 1
        fi
        return 0
    }

    shopt -s extdebug 2>/dev/null
    trap 'tas_bash_preexec' DEBUG
    tas_log "  trap DEBUG registered (inactive until initialization done)"
fi

# ---------------------------------------------------------------------------
# zsh：preexec_functions
# ---------------------------------------------------------------------------

if [ -n "$ZSH_VERSION" ]; then
    tas_log "  shell: zsh $ZSH_VERSION"
    tas_zsh_preexec() {
        [ -z "$1" ] && return 0
        tas_check_and_block "$1"
    }
    if ! printf '%s\n' "${preexec_functions[@]}" 2>/dev/null | grep -q 'tas_zsh_preexec'; then
        preexec_functions+=(tas_zsh_preexec)
    fi
    tas_log "  preexec registered"
fi

# ---------------------------------------------------------------------------
# 全部完成，启用检测
# ---------------------------------------------------------------------------

export __TAS_READY=1
tas_log "Initialization complete. Monitoring all commands..."
