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
#   5. 「临时存储区」(tmp/) 易失：影子写副本、删除 whiteout、导入样本都落于此；
#      会话退出（EXIT trap）整体清空，一切修改即消失。
#
# 最小快照拆分（用户需求落点）：
#   - essential/  ← 持久，仅 su/sudo 及篡改组件（必要更改区）
#   - tmp/        ← 易失，影子写 / whiteout / 运行时 / 导入（临时存储区）
#   - 用户区（$HOME / $PREFIX）← 链接视图，写入被影子化
#
# 初始化可观测性（本次重点）：
#   交互式进入时，控制台先渲染一块**实时进度面板**（步骤列表 + 转圈 + 进度条 + 耗时），
#   逐推进、逐点亮；结束后整块擦除，恢复常规控制台并交出命令输入提示。
#   每一步都带**硬超时**：超时或失败只降级告警，绝不阻塞。proot 正式拉起前还会做一次
#   带超时的「可用性冒烟」——冒烟不过就直接降级为轻量影子模式，**保证用户一定能拿到
#   可输入的 shell**，绝不会停在「只有横幅、没有提示符」的黑屏状态。
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
#   VORTEX_UI_OFF            1=关闭初始化进度面板（纯文本输出）
#   VORTEX_PROOT_TIMEOUT     冒烟校验超时秒数（默认 20）
# ============================================================================

VORTEX_ROOT="${VORTEX_ROOT:-$(cd "$(dirname "$0")/.." 2>/dev/null && pwd)}"
[ -z "$VORTEX_ROOT" ] && VORTEX_ROOT="$HOME/.vortex"

REAL_HOME="${VORTEX_REAL_HOME:-/data/data/com.termux/files/home}"
REAL_PREFIX="${VORTEX_REAL_PREFIX:-/data/data/com.termux/files/usr}"
REAL_STORAGE="/storage/emulated/0"

# 「必要更改区」：持久化，仅装 su/sudo 模拟器与伪装 ROOT 组件。
ESSENTIAL="$VORTEX_ROOT/essential"
# 「临时存储区」：会话级、易失。影子写副本 / whiteout / 导入样本。
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
PROOT_TIMEOUT="${VORTEX_PROOT_TIMEOUT:-20}"

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

# 手动会话存活标记：--interactive 启动时创建，退出回收时摘除。
# 一次性运行（--run）与手动会话共用 tmp/：其 EXIT trap 见到本标记即
# 只清哨兵、不碰 tmp/——否则会把活会话的影子存储区连锅端。
ACTIVE_SESSION_MARKER="$TMP/.vortex_active_session"
VORTEX_SESSION_OWNER=0

# ---------------------------------------------------------------------------
# 环境准备
# ---------------------------------------------------------------------------

have_proot() {
    [ "$VORTEX_FORCE_NO_PROOT" = "1" ] && return 1
    command -v proot >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# 初始化进度面板
# ---------------------------------------------------------------------------
# 只在「交互式 + stdout 是终端 + TERM 非 dumb」时启用；其余场景（--run 被解析输出、
# 重定向到文件、Agent 调用）自动退化为纯文本，绝不往管道里灌 ANSI。
#
# 面板为**固定高度**（标题 + 分隔 + N 步 + 进度条 + 分隔），行数可预先算出，
# 结束时精确上移擦除，不留残影、不吞掉后续 shell 的输出。

VS_TOTAL=7
VS_ST1=o; VS_ST2=o; VS_ST3=o; VS_ST4=o; VS_ST5=o; VS_ST6=o; VS_ST7=o
VS_X1=_;  VS_X2=_;  VS_X3=_;  VS_X4=_;  VS_X5=_;  VS_X6=_;  VS_X7=_

VORTEX_UI=0
VORTEX_UI_LINES=0
VORTEX_SPIN_I=0
VORTEX_T0=0

# 毫秒时间戳：date 支持 %N 时用纳秒精度，否则退化为秒。
vs_now() {
    _n=$(date +%s%N 2>/dev/null)
    case "$_n" in
        '' | *[!0-9]*) printf '%s' "$(( $(date +%s) * 1000 ))" ;;
        *) printf '%s' "$(( _n / 1000000 ))" ;;
    esac
}

