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
# rm 是否必须送检测端:
#   1) 带递归开关 (-r / -R / 组合形式如 -rf / -fr, 或 --recursive / --no-preserve-root)
#   2) 删除目标是绝对路径 (/ 或 /xxx)
#
# 旧实现是一张固定组合的 case 表, 只认 "-rf /" "-fr /" "-f -r /" "-r -f /"
# "--no-preserve-root" "-rf /*", 于是 `rm -r /`、`rm -R /*`、`rm -rf /etc`
# 全部落到 *) 分支原样执行 —— 而 RiskCommandDetector 的 RM_RF_ROOT 规则本来
# 正好能匹配这些形式, 只是 hook 压根没把命令发出去。
#
# 宁可多送一次检测：误送只会多一次本地 TCP 往返（毫秒级），漏送是直接放行。
__VGE_rm_dangerous() {
    local __arg __rec=0
    for __arg in "$@"; do
        case "$__arg" in
            --recursive|--no-preserve-root) __rec=1 ;;
            -[a-zA-Z]*) case "$__arg" in *r*|*R*) __rec=1 ;; esac ;;
            /*|/) return 0 ;;
        esac
    done
    [ "$__rec" = 1 ]
}

# 剥离 env / nohup / xargs / busybox 等前缀执行器, 结果写入 __VGE_STRIPPED。
#
# 为什么需要: 函数覆盖只对「裸命令名」生效。`env rm -rf /` 里的 rm 是 env 从 PATH
# 里重新查出来的真二进制, 不走我们的 rm 函数; DEBUG trap 那边它既不是 ./ ../ /*
# 开头、也没有块设备重定向, 同样直接放行。常见的前缀还有 nohup / xargs / nice /
# setsid / stdbuf / timeout / time / busybox / eval / command / exec / strace。
__VGE_STRIPPED=
__VGE_strip_prefix() {
    local __rest="$1" __tok
    while :; do
        __tok=${__rest%%[[:space:]]*}
        [ "$__tok" = "$__rest" ] && break
        case "$__tok" in
            env|nohup|nice|setsid|stdbuf|timeout|time|xargs|busybox|eval|command|exec|watch|strace)
                __rest=${__rest#*[[:space:]]}
                # 再吃掉前缀执行器自身的选项 (-i / -n1 / -p ...)
                while :; do
                    __tok=${__rest%%[[:space:]]*}
                    case "$__tok" in
                        -*) __rest=${__rest#*[[:space:]]} ;;
                        *) break ;;
                    esac
                done
                ;;
            *) break ;;
        esac
    done
    __VGE_STRIPPED="$__rest"
}

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
            if ! __VGE_rm_dangerous "$@"; then
                command rm "$@"
                return $?
            fi
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

# 说明: 这张表必须与 RiskCommandDetector 中的规则对齐, 否则检测端有规则、
# shell 端却不投递, 规则形同虚设。补齐了原先缺失的分区/擦除/加密/内核模块
# 与其余 mkfs 变体（parted / cfdisk / gdisk / sgdisk / wipefs / shred /
# cryptsetup / mkfs.xfs / mkfs.btrfs / mkfs.ntfs / insmod / rmmod / modprobe 等）。
for __f in su sudo pkexec doas dd mkswap fdisk sfdisk setprop \
           mkfs mkfs.ext2 mkfs.ext3 mkfs.ext4 mkfs.f2fs mkfs.vfat mkfs.exfat \
           mkfs.fat mkfs.ntfs mkfs.xfs mkfs.btrfs mkfs.zfs mke2fs newfs_msdos \
           parted cfdisk gdisk sgdisk wipefs shred cryptsetup \
           insmod rmmod modprobe \
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

        # 4. 检测范围
        #    a) 直接执行脚本 (./ ../ /*) —— 不走函数包装, 只能靠 DEBUG trap
        #    b) 重定向写入块设备 (> /dev/sdX、>> /dev/nvme0n1 ...)
        #       这类命令没有可包装的命令名, 函数包装抓不到; 原先被整类放行,
        #       导致 RiskCommandDetector 的 RAW_DISK_WRITE 规则永远不触发
        #       （`cat x > /dev/sda` 这类破坏性写盘可直接执行）
        #    c) 其余交互命令放行 —— 性能考虑, 不把每条命令都送去 TCP 检测
        #    d) 前缀执行器 (env / nohup / xargs / busybox ...) 包裹的危险命令
        #       见 __VGE_strip_prefix 的说明
        #
        # 重定向判定基于「去掉所有空白」的副本: case 模式无法表达可选的空格,
        # 否则 `cat x > /dev/sda`（> 后有空格）会被漏掉 —— 这正是检测端正则
        # 里 `>\s*/dev/` 所允许的形式。
        #
        # 再放宽一层: 只去掉空白仍挡不住 `cat x >${IFS}/dev/sda` 这类夹变量的写法,
        # 因此只要「同时出现重定向符和块设备路径」就送检测端（误送仅多一次本地往返）。
        __vge_nosp=${__cmd//[[:space:]]/}

        # 三类需要送检测的情况，任一命中即检查；都不命中才放行
        __vge_check=0

        #   a) 直接执行脚本 (./ ../ /*) —— 不走函数包装, 只能靠 DEBUG trap
        case "$__cmd" in
            ./*|../*|/*) __vge_check=1 ;;
        esac

        #   b) 重定向写入块设备 (> /dev/sdX、>> /dev/nvme0n1 ...)
        #      判定基于「去掉所有空白」的副本: case 模式无法表达可选的空格,
        #      否则 `cat x > /dev/sda`（> 后有空格）会被漏掉。
        #      再放宽一层: 只去掉空白仍允许中间夹别的东西 (`>${IFS}/dev/sda`、
        #      `>${DISK}`)，所以「出现块设备路径 + 出现重定向符」一并视为可疑。
        #      误判代价只是一次本地往返，漏判代价是直接放行。
        if [ "$__vge_check" = 0 ]; then
            case "$__vge_nosp" in
                *\>/dev/sd*|*\>/dev/nvme*|*\>/dev/mmcblk*|*\>/dev/loop*\
                |*\>/dev/ram*|*\>/dev/zram*|*\>/dev/vd*|*\>/dev/xvd*|*\>/dev/blk*)
                    __vge_check=1 ;;
                */dev/sd*|*/dev/nvme*|*/dev/mmcblk*|*/dev/loop*\
                |*/dev/ram*|*/dev/zram*|*/dev/vd*|*/dev/xvd*|*/dev/blk*)
                    case "$__vge_nosp" in
                        *">"*) __vge_check=1 ;;
                    esac ;;
            esac
        fi

        #   c) 前缀执行器包裹的危险命令（注意这里必须用原始 $__cmd 匹配，
        #      去空白的副本连 "env" 这个词都拼不出来了）
        if [ "$__vge_check" = 0 ]; then
            case "$__cmd" in
                env\ *|nohup\ *|nice\ *|setsid\ *|stdbuf\ *|timeout\ *|time\ *\
                |xargs\ *|busybox\ *|eval\ *|command\ *|exec\ *|watch\ *|strace\ *)
                    __VGE_strip_prefix "$__cmd"
                    case "${__VGE_STRIPPED%%[[:space:]]*}" in
                        rm|mv|chmod|chown|su|sudo|pkexec|doas|dd|mkswap|setprop\
                        |mkfs|mkfs.*|newfs_msdos|fdisk|sfdisk|cfdisk|gdisk|sgdisk|parted\
                        |wipefs|shred|cryptsetup|insmod|rmmod|modprobe\
                        |shutdown|reboot|poweroff|halt)
                            __vge_check=1 ;;
                    esac ;;
            esac
        fi

        #   d) 其余交互命令放行 —— 性能考虑, 不把每条命令都送去 TCP 检测
        if [ "$__vge_check" = 0 ]; then
            __VGE_IN_TRAP=
            return 0
        fi

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
        # 先执行原 handler, 再执行我们的检测逻辑
        # 注意: vge_trap_debug 函数体已经包含检测逻辑, 链式时不需要再 eval 原 handler
        # 因为我们用 trap 'vge_trap_debug' DEBUG 覆盖了之前的 trap
        # 原 handler 的函数名如果是 _omb_xxx 之类, 会被我们的白名单跳过
        vge_dlog "trap: orig DEBUG handler exists: ${__VGE_orig_debug:0:60}"
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
