#!/system/bin/sh
# ============================================================================
# VorteX Guard Engine Hook — v21
#
# v20 成功证明: TCP 在子 shell 里做 = 零 prompt 干扰
# v21 加回 DEBUG trap 拦截 ./script.sh (直接 exec, 不走 shell 命令)
#
# trap 设计原则:
#   1. 开头立刻 shopt -u extdebug → PROMPT_COMMAND 永远不被拦截
#   2. 白名单 (vge_*, _omb_*, shopt, trap) 直接 return 0
#   3. 只检测 ./ ../ /* 开头的命令 (直接执行脚本)
#   4. TCP 在子 shell 里做 (已证明安全)
#   5. DENY 时才临时开 extdebug + return 1 (只为让 bash 跳过命令)
#   6. precmd 兜底关 extdebug (如果 DENY 后泄漏)
# ============================================================================

if [ -n "$__VGE_LOADED" ]; then
    return 0 2>/dev/null || exit 0
fi
__VGE_LOADED=1

PORT_FILE="${TERMUX_SECURITY_PORT_FILE:-/data/data/com.termux/files/sock/termux-security.port}"
__VGE_LOG="${HOME}/.tas-debug.log"

vge_dlog() {
    { printf '%s %s\n' "${EPOCHSECONDS:-$SECONDS}" "$*" >>"$__VGE_LOG"; } 2>/dev/null
}

vge_log() {
    case "$-" in
        *i*) printf '[VGE] %s\n' "$*" ;;
    esac
}

vge_log "Loading VorteX Guard Engine (v21)..."

# ============================================================================
# TCP 通信 — 放在子 shell 里, 父进程零 fd 操作!
# ============================================================================
vge_call() {
    local method="$1"; shift
    local port
    port=$(cat "$PORT_FILE" 2>/dev/null) || { vge_RESULT="ALLOW"; vge_ERROR="no-port-file"; return 0; }
    [ -z "$port" ] && { vge_RESULT="ALLOW"; vge_ERROR="empty-port"; return 0; }

    local timeout=90
    [ "$method" = "CHECK_SCRIPT" ] && timeout=120

    local __resp
    __resp=$(
        (
            local req="$method"
            local line
            for line in "$@"; do req="${req}"$'\n'"${line}"; done
            req="${req}"$'\n'"END"$'\n'

            if command -v nc >/dev/null 2>&1; then
                printf '%s' "$req" | nc -w "$timeout" 127.0.0.1 "$port" 2>/dev/null
            elif [ -n "$BASH_VERSION" ]; then
                if exec 3<>/dev/tcp/127.0.0.1/$port 2>/dev/null; then
                    printf '%s' "$req" >&3
                    local count=0
                    while [ $count -lt 10 ] && IFS= read -t "$timeout" -r line <&3; do
                        count=$((count+1))
                        printf '%s\n' "$line"
                        [ "$line" = "END" ] && break
                    done
                    exec 3<>/dev/null 2>/dev/null
                else
                    printf 'CONNECT_FAILED\n'
                fi
            fi
        ) 2>/dev/null
    ) || __resp=""

    local done=0
    case "$__resp" in *"END"*) done=1 ;; esac
    vge_REASON=""
    vge_ERROR=""

    if [ "$done" -ne 1 ]; then
        local first
        first=$(printf '%s\n' "$__resp" | sed '/^$/d' | head -n1)
        case "$first" in ALLOW|DENY) vge_RESULT="$first" ;; CONNECT_FAILED) vge_RESULT="ALLOW"; vge_ERROR="connect-failed" ;; *) vge_RESULT="ALLOW" ;; esac
        [ -z "$vge_ERROR" ] && vge_ERROR="incomplete done=$done first=[$first]"
        return 0
    fi
    __resp="${__resp#$'\n'}"
    vge_RESULT=$(printf '%s\n' "$__resp" | head -n1)
    vge_REASON=$(printf '%s\n' "$__resp" | grep -E '^reason=' | head -n1 | sed 's/^reason=//')
    vge_ERROR=$(printf '%s\n' "$__resp" | grep -E '^error=' | head -n1 | sed 's/^error=//')
}