# 毫秒 → "1.2s"
vs_ms() {
    printf '%d.%ds' "$(( $1 / 1000 ))" "$(( ($1 % 1000) / 100 ))"
}

vs_ui_init() {
    VORTEX_UI=0
    [ "${VORTEX_UI_OFF:-0}" = "1" ] && return 0
    [ -t 1 ] 2>/dev/null || return 0
    case "${TERM:-dumb}" in dumb | "") return 0 ;; esac
    VORTEX_UI=1
    VORTEX_T0=$(vs_now)
    printf '\033[?25l'    # 隐藏光标，避免面板刷新时光标乱跳
}

vs_ui_fini() {
    [ "$VORTEX_UI" = 1 ] || return 0
    vs_clear
    printf '\033[?25h'    # 恢复光标
    VORTEX_UI=0
}

# 擦掉已绘制的面板：上移 N 行并清到屏幕底。
vs_clear() {
    [ "$VORTEX_UI" = 1 ] || return 0
    if [ "$VORTEX_UI_LINES" -gt 0 ] 2>/dev/null; then
        printf '\033[%sA\033[J' "$VORTEX_UI_LINES"
    fi
    VORTEX_UI_LINES=0
}

vs_set() { eval "VS_ST$1=\$2"; eval "VS_X$1=\$3"; }
vs_get_st() { eval "_vs_v=\$VS_ST$1"; }

vs_spin() {
    case $(( VORTEX_SPIN_I % 10 )) in
        0) printf '\342\240\213' ;; 1) printf '\342\240\231' ;; 2) printf '\342\240\271' ;;
        3) printf '\342\240\270' ;; 4) printf '\342\240\274' ;; 5) printf '\342\240\264' ;;
        6) printf '\342\240\246' ;; 7) printf '\342\240\247' ;; 8) printf '\342\240\207' ;;
        9) printf '\342\240\217' ;;
    esac
    VORTEX_SPIN_I=$(( VORTEX_SPIN_I + 1 ))
}

vs_row() {
    _ri=$1
    vs_get_st "$_ri"; _rst=$_vs_v
    eval "_rx=\$VS_X$_ri"
    case "$_ri" in
        1) _rlb='环境探测' ;;
        2) _rlb='播种必要更改区' ;;
        3) _rlb='准备临时存储区' ;;
        4) _rlb='生成伪ROOT身份' ;;
        5) _rlb='构造proot绑定' ;;
        6) _rlb='校验proot可用性' ;;
        7) _rlb='启动沙箱shell' ;;
        *) _rlb='' ;;
    esac
    case "$_rst" in
        '+') printf '  \033[32m\342\234\224\033[0m %s' "$_rlb" ;;
        '!') printf '  \033[33m!\033[0m \033[33m%s\033[0m' "$_rlb" ;;
        'x') printf '  \033[31m\342\234\230\033[0m \033[31m%s\033[0m' "$_rlb" ;;
        'r') printf '  \033[36m%s\033[0m %s' "$(vs_spin)" "$_rlb" ;;
        *)   printf '  \033[2m\342\227\213 %s\033[0m' "$_rlb" ;;
    esac
    [ "$_rx" != "_" ] && printf ' \033[2m\302\267 %s\033[0m' "$_rx"
    printf '\n'
}

vs_render() {
    [ "$VORTEX_UI" = 1 ] || return 0
    vs_clear
    _ln=0
    printf '\033[1;36mVorteX Sandbox\033[0m \033[2m\302\267 沙箱初始化\033[0m\n'; _ln=$((_ln + 1))
    printf '\033[2m────────────────────────────────\033[0m\n';                   _ln=$((_ln + 1))
    _ri=1
    while [ "$_ri" -le "$VS_TOTAL" ]; do
        vs_row "$_ri"
        _ln=$((_ln + 1))
        _ri=$((_ri + 1))
    done
    _done=0
    _ri=1
    while [ "$_ri" -le "$VS_TOTAL" ]; do
        vs_get_st "$_ri"
        case "$_vs_v" in '+' | '!') _done=$((_done + 1)) ;; esac
        _ri=$((_ri + 1))
    done
    _bw=24
    _f=$(( _done * _bw / VS_TOTAL ))
    _e=$(( _bw - _f ))
    printf '  '
    _k=0; while [ "$_k" -lt "$_f" ]; do printf '\033[36m\342\226\223\033[0m'; _k=$((_k + 1)); done
    _k=0; while [ "$_k" -lt "$_e" ]; do printf '\033[2m\342\226\221\033[0m'; _k=$((_k + 1)); done
    printf ' \033[2m%d/%d\033[0m' "$_done" "$VS_TOTAL"
    if [ "$VORTEX_T0" -gt 0 ] 2>/dev/null; then
        printf ' \033[2m\302\267 %s\033[0m' "$(vs_ms $(( $(vs_now) - VORTEX_T0 )) )"
    fi
    printf '\n'; _ln=$((_ln + 1))
    printf '\033[2m────────────────────────────────\033[0m\n';                   _ln=$((_ln + 1))
    VORTEX_UI_LINES=$_ln
}

