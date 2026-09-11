#!/system/bin/sh
# ============================================================================
# Termux Advanced Security Hook — v9.2
#
# v9.1 日志实证：late-init OK / check 触发 / server DENY 回传 —— 检测链路全通。
# 剩余问题：DENY → trap return 1 → extdebug 跳过命令后，prompt 不再渲染。
#
# 根因分析：extdebug 下 DEBUG trap 在 PROMPT_COMMAND 执行期间同样开火；
# trap 一旦在此期间返回 1 → bash 中止整个 hook 函数 → omb prompt 崩掉。
#
# v9.2 修复：
#   1) 【渲染保护区】late-init 时把 PROMPT_COMMAND 元素[0]（omb hook）包进
#      __tas_pc_wrap：执行原内容前置 __TAS_IN_PROMPT=1 → trap 检测到该标志
#      直接 return 0 —— prompt 渲染链路绝不可能被我们中止
#   2) 【DENY 抑制窗】DENY 后 3s 内同一命令重触发 → 静默 rc=1（不再走 TCP），
#      防 extdebug 跳过后 BASH_COMMAND 残留导致的重触发循环
#   3) 【日志】precmd ok / deny-skip 两类事件入 ~/.tas-debug.log，
#      下轮日志可直接分辨：PC 数组是否存活 / 谁被跳过
# ============================================================================

if [ -n "$__TAS_LOADED" ]; then
    return 0 2>/dev/null || exit 0
fi
__TAS_LOADED=1

PORT_FILE="${TERMUX_SECURITY_PORT_FILE:-/data/data/com.termux/files/sock/termux-security.port}"
__TAS_LOG="${HOME}/.tas-debug.log"

tas_log() {
    case "$-" in
        *i*) printf '[TAS] %s\n' "$*" >&2 ;;
    esac
}

tas_dlog() {
    { printf '%s %s\n' "${EPOCHSECONDS:-$SECONDS}" "$*" >>"$__TAS_LOG"; } 2>/dev/null
}

tas_log "Loading Termux Advanced Security Module (v9.2)..."

# ---------------------------------------------------------------------------
# TCP 通信
# ---------------------------------------------------------------------------
tas_call() {
    local method="$1"; shift
    local port
    port=$(cat "$PORT_FILE" 2>/dev/null) || { TAS_RESULT="ALLOW"; TAS_ERROR="no-port-file"; return 0; }
    [ -z "$port" ] && { TAS_RESULT="ALLOW"; TAS_ERROR="empty-port"; return 0; }

    local req="$method"
    local line
    for line in "$@"; do req="${req}"$'\n'"${line}"; done
    req="${req}"$'\n'"END"$'\n'

    local timeout=90
    [ "$method" = "CHECK_SCRIPT" ] && timeout=120

    local resp=""
    local done=0
    TAS_REASON=""
    TAS_ERROR=""
    if [ -n "$BASH_VERSION" ]; then
        if exec 3<>/dev/tcp/127.0.0.1/$port 2>/dev/null; then
            printf '%s' "$req" >&3
            local count=0
            while [ $count -lt 10 ] && IFS= read -t "$timeout" -r line <&3; do
                count=$((count+1))
                resp="${resp}"$'\n'"${line}"
                if [ "$line" = "END" ]; then done=1; break; fi
            done
            exec 3<&- 2>/dev/null
        else
            TAS_ERROR="connect-failed port=$port"
        fi
    elif command -v nc >/dev/null 2>&1; then
        resp=$(printf '%s' "$req" | nc -w "$timeout" 127.0.0.1 "$port" 2>/dev/null) || resp=""
        case "$resp" in
            *"END"*) done=1 ;;
        esac
    fi

    if [ "$done" -ne 1 ]; then
        local first
        first=$(printf '%s\n' "$resp" | sed '/^$/d' | head -n1)
        case "$first" in
            ALLOW|DENY) TAS_RESULT="$first" ;;
            *) TAS_RESULT="ALLOW" ;;
        esac
        TAS_ERROR="${TAS_ERROR:-incomplete done=$done first=[$first]}"
        return 0
    fi
    resp="${resp#$'\n'}"
    TAS_RESULT=$(printf '%s\n' "$resp" | head -n1)
    TAS_REASON=$(printf '%s\n' "$resp" | grep -E '^reason=' | head -n1 | sed 's/^reason=//')
    TAS_ERROR=$(printf '%s\n' "$resp" | grep -E '^error=' | head -n1 | sed 's/^error=//')
}