# ============================================================================
# 检测入口
# ============================================================================
vge_check_and_block() {
    local cmd="$1"
    local method="CHECK_CMD"
    case "$cmd" in
        ./*|../*|/*|*\.sh*|*\.bash*|*\.zsh*)
            method="CHECK_SCRIPT" ;;
    esac
    vge_dlog "check: method=$method cmd=[${cmd:0:100}]"
    vge_call "$method" "$cmd"
    vge_dlog "check: result=$vge_RESULT reason=[${vge_REASON:0:100}] error=[$vge_ERROR]"
    if [ "$vge_RESULT" = "DENY" ]; then
        printf '\n[VorteX Guard] BLOCKED: %s\n' "$cmd"
        [ -n "$vge_REASON" ] && printf '[VorteX Guard] reason: %s\n' "$vge_REASON"
        printf '\n'
        return 1
    fi
    if [ -n "$vge_ERROR" ]; then
        printf '\n[VorteX Guard] 检测异常（已自动放行）: %s\n' "$vge_ERROR"
    fi
    return 0
}

# ============================================================================
# 函数覆盖
# ============================================================================
__VGE_wrap() {
    local __fn="$1"; shift
    local __full="$__fn"
    [ $# -gt 0 ] && __full="$__fn $*"

    case "$__fn" in
        bash|sh|zsh|ksh|dash|fish)
            if [ -z "${__VGE_READY:-}" ]; then
                vge_dlog "init-pass (shell): [${__full:0:60}]"
                command "$__fn" "$@"
                return $?
            fi
            ;;
        rm)
            case "$*" in
                *"-rf /"*|*"-fr /"*|*"-f -r /"*|*"-r -f /"*|*"--no-preserve-root"*|*"-rf /*"*) ;;
                *) command rm "$@"; return $? ;;
            esac
            ;;
        chmod)
            case "$*" in
                *"-R 777 /"*|*"-R 777 /*"*|*"777 /"*) ;;
                *) command chmod "$@"; return $? ;;
            esac
            ;;
        su|sudo|pkexec|doas|dd|mkswap|fdisk|sfdisk|setprop|\
        mkfs|mkfs.ext2|mkfs.ext3|mkfs.ext4|mkfs.f2fs|mkfs.vfat|mkfs.exfat|\
        shutdown|reboot|poweroff|halt)
            ;;
        *)
            command "$__fn" "$@"
            return $? ;;
    esac

    vge_check_and_block "$__full"
    [ "$vge_RESULT" = "DENY" ] && return 1
    command "$__fn" "$@"
}

for __f in su sudo pkexec doas dd mkswap fdisk sfdisk setprop \
           mkfs mkfs.ext2 mkfs.ext3 mkfs.ext4 mkfs.f2fs mkfs.vfat mkfs.exfat \
           shutdown reboot poweroff halt rm chmod \
           bash sh zsh ksh dash fish; do
    eval "${__f}() { __VGE_wrap ${__f} \"\$@\"; }"
done
unset __f

# ============================================================================
# DEBUG trap — 拦截 ./script.sh (直接 exec, 不走 shell 命令)
#
# 关键安全设计:
#   1. 开头立刻 shopt -u extdebug → PROMPT_COMMAND 永远不被拦截
#   2. 递归保护 __VGE_IN_TRAP → 防止重入
#   3. 白名单直接 return 0 → OMB/我们自己的函数不被干扰
#   4. 只检测 ./ ../ /* 开头的命令 → 只拦直接执行脚本
#   5. TCP 在子 shell 里做 → 父进程零 fd 改动
#   6. DENY 时才临时开 extdebug + return 1 → 只为让 bash 跳过命令
# ============================================================================
if [ -n "$BASH_VERSION" ]; then
    vge_trap_debug() {
        # 1. 递归保护
        [ -n "${__VGE_IN_TRAP:-}" ] && return 0
        __VGE_IN_TRAP=1

        # 2. 立刻关 extdebug — 绝对保证 PROMPT_COMMAND 不被拦截
        shopt -u extdebug 2>/dev/null

        local __cmd="$BASH_COMMAND"
        [ -z "$__cmd" ] && { __VGE_IN_TRAP=; return 0; }

        # 3. 白名单 — 直接放行
        case "$__cmd" in
            vge_*|__VGE_*|__VGE_*|_omb_*|_omz_*|__bp_*\
            |trap\ *DEBUG*|trap\ *-p*\
            |shopt\ *extdebug*|shopt\ *-s*\ |shopt\ *-u*\
            |builtin\ *)
                __VGE_IN_TRAP=; return 0 ;;
        esac

        # 4. 只检测直接执行脚本的命令 (./..//*)
        case "$__cmd" in
            ./*|../*|/*)
                # 可能是 ./script.sh 或 /bin/ls 等
                # /bin/ls 不是脚本, 但检测一下也无妨 (TCP 会判断)
                ;;
            *)
                # 其他命令 (cat, ls, echo...) 直接放行, 不检测
                __VGE_IN_TRAP=; return 0 ;;
        esac

        # 5. 子 shell TCP (安全!)
        vge_check_and_block "$__cmd"

        if [ "$vge_RESULT" = "DENY" ]; then
            # 6. DENY: 临时开 extdebug + return 1 → bash 跳过命令
            shopt -s extdebug 2>/dev/null
            __VGE_IN_TRAP=
            return 1
        fi

        __VGE_IN_TRAP=
        return 0
    }

    # 链式 trap: 保留原 DEBUG trap handler
    __VGE_orig_debug=$(trap -p DEBUG 2>/dev/null | sed 's/^trap -- //' | sed 's/ DEBUG$//')
    if [ -n "$__VGE_orig_debug" ]; then
        eval "vge_trap_debug() { ${__VGE_orig_debug}; _vge_trap_inner \"\$BASH_COMMAND\"; }"
    fi
    trap 'vge_trap_debug' DEBUG
    vge_dlog "trap: DEBUG installed (orig=[${__VGE_orig_debug:0:40}])"
fi

# ============================================================================
# precmd 兜底关 extdebug — 防止 DENY 后 extdebug 泄漏到 PROMPT_COMMAND
# ============================================================================
__VGE_precmd_hook() {
    shopt -u extdebug 2>/dev/null
}

if [ -n "$BASH_VERSION" ]; then
    # OMB 模式: 往 OMB precmd 数组里注册
    if [ -n "${_omb_original_precmd_functions:-}" ]; then
        case "${_omb_original_precmd_functions[*]}" in
            *__VGE_precmd_hook*) ;;
            *) _omb_original_precmd_functions+=(__VGE_precmd_hook) ;;
        esac
        vge_dlog "precmd: registered via OMB array"
    else
        # fallback: PROMPT_COMMAND 追加
        case "${PROMPT_COMMAND:-}" in
            *__VGE_precmd_hook*) ;;
            *)
                if [ -n "$PROMPT_COMMAND" ]; then
                    PROMPT_COMMAND="${PROMPT_COMMAND}; __VGE_precmd_hook"
                else
                    PROMPT_COMMAND="__VGE_precmd_hook"
                fi
                export PROMPT_COMMAND
                vge_dlog "precmd: registered via PROMPT_COMMAND"
                ;;
        esac
    fi
fi

# 标记初始化完成
__VGE_READY=1
export __VGE_READY

vge_log "Security module active (v21, trap + sub-shell TCP)."
vge_dlog "init-done"