vs_plain() {
    # 非面板模式下的纯文本进度（--run / 重定向 / Agent 场景）
    [ "$VORTEX_UI" = 1 ] && return 0
    printf '[VorteX Sandbox] %s\n' "$1" >&2
}

# 后台跑一段命令并**边等边转圈**；$1=步骤号 $2=超时秒 其余=命令。
# 超时后强杀并返回 124，绝不无限等待——这是「任何环节都不得卡死」的落点。
vs_step() {
    _sn=$1; shift
    _sto=$1; shift
    vs_set "$_sn" r _
    vs_plain "… $* (≤${_sto}s)"
    _sf="$VORTEX_ROOT/.vortex_step.$$"
    rm -f "$_sf" 2>/dev/null
    ( "$@" >/dev/null 2>&1; echo $? > "$_sf" ) &
    _spid=$!
    _st0=$(vs_now)
    _guard=0
    _max=$(( _sto * 10 + 40 ))
    while [ ! -s "$_sf" ] && [ "$_guard" -lt "$_max" ]; do
        vs_set "$_sn" r "$(vs_ms $(( $(vs_now) - _st0 )) )"
        vs_render
        _guard=$(( _guard + 1 ))
        sleep 0.1
    done
    if [ ! -s "$_sf" ]; then
        kill -9 "$_spid" 2>/dev/null
        echo 124 > "$_sf" 2>/dev/null
    fi
    wait "$_spid" 2>/dev/null
    _src=$(cat "$_sf" 2>/dev/null)
    rm -f "$_sf" 2>/dev/null
    case "${_src:-1}" in
        0)   vs_set "$_sn" + "$(vs_ms $(( $(vs_now) - _st0 )) )" ; _rc=0 ;;
        124) vs_set "$_sn" ! "超时${_sto}s" ; _rc=124 ;;
        *)   vs_set "$_sn" ! "rc=${_src:-1}" ; _rc=${_src:-1} ;;
    esac
    vs_render
    return $_rc
}

# 同步小步骤：标为运行中→渲染→执行→标记结果（毫秒级，不进后台）。
vs_quick() {
    _sn=$1; shift
    vs_set "$_sn" r _
    vs_plain "… $*"
    _st0=$(vs_now)
    vs_render
    "$@"
    _rc=$?
    if [ "$_rc" = 0 ]; then
        vs_set "$_sn" + "$(vs_ms $(( $(vs_now) - _st0 )) )"
    else
        vs_set "$_sn" ! "rc=$_rc"
    fi
    vs_render
    return $_rc
}

# ---------------------------------------------------------------------------
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
# 原理：**不再**用 proot 把整个 /proc 绑成软链农场。那样会让
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
            Uid:*)    printf 'Uid:\t0\t0\t0\t0\n' ;;
            Gid:*)    printf 'Gid:\t0\t0\t0\t0\n' ;;
            Groups:*) printf 'Groups:\t0\n' ;;
            *)        printf '%s\n' "$fs_line" ;;
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

# 只有当**宿主侧来源路径确实存在**时才追加绑定：proot 绑到不存在的宿主路径会静默
# 失效甚至让路径翻译落空，直接跳过更安全。
vs_bind() {
    [ -e "$1" ] || return 0
    PROOT_BINDS_STR="$PROOT_BINDS_STR -b $1:$2"
}