# ---------------------------------------------------------------------------
# 本地模式预筛
# ---------------------------------------------------------------------------
tas_needs_check() {
    local cmd="$1"
    case "$cmd" in
        bash\ *|sh\ *|zsh\ *|ksh\ *|dash\ *|fish\ *) return 0 ;;
        source\ *) return 0 ;;
        ./*|../*) return 0 ;;
        /*) return 0 ;;
        *\.sh*|*\.bash*|*\.zsh*|*\.py*|*\.rb*|*\.pl*|*\.js\ *) return 0 ;;
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
# 检测入口
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
    tas_dlog "check: method=$method cmd=[${cmd:0:100}]"
    tas_call "$method" "$cmd"
    tas_dlog "check: result=$TAS_RESULT reason=[${TAS_REASON:0:100}] error=[$TAS_ERROR]"
    if [ "$TAS_RESULT" = "DENY" ]; then
        # 抑制窗：3s 内同命令重触发直接静默跳过
        __TAS_DENIED_CMD="$cmd"
        __TAS_DENIED_UNTIL=$((SECONDS + 3))
        stty sane 2>/dev/null || stty echo 2>/dev/null
        printf '\n[Termux Security] BLOCKED: %s\n' "$cmd" >&2
        [ -n "$TAS_REASON" ] && printf '[Termux Security] reason: %s\n' "$TAS_REASON" >&2
        printf '\n' >&2
        tas_dlog "deny-skip: [${cmd:0:60}]"
        return 1
    fi
    if [ -n "$TAS_ERROR" ]; then
        printf '\n[Termux Security] 检测异常（已自动放行）: %s\n' "$TAS_ERROR" >&2
    fi
    return 0
}

# ===========================================================================
# bash
# ===========================================================================
if [ -n "$BASH_VERSION" ]; then

    # 每次 prompt 前恢复终端状态 + 记录 PC 存活
    __tas_precmd() {
        stty sane 2>/dev/null || stty echo 2>/dev/null
        tas_dlog "precmd ok"
    }

    # 【渲染保护区】包装 PROMPT_COMMAND 原内容：
    # 执行期间置 __TAS_IN_PROMPT → trap 直接放行 → prompt 渲染绝不被中止
    __tas_pc_wrap() {
        __TAS_IN_PROMPT=1
        if [ -n "$__TAS_ORIG_PC" ]; then
            eval "$__TAS_ORIG_PC"
        fi
        __TAS_IN_PROMPT=
    }

    # 第一次 prompt 执行（omb 已完整加载）——一次性安装
    __tas_late_init() {
        [ -n "$__TAS_INSTALLED" ] && return 0
        __TAS_INSTALLED=1

        # 1) 捕获当前 DEBUG trap（链式保留）
        local t
        t=$(trap -p DEBUG 2>/dev/null)
        case "$t" in
            *tas_trap_debug*) __TAS_ORIG_DEBUG_CMD="" ;;
            *)
                __TAS_ORIG_DEBUG_CMD=$(printf '%s\n' "$t" | sed "s/^trap -- '//" | sed "s/' DEBUG\$//")
                [ "$__TAS_ORIG_DEBUG_CMD" = "$t" ] && __TAS_ORIG_DEBUG_CMD=""
                ;;
        esac

        # 2) extdebug + 链式 trap
        shopt -s extdebug 2>/dev/null
        trap 'tas_trap_debug' DEBUG

        # 3) 包装 PROMPT_COMMAND（渲染保护区）+ late_init → precmd
        if declare -p PROMPT_COMMAND 2>/dev/null | grep -q 'declare -a'; then
            if [ "${#PROMPT_COMMAND[@]}" -gt 0 ]; then
                __TAS_ORIG_PC="${PROMPT_COMMAND[0]}"
                PROMPT_COMMAND[0]=__tas_pc_wrap
            else
                __TAS_ORIG_PC=""
                PROMPT_COMMAND+=(__tas_pc_wrap)
            fi
            local _i
            for _i in "${!PROMPT_COMMAND[@]}"; do
                [ "${PROMPT_COMMAND[$_i]}" = "__tas_late_init" ] && PROMPT_COMMAND[$_i]="__tas_precmd"
            done
            local _has=0 _e
            for _e in "${PROMPT_COMMAND[@]}"; do
                [ "$_e" = "__tas_precmd" ] && { _has=1; break; }
            done
            [ "$_has" -eq 0 ] && PROMPT_COMMAND+=(__tas_precmd)
        else
            local np="$PROMPT_COMMAND"
            np="${np//__tas_late_init; /}"
            np="${np//__tas_late_init/}"
            __TAS_ORIG_PC="$np"
            PROMPT_COMMAND="__tas_pc_wrap; __tas_precmd"
        fi

        export __TAS_READY=1
        tas_dlog "late-init OK v9.2. orig-pc=[${__TAS_ORIG_PC:0:60}] pc=[$(declare -p PROMPT_COMMAND 2>/dev/null | head -c 200)]"
        tas_log "Security module active (v9.2)."
    }

    # 链式 DEBUG trap
    tas_trap_debug() {
        [ -n "$__TAS_IN_TRAP" ] && return 0
        # 渲染保护区：PROMPT_COMMAND 执行期间绝不检测、绝不止
        [ -n "$__TAS_IN_PROMPT" ] && return 0
        __TAS_IN_TRAP=1

        # DENY 抑制窗：刚被拒的同一命令直接静默跳过（防重触发循环）
        if [ -n "$BASH_COMMAND" ] && [ "$BASH_COMMAND" = "$__TAS_DENIED_CMD" ] \
           && [ "${__TAS_DENIED_UNTIL:-0}" -gt "$SECONDS" ]; then
            __TAS_IN_TRAP=
            return 1
        fi

        local __rc=0
        if [ -n "$__TAS_ORIG_DEBUG_CMD" ]; then
            eval "$__TAS_ORIG_DEBUG_CMD"
        fi
        if [ -n "$BASH_COMMAND" ]; then
            case "$BASH_COMMAND" in
                tas_*|__tas_*|__TAS_*|__bp_*|_omb_*|trap\ *DEBUG*|shopt\ *extdebug*) ;;
                *)
                    if [ -f "$PORT_FILE" ] && tas_needs_check "$BASH_COMMAND"; then
                        local cmd="$BASH_COMMAND"
                        case "$cmd" in
                            ./*) cmd="${PWD%/}/${cmd#./}" ;;
                        esac
                        tas_check_and_block "$cmd"
                        [ "$TAS_RESULT" = "DENY" ] && __rc=1
                    fi
                    ;;
            esac
        fi
        __TAS_IN_TRAP=
        return $__rc
    }

    # ---- source 时注册（零 trap / 零 extdebug / 不碰数组元素）----
    if declare -p PROMPT_COMMAND 2>/dev/null | grep -q 'declare -a'; then
        PROMPT_COMMAND+=(__tas_late_init)
    else
        case "$PROMPT_COMMAND" in
            *__tas_late_init*) ;;
            *) PROMPT_COMMAND="__tas_late_init${PROMPT_COMMAND:+; $PROMPT_COMMAND}" ;;
        esac
    fi

# ===========================================================================
# zsh
# ===========================================================================
elif [ -n "$ZSH_VERSION" ]; then
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
    tas_zsh_precmd() {
        stty sane 2>/dev/null || stty echo 2>/dev/null
    }
    if ! printf '%s\n' "${precmd_functions[@]}" 2>/dev/null | grep -q 'tas_zsh_precmd'; then
        precmd_functions+=(tas_zsh_precmd)
    fi
    tas_log "  preexec registered."
fi