build_proot_binds() {
    PROOT_BINDS_STR="-b /dev -b /sys -b /system -b /apex -b /proc"

    if [ "$ISOLATE_STORAGE" = "1" ]; then
        vs_bind "$RUN_STORAGE" "$REAL_STORAGE"
    fi

    if seed_essential; then
        vs_bind "$ESSENTIAL/su"   /system/bin/su
        vs_bind "$ESSENTIAL/su"   /system/xbin/su
        vs_bind "$ESSENTIAL/su"   /sbin/su
        vs_bind "$ESSENTIAL/sudo" /system/bin/sudo
        vs_bind "$ESSENTIAL/sudo" /system/xbin/sudo
        vs_bind "$ESSENTIAL/sudo" /sbin/sudo
    else
        echo "[VorteX Sandbox] 警告：必要更改区播种失败，su/sudo 模拟器将不可用。" >&2
    fi
}

# 生成 proot 冒烟脚本：用与正式启动完全相同的参数拉起一个瞬时命令，
# 命中约定输出才算通过。写成独立文件是为了让 `timeout` 能直接杀到 proot 进程本身
# （脚本内 exec 掉 sh，proot 即 timeout 的直接子进程）。
build_smoke_script() {
    _smoke="$VORTEX_ROOT/.vortex_smoke.$$"
    _sb="$REAL_PREFIX/bin/echo"
    [ -x "$_sb" ] || _sb=/system/bin/echo
    [ -x "$_sb" ] || return 1
    {
        printf '_out=$("%s" %s -0 %s -w "%s" "%s" VORTEX_PROOT_OK 2>/dev/null)\n' \
            "$_proot_bin" "$_popt_link2symlink" "$PROOT_BINDS_STR" "$REAL_HOME" "$_sb"
        printf 'case "$_out" in *VORTEX_PROOT_OK*) exit 0 ;; esac\n'
        printf 'exit 1\n'
    } > "$_smoke" 2>/dev/null || return 1
    echo "$_smoke"
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
    # proot 自身的临时目录（--link2symlink 链接农场）必须落在真实可写目录上。
    mkdir -p "$REAL_PREFIX/tmp" 2>/dev/null
    PROOT_TMP_DIR="$REAL_PREFIX/tmp"
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
    export HOME PREFIX PATH LD_LIBRARY_PATH TMPDIR PROOT_TMP_DIR SHELL TERM LANG
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
# 轻量降级方案（proot 不可用 / 冒烟失败）：仍注入 LD_PRELOAD 影子写
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
}

# ---------------------------------------------------------------------------
# 交互式初始化流水线（全程可见 + 每步硬超时）
# ---------------------------------------------------------------------------
vs_probe_env() {
    mkdir -p "$ESSENTIAL" "$TMP" 2>/dev/null || return 1
    [ -d "$REAL_HOME" ] || return 1
    return 0
}

init_interactive() {
    vs_ui_init

    HAVE_PROOT=0
    have_proot && HAVE_PROOT=1
    SMOKE_OK=0

    # 1 环境探测
    vs_quick 1 vs_probe_env

    # 2 播种必要更改区
    vs_quick 2 seed_essential

    # 3 准备临时存储区
    vs_quick 3 seed_storage

    # 4 生成伪 ROOT 身份
    vs_quick 4 seed_fake_proc

    # 5 构造 proot 绑定
    vs_quick 5 build_proot_binds

    # 6 proot 可用性冒烟（带硬超时，绝不让流程停在这一步）
    if [ "$HAVE_PROOT" = "1" ]; then
        _proot_bin=$(command -v proot 2>/dev/null)
        [ -n "$_proot_bin" ] || _proot_bin=proot
        _popt_link2symlink=""
        if "$_proot_bin" --link2symlink --version >/dev/null 2>&1; then
            _popt_link2symlink="--link2symlink"
        fi
        _smoke=$(build_smoke_script)
        if [ -n "$_smoke" ]; then
            if command -v timeout >/dev/null 2>&1; then
                vs_step 6 "$PROOT_TIMEOUT" timeout "$PROOT_TIMEOUT" /system/bin/sh "$_smoke"
            else
                vs_step 6 "$PROOT_TIMEOUT" /system/bin/sh "$_smoke"
            fi
            [ $? = 0 ] && SMOKE_OK=1
            rm -f "$_smoke" 2>/dev/null
        else
            vs_set 6 ! '无冒烟程序'
            vs_render
        fi
    else
        vs_set 6 ! '未安装proot'
        vs_render
    fi
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
# 面板擦除放在 reset_run 开头：任何退出路径都不会把面板/隐藏光标留在终端上。
trap 'reset_run' EXIT INT TERM

reset_run() {
    vs_ui_fini
    if [ "$VORTEX_SESSION_OWNER" = "1" ]; then
        # 手动会话退出：摘自己的标记，走完整回收。
        rm -f "$ACTIVE_SESSION_MARKER" 2>/dev/null
        rm -rf "$TMP" 2>/dev/null
        rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
    elif [ -f "$ACTIVE_SESSION_MARKER" ]; then
        # 一次性运行退出，但手动会话还活着：tmp/ 是它的运行时，绝不能动。
        rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
    else
        rm -rf "$TMP" 2>/dev/null
        rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
    fi
    rm -f "$VORTEX_ROOT/.vortex_step.$$" "$VORTEX_ROOT/.vortex_smoke.$$" 2>/dev/null
}

banner() {
    printf '\n[VorteX Sandbox] %s\n' "$1"
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
            printf '[VorteX Sandbox] 已进入轻量隔离（仅 LD_PRELOAD 影子写，未安装 proot）\n' >&2
            "$REAL_PREFIX/bin/bash" -c "$CMD"
        fi
        ;;

    --interactive|"")
        # 标记本进程为手动会话所有者：退出时完整回收；其间到来的一次性运行
        # （--run）见到标记则只清哨兵，不碰共享的 tmp/。
        : > "$ACTIVE_SESSION_MARKER" 2>/dev/null
        VORTEX_SESSION_OWNER=1

        init_interactive

        # 第 7 步：真正拉起 shell。到这一步为止，前面任何失败都只是降级告警，
        # 不会把用户丢在一个没有提示符的控制台里。
        vs_set 7 r _
        vs_render

        if [ "$SMOKE_OK" = "1" ]; then
            # 并发回收可能已把 tmp/ 清掉，正式启动前补一次，保证绑定与伪身份文件就位。
            mkdir -p "$TMP" "$RUN_STORAGE" 2>/dev/null
            seed_fake_proc 2>/dev/null
            build_proot_binds 2>/dev/null
            vs_set 7 + _
            vs_render
            vs_ui_fini
            banner 'proot 隔离 + LD_PRELOAD 影子写已启用（虚拟 ROOT，会话结束自动清空）'
            printf '[VorteX Sandbox] 真实 %s 以链接视图呈现：写入被影子化到临时存储区，底层数据不变。\n' "$REAL_HOME"
            printf '[VorteX Sandbox] 身份伪造已启用：/proc/self/attr/current=u:r:su:s0，uid=0；\n'
            printf '[VorteX Sandbox] su/sudo 为沙箱内模拟执行（持久于 essential/），不调用真实特权命令。\n'
            printf '[VorteX Sandbox] 本会话的所有改动在退出时彻底消失，临时存储区 (tmp/) 被删除。\n\n'
            run_in_proot -i
        else
            setup_fallback
            if [ "$HAVE_PROOT" = "1" ]; then
                vs_set 7 ! 'proot不可用·已降级'
            else
                vs_set 7 + '轻量模式'
            fi
            vs_render
            vs_ui_fini
            banner '已进入轻量隔离沙箱（仅 LD_PRELOAD 影子写）'
            if [ "$HAVE_PROOT" = "1" ]; then
                printf '[VorteX Sandbox] proot 可用性校验未通过（超时 %ss 或无法拉起子进程），已自动降级，\n' "$PROOT_TIMEOUT"
                printf '[VorteX Sandbox] 避免停在无提示的控制台。受控目录（$HOME / $PREFIX）内的写入仍会被影子化。\n\n'
            else
                printf '[VorteX Sandbox] 未检测到 proot；请执行 `pkg install proot` 后重试以获得完整隔离。\n'
                printf '[VorteX Sandbox] 当前绝对路径不被 ptrace 屏蔽，但受控目录内的写入仍会被影子化。\n\n'
            fi
            "$REAL_PREFIX/bin/bash" -i
        fi
        ;;

    *)
        echo "VorteX Sandbox: unknown argument '$1'" >&2
        exit 1
        ;;
esac
