#!/system/bin/sh
# ============================================================================
# VorteX Sandbox Bootstrap  (proot-based)
# ----------------------------------------------------------------------------
# 目标：
#   1. 沙箱内所有修改（文件写入、配置变更、乃至虚拟 ROOT 下的删除）都局限于沙箱内部，
#      **包括使用绝对路径的删除操作**——这是早期仅重定向 $HOME 方案最大的漏洞。
#   2. 沙箱内保留用户完整的 Termux 环境：直接复用真实 $PREFIX（usr/bin、lib、pkg 全部可用），
#      不复制、不重装，因此 $PREFIX 体积不翻倍。
#   3. 提供虚拟 ROOT：proot -0 让所有进程以 uid 0 视角运行，
#      并伪造 /proc/self（SELinux 上下文 u:r:su:s0、status Uid=0）与 su/sudo 模拟器，
#      让脚本确信运行在真实 root 环境中，而特权命令只在沙箱容器内模拟执行。
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
RUN_PREFIX="$VORTEX_ROOT/run/usr"
RUN_STORAGE="$VORTEX_ROOT/run/storage"
SNAP_STORAGE="$VORTEX_ROOT/snapshot/storage"
ISOLATE_STORAGE="${VORTEX_ISOLATE_STORAGE:-1}"

# 影子 /proc（虚拟 ROOT 身份伪造）与 su/sudo 模拟脚本所在目录。
RUN_PROC="$VORTEX_ROOT/run/proc"
TOOLS_DIR="$VORTEX_ROOT/run/tools"
PROCWATCH_PIDFILE="$VORTEX_ROOT/.vortex_procwatch_pid"
# 为「接下来 N 个 PID」预留占位视图，消除新进程刚 fork、影子目录尚未建好的竞态。
PROCWATCH_PENDING_K=16
# 真实 /proc 在沙箱内的进程别名（影子条目经它软链回真实 /proc）。
# 放在 /dev 下：-b /dev 的绑定前缀更短，proot 取最长匹配，此处不会被旁路。
REAL_PROC_ALIAS="/dev/.vortex_realproc"

REAL_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
REAL_STORAGE="/storage/emulated/0"

# 关键：**必须自建 PATH**。
# 会话由 libterminal 直接 exec 本脚本，继承的 PATH 可能为空或不含 $PREFIX/bin，
# 此时 `command -v proot` 会找不到 proot → 静默降级成弱隔离（危险）。
# 所以这里无条件把 $PREFIX/bin 放到最前，保证 proot 探测可靠。
case ":$PATH:" in
    *":$REAL_PREFIX/bin:"*) ;;
    *) PATH="$REAL_PREFIX/bin:$PATH" ;;
esac
export PATH

mkdir -p "$SNAP" "$RUN" 2>/dev/null

# 完成标记：只有播种全部成功才落。
#
# 为什么不能只靠「目录非空」判断就绪：拷贝中途被杀死（ANR 闪退、应用被杀、
# 磁盘满）会留下**半成品目录**，它同样是非空的。若据此认定「已就绪、跳过播种」，
# 残缺状态会被固化，之后每次进沙箱都从这堆残缺数据播种——用户在 .bashrc 里
# 引用的脚本（ty-hook.sh、broot launcher 等）就集体消失，且**永不恢复**。
# 这正是「首次开启沙箱闪退一次后功能彻底崩掉」的根因。
SNAP_MARKER="$SNAP/.vortex_snapshot_ok"
PREFIX_MARKER="$RUN_PREFIX/.vortex_prefix_ok"
RUN_MARKER="$RUN/.vortex_run_ok"

# 内存储告警哨兵：脚本侧无法直接驱动 UI 的 Snackbar，
# 因此把「内存储已被重定向」这一事实写进哨兵文件，由宿主侧轮询后转成 Snackbar。
NOTICE_FILE="$VORTEX_ROOT/.vortex_storage_notice"

# ---------------------------------------------------------------------------
# 快照 / 重置
# ---------------------------------------------------------------------------

# 从快照播种 run 层。
# 判据是标记文件而非目录非空：半成品 run 层必须被丢弃重播，否则用户环境残缺。
seed_run() {
    if [ -f "$RUN_MARKER" ]; then
        return 0
    fi
    # 无标记却非空 → 上次播种中途夭折，清干净重来。
    if [ -n "$(ls -A "$RUN" 2>/dev/null)" ]; then
        rm -rf "$RUN" 2>/dev/null
        mkdir -p "$RUN" 2>/dev/null
    fi
    # 播种用 tar 管道，并**排除 storage**：Termux 的 $HOME/storage 是指向
    # /storage/emulated/0 的符号链接，一旦被穿透就会把用户整个内存储
    # 拖进沙箱可写层（可达数 GB），且沙箱内的写入会污染真实内存储。
    # 沙箱内的 $HOME/storage 由 seed_storage 单独用空目录占位。
    (cd "$SNAP" && tar --exclude=./storage -cf - . 2>/dev/null) | (cd "$RUN" && tar -xf - 2>/dev/null) || true
    # storage 占位：只建软链形态的入口，不复制内容。
    if [ ! -e "$RUN/storage" ]; then
        mkdir -p "$RUN/storage" 2>/dev/null || true
    fi
    if [ ! -f "$SNAP_MARKER" ]; then
        # 快照不完整（多半是首次初始化时被 ANR 闪退打断）。
        # run 层此时也必然残缺，如实告知用户——宁可让用户知道环境不完整，
        # 也不要让他对着一个「凭空少了几十个脚本」的环境一头雾水。
        echo "[VorteX Sandbox] 警告：初始快照不完整，\$HOME 中可能有文件缺失。" >&2
        echo "[VorteX Sandbox] 提示：到「设置 → 安全设置 → VorteX Sandbox」关闭再开启以重建快照。" >&2
    fi
    : > "$RUN_MARKER" 2>/dev/null || true
}

reset_run() {
    # 沙箱只是**临时**占用空间：会话一结束就把影子层全部清掉，
    # 包括约 95MB 的 $PREFIX 拷贝——否则用户会平白看到近百 MB 不明占用。
    #
    # 先停影子 /proc 的 watcher，再删 run：否则 watcher 可能在删除途中
    # 又把 PID 目录补回来，留下删不干净的残余。
    stop_procwatch
    # run/ 这一层空壳目录也一并删掉：只删子项会留下几 KB 的空目录，
    # 在「沙箱是否还在占用空间」这个问题上给出模棱两可的答案。
    rm -rf "$VORTEX_ROOT/run" 2>/dev/null
    # 通知标记也一并清掉：下次进沙箱是全新会话，
    # 内存储告警应当重新提示一次。
    rm -f "$NOTICE_FILE" "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null
}

# ---------------------------------------------------------------------------
# 环境准备
# ---------------------------------------------------------------------------

have_proot() {
    [ "$VORTEX_FORCE_NO_PROOT" = "1" ] && return 1
    command -v proot >/dev/null 2>&1
}

# 内存储重定位：把真实内存储路径 bind 到一个**空临时目录**。
#
# 为什么是「空目录」而不是「影子拷贝」：
#   - 影子拷贝意味着把用户动辄几十 GB 的内存储复制一遍，既慢又毫无意义；
#   - 沙箱的定位是「用户 Termux 环境的隔离预演」，内存储不在其列；
#   - 需求明确：**沙箱内坚决不允许访问 Android 内部存储**。
#
# 因此这里 bind 到空目录：路径可见、内容为空，用户的任何访问都读不到东西，
# 与「不存在」相比又能保留路径结构，避免脚本因路径缺失而行为诡异。
#
# 副作用正是我们要的：访问必然落空，用户立刻知道内存储不可用。
# 同时写哨兵文件，由宿主侧转成 Snackbar 明确告知原因与替代方案。
seed_storage() {
    [ "$ISOLATE_STORAGE" = "1" ] || return 0
    mkdir -p "$RUN_STORAGE" 2>/dev/null
    # 保持为空。不播种任何内容——这正是本函数的核心语义。
    notice_storage_blocked
}

# 记录「内存储已被重定位」这一事实，供宿主侧转成 Snackbar。
# 首次进入沙箱时提示一次即可（每次都提示会很吵）。
notice_storage_blocked() {
    [ -f "$VORTEX_ROOT/.vortex_storage_notified" ] && return 0
    echo "blocked" > "$NOTICE_FILE" 2>/dev/null || true
    : > "$VORTEX_ROOT/.vortex_storage_notified" 2>/dev/null || true
}

# ===========================================================================
# 虚拟 ROOT 身份伪造：影子 /proc + su/sudo 模拟
# ===========================================================================
# 目标：让沙箱内的脚本确信自己运行在**真实的 Android root 设备**上：
#   1. cat /proc/self/attr/current   → u:r:su:s0（root shell 的 SELinux 上下文）
#   2. /proc/self/status 的 Uid/Gid  → 0
#   3. su / sudo                      → 只在 proot 容器内模拟执行，
#                                       绝不触达真实特权命令（不逃逸），
#                                       原样返回被调命令的输出与退出码。
#
# 原理：proot 的路径解析逐组件进行，一旦某前缀命中绑定，剩余组件直接落到
# 绑定目标上（不再经过 /proc/self → /proc/<pid> 的扩展改写）。因此整体把
# /proc 绑到影子目录 $RUN_PROC 后：
#   - 影子内预置真实目录 self / thread-self，attr/current、status 在其中被伪造，
#     其余条目软链到真实 /proc 的进程别名 $REAL_PROC_ALIAS（内容动态、真实）；
#   - 显式 /proc/<pid> 读取（ps、cat /proc/$$）需要影子里存在对应 PID 目录：
#     启动时全量建立，随后由后台 watcher 持续补全新 PID、回收死 PID；
#     为消除「新进程刚 fork、影子目录还没建好」的竞态，watcher 还会为
#     接下来 PROCWATCH_PENDING_K 个 PID 预留结构完整的占位视图；
#   - 非本应用 UID 的进程（老 Android 无 hidepid 时可见）保持真实软链，
#     避免把别人的进程也伪装成 root 而露馅。
#
# 降级：影子 /proc 建不起来时退回真实 -b /proc（身份伪造失效但隔离不受影响）。

# 把 status 中的身份字段归零，其余字段保持真实（UID/GID/Groups → root）。
fake_status_file() {
    # $1 = 真实 status 路径；$2 = 输出路径
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

# 伪造 SELinux 上下文：current = u:r:su:s0（root shell），其余文件留空可读写。
write_fake_attr() {
    # $1 = 影子 attr 目录
    mkdir -p "$1" 2>/dev/null || return 1
    printf 'u:r:su:s0' > "$1/current" || return 1
    for wa_f in exec fscreate keycreate prev sockcreate lsm; do
        [ -e "$1/$wa_f" ] || : > "$1/$wa_f" 2>/dev/null
    done
    return 0
}

# 构造单个 PID（或 self）的影子视图：
#   - attr/current 与 status 伪造为 root；
#   - cwd/exe/root 这类「路径字符串」型软链复制真实目标串（readlink 输出正常）；
#   - 其余条目软链到 $REAL_PROC_ALIAS/<pid>/<name>，内容按读取者动态解析。
shadow_pid_view() {
    # $1 = 影子目录；$2 = 真实路径（/proc/<pid> 或 /proc/self）
    sv_d="$1"
    sv_src="$2"
    sv_suffix="${sv_src#/proc}"
    mkdir -p "$sv_d" 2>/dev/null || return 1
    write_fake_attr "$sv_d/attr" || return 1
    fake_status_file "$sv_src/status" "$sv_d/status" || rm -f "$sv_d/status"
    # 路径型软链：复制真实目标串，readlink 出来是正常路径。
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

# 为「尚未存在」的 PID 预留占位视图：结构完整（attr/status/软链齐全），
# 进程真正 fork 出来时目录已在，watcher 随后再用真实数据精化。
# 占位的 stat/cmdline 等软链指向尚不存在的 PID → 读取即 ENOENT，
# ps 之类工具会自动跳过，不会把占位当成幽灵进程列出来。
make_pending_view() {
    # $1 = 影子目录；$2 = 预留的 PID
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

# 从 status 中读出真实 Uid（shell 内建实现，零外部依赖）。
read_real_uid() {
    while IFS= read -r ru_line; do
        case "$ru_line" in
            Uid:*) set -- $ru_line; printf '%s' "$2"; return 0 ;;
        esac
    done < "$1" 2>/dev/null
    return 1
}

# 预留接下来 K 个 PID 的占位视图。
add_pending_views() {
    # $1 = 当前最大 PID
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

# 扫描一轮：补建缺失 PID、精化占位、回收死 PID、为下一批 PID 预留占位。
# （sc_pending 为跨轮持久的占位 PID 清单，watcher 子进程内维护）
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
            # 已有视图：若它是占位且真实进程已出现 → 用真实数据精化。
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

        # 影子中缺失 → 新进程：本应用 UID 的伪造为 root 视图，其余保持真实软链。
        sc_uid=$(read_real_uid "$sc_e/status")
        if [ "$sc_uid" = "$SC_OWN_UID" ]; then
            shadow_pid_view "$sc_d" "$sc_e"
        else
            ln -sfn "$REAL_PROC_ALIAS/$sc_pid" "$sc_d" 2>/dev/null
        fi
    done

    # 回收死 PID 的影子目录（占位的等它真正出现，不清）。
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

# 后台 watcher：持续 procwatch_scan，维持影子 /proc 与真实进程表同步。
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
        pw_old=$(cat "$PROCWATCH_PIDFILE" 2>/dev/null)
        [ -n "$pw_old" ] && kill "$pw_old" 2>/dev/null
        rm -f "$PROCWATCH_PIDFILE"
    fi
    : > "$PROCWATCH_PIDFILE" 2>/dev/null || return 1
    ( procwatch_loop ) >/dev/null 2>&1 &
    echo $! > "$PROCWATCH_PIDFILE" 2>/dev/null
    return 0
}

stop_procwatch() {
    if [ -f "$PROCWATCH_PIDFILE" ]; then
        pw_old=$(cat "$PROCWATCH_PIDFILE" 2>/dev/null)
        [ -n "$pw_old" ] && kill "$pw_old" 2>/dev/null
        rm -f "$PROCWATCH_PIDFILE"
    fi
    return 0
}

# 建立影子 /proc 并启动 watcher；任何一步失败返回非 0（调用方退回真实 /proc）。
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
# su / sudo 模拟（不逃逸、不真实执行特权命令）
# ---------------------------------------------------------------------------
# proot 模式：命令在容器内执行（伤害被影子层吸收），原样透传输出与退出码。
# 轻量降级模式（无 proot、无容器可隔离）：只模拟、不执行，返回成功值。
#
# 覆盖两处入口，双保险：
#   1. PATH：把模拟脚本种进影子 $PREFIX/bin（沙箱 PATH 首段）；
#   2. 绝对路径：绑定覆盖 /system/bin|/system/xbin|/sbin 下的 su、sudo，
#      即便脚本绕过 PATH 用绝对路径调用，拿到的也是模拟器。
plant_su_mocks() {
    mkdir -p "$TOOLS_DIR" 2>/dev/null || return 1

    cat > "$TOOLS_DIR/su" <<'VORTEX_EOF'
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
# `su <user> [cmd...]`：第一个参数若不是可执行命令，则视为用户名忽略。
if [ $# -gt 0 ] && ! command -v "$1" >/dev/null 2>&1; then
    shift
fi
# 这里**不能用 exec "$@"**：exec 只做文件执行（查 PATH，不认内建命令），
# `su -c` 常见用法里的 echo / cd 等内建会变成 "not found"。
# 直接执行则走正常的命令查找顺序（函数 → 内建 → PATH），退出码原样透传。
if [ $# -gt 0 ]; then
    "$@"
    exit $?
fi
exec "${SHELL:-/system/bin/sh}" -i
VORTEX_EOF

    cat > "$TOOLS_DIR/sudo" <<'VORTEX_EOF'
#!/system/bin/sh
# VorteX 沙箱 sudo 模拟器：只在沙箱容器内执行，绝不调用真实 sudo。
# 选项解析后，被提权的命令在沙箱容器内运行，输出与退出码原样返回。
vc_login=0
vc_shell=0
while [ $# -gt 0 ]; do
    case "$1" in
        -u|-g) shift 2 ;;              # 目标用户被忽略：沙箱内已是虚拟 ROOT
        -i) vc_login=1; shift ;;
        -s) vc_shell=1; shift ;;
        -V|--version)
            echo "VorteX sandbox sudo (simulated) 1.0"
            exit 0 ;;
        -h|--help)
            echo "usage: sudo [-u user] [-g group] [-i|-s] command [args]"
            exit 0 ;;
        -n|-E|-H|-k|-K|-b|-S) shift ;; # 常用开关：静默忽略
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
# 不用 exec "$@"：exec 不认内建命令（echo 等会 not found），
# 直接执行才能得到与真实 sudo 一致的输出与退出码。
"$@"
exit $?
VORTEX_EOF

    chmod 0755 "$TOOLS_DIR/su" "$TOOLS_DIR/sudo" 2>/dev/null || return 1

    # 种进影子 $PREFIX/bin：沙箱 PATH 首段命中（覆盖可能存在的真实拷贝）。
    if [ -d "$RUN_PREFIX/bin" ]; then
        cp -f "$TOOLS_DIR/su" "$RUN_PREFIX/bin/su" 2>/dev/null
        cp -f "$TOOLS_DIR/sudo" "$RUN_PREFIX/bin/sudo" 2>/dev/null
        chmod 0755 "$RUN_PREFIX/bin/su" "$RUN_PREFIX/bin/sudo" 2>/dev/null
    fi
    return 0
}

# 构造 proot 的 bind 参数数组。真实路径一律换成「宿主可见」的真实值，
# proot -b 不会跟随符号链接，所以 $HOME/storage 的真身要单独 bind。
# 构造 proot 的 bind 参数（POSIX 兼容：不能使用 bash 数组，
# 因为脚本 shebang 是 /system/bin/sh 即 mksh，数组语法会报 "not found"）。
# 结果写入 PROOT_BINDS_STR，以空格分隔（路径均不含空格）。
build_proot_binds() {
    # 关键：**不能** `-b /data`。
    # 真实 HOME 就在 /data/data/com.termux/files/home，整挂 /data 会让真实路径重新可见，
    # 影子绑定被旁路，绝对路径隔离直接失效。因此只精确绑定 $PREFIX。
    #
    # /sdcard 不单独 bind：现代 Android 上它只是 /storage/emulated/0 的符号链接，
    # 且 Termux 沙箱上下文对其无权限，bind 反而会触发 proot warning。
    PROOT_BINDS_STR="-b /dev -b /sys -b /system -b /apex"

    # 影子 /proc（虚拟 ROOT 身份伪造）：attr/current → u:r:su:s0、status Uid=0。
    # 建不起来则退回真实 -b /proc（身份伪造失效，但隔离不受影响）。
    if seed_shadow_proc; then
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $RUN_PROC:/proc -b /proc:$REAL_PROC_ALIAS"
    else
        echo "[VorteX Sandbox] 警告：影子 /proc 不可用，/proc/self 身份伪造已停用。" >&2
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b /proc"
    fi

    # 影子 HOME：沙箱内所有 /data/data/com.termux/files/home 路径都落到这里
    PROOT_BINDS_STR="$PROOT_BINDS_STR -b $RUN:$REAL_HOME"

    # 影子 $PREFIX（$RUN_PREFIX）。
    #
    # 为什么必须影子化：proot 的 -b 是可写绑定，若直接把真实 $PREFIX 挂进去，
    # 沙箱内的虚拟 ROOT 就能 `rm -rf $PREFIX` 把真实 Termux 本体删掉——
    # 实测 T6 确实删光了 files/usr。
    #
    # 为什么必须是**真实拷贝**而不是硬链接：`cp -al` 只防删除，防不住原地改写。
    # 硬链接共享 inode，沙箱里 `echo x > $PREFIX/bin/xxx` 会直接写穿到真实文件，
    # 违背「所有改动在沙箱会话完全结束后消失」这一硬要求。
    # 因此这里用 `cp -a` 真拷贝（usr 约 95MB），会话重置时整体丢弃。
    # 拷贝失败或 inode 自检不通过 → 不 bind prefix（此时沙箱内 $PREFIX 不可见，
    # 命令会报 no such file，比"写穿到真实文件"安全得多）。
    if seed_prefix; then
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $RUN_PREFIX:$REAL_PREFIX"
    else
        echo "[VorteX Sandbox] 警告：\$PREFIX 影子层不可用，沙箱内 \$PREFIX 将不可见。" >&2
    fi

    # 影子内存储
    if [ "$ISOLATE_STORAGE" = "1" ]; then
        seed_storage
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $RUN_STORAGE:$REAL_STORAGE"
    fi

    # su / sudo 模拟：绝对路径入口一律绑定到模拟器（真身绝不进入沙箱），
    # PATH 入口由 plant_su_mocks 种进影子 $PREFIX/bin（上面 seed_prefix 之后）。
    if plant_su_mocks; then
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $TOOLS_DIR/su:/system/bin/su -b $TOOLS_DIR/su:/system/xbin/su -b $TOOLS_DIR/su:/sbin/su"
        PROOT_BINDS_STR="$PROOT_BINDS_STR -b $TOOLS_DIR/sudo:/system/bin/sudo -b $TOOLS_DIR/sudo:/system/xbin/sudo -b $TOOLS_DIR/sudo:/sbin/sudo"
    else
        echo "[VorteX Sandbox] 警告：su/sudo 模拟器生成失败，沙箱内特权命令将不可用。" >&2
    fi
}

# 播种影子 $PREFIX（真实拷贝，非硬链接）。
#
# 顺序很关键：先探测 cp -a 是否真正产生独立 inode，若是硬链接/引用则拒绝使用，
# 宁可退化为「不 bind prefix、$PREFIX 保持真实」也不能给出假的隔离承诺。
seed_prefix() {
    mkdir -p "$RUN_PREFIX" 2>/dev/null
    [ -f "$PREFIX_MARKER" ] && return 0
    # 无标记却非空 → 上次拷贝中途夭折的残缺层（例如上一轮 ANR 闪退留下的）。
    # 残缺的 $PREFIX 会让沙箱内出现「一半命令找不到」的怪现象，必须整体重来。
    if [ -n "$(ls -A "$RUN_PREFIX" 2>/dev/null)" ]; then
        rm -rf "$RUN_PREFIX" 2>/dev/null
        mkdir -p "$RUN_PREFIX" 2>/dev/null
    fi

    cp -a "$REAL_PREFIX/." "$RUN_PREFIX/" 2>/dev/null || {
        rm -rf "$RUN_PREFIX" 2>/dev/null
        mkdir -p "$RUN_PREFIX" 2>/dev/null
        return 1
    }

    # 自检：影子里的可执行文件必须与真实文件 inode 不同，否则说明拷贝退化成了硬链接。
    _real_bin="$REAL_PREFIX/bin/bash"
    _shadow_bin="$RUN_PREFIX/bin/bash"
    if [ -e "$_real_bin" ] && [ -e "$_shadow_bin" ]; then
        _ri=$(stat -c %i "$_real_bin" 2>/dev/null)
        _si=$(stat -c %i "$_shadow_bin" 2>/dev/null)
        if [ -n "$_ri" ] && [ "$_ri" = "$_si" ]; then
            echo "[VorteX Sandbox] 错误：\$PREFIX 影子层与真实文件共享 inode（硬链接退化），" >&2
            echo "[VorteX Sandbox] 已放弃 \$PREFIX 影子化以避免给出虚假的隔离承诺。" >&2
            rm -rf "$RUN_PREFIX" 2>/dev/null
            return 1
        fi
    fi
    : > "$PREFIX_MARKER" 2>/dev/null || true
    return 0
}

# 组装并执行 proot。$1 = 传给 bash 的模式参数（-i / -c ...）
run_in_proot() {
    # 先播种影子层：没有这一步 proot 里的 $HOME 会是空目录，
    # 用户在沙箱内看不到自己的任何文件（环境就"不完整"了）。
    seed_run

    build_proot_binds
    unset LD_PRELOAD

    # 环境在宿主侧导出后由 proot 继承。
    # 不用 `env -i`：精简 bootstrap 里可能没有 coreutils 的 env，
    # 且 proot 对 `-b /data` + `-b $PREFIX` 叠加时的可执行路径解析不稳。
    #
    # 先记下宿主 PATH 并定位 proot：下面会把 PATH 收窄成沙箱内的样子
    # （$PREFIX/bin:/system/bin），proot 结束后必须恢复，否则脚本退出时的
    # reset trap（rm、stop_procwatch）会因找不到命令而清不掉影子空间。
    _host_path="$PATH"
    _proot_bin=$(command -v proot 2>/dev/null)
    [ -n "$_proot_bin" ] || _proot_bin=proot
    HOME="$REAL_HOME"
    PREFIX="$REAL_PREFIX"
    PATH="$REAL_PREFIX/bin:/system/bin"
    LD_LIBRARY_PATH="$REAL_PREFIX/lib"
    TMPDIR="$REAL_PREFIX/tmp"
    SHELL="$REAL_PREFIX/bin/bash"
    TERM="${TERM:-xterm-256color}"
    LANG="${LANG:-en_US.UTF-8}"
    VORTEX_SANDBOX=1
    VORTEX_VIRTUAL_ROOT=1
    VORTEX_ROOT="$VORTEX_ROOT"
    VORTEX_PROOT=1
    export HOME PREFIX PATH LD_LIBRARY_PATH TMPDIR SHELL TERM LANG
    export VORTEX_SANDBOX VORTEX_VIRTUAL_ROOT VORTEX_ROOT VORTEX_PROOT

    # 关键：**不能** `-b /data`。
    # 真实 HOME 就在 /data/data/com.termux/files/home，整挂 /data 会让真实路径重新可见，
    # 影子绑定被旁路，绝对路径隔离直接失效。因此只精确绑定 $PREFIX。
    #
    # /sdcard 不单独 bind：现代 Android 上它只是 /storage/emulated/0 的符号链接，
    # 且 Termux 沙箱上下文对其无权限，bind 反而会触发 proot warning。
    # --link2symlink 是 Termux proot 的补丁选项（app 数据分区不支持硬链接，
    # 需要把硬链接降级成软链接），上游/发行版 proot 并没有——不探测就带上会
    # 直接 `unknown option` 启动失败，整个沙箱都进不去。这里实测一次再决定。
    _popt_link2symlink=""
    if "$_proot_bin" --link2symlink --version >/dev/null 2>&1; then
        _popt_link2symlink="--link2symlink"
    fi
    "$_proot_bin" $_popt_link2symlink \
        -0 \
        $PROOT_BINDS_STR \
        -w "$REAL_HOME" \
        "$REAL_PREFIX/bin/bash" "$@"
    _vortex_rc=$?
    PATH="$_host_path"
    export PATH
    return $_vortex_rc
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
    fi
    # 轻量模式没有 proot 容器可隔离，特权命令**只模拟、不执行**：
    # 打印模拟提示并返回成功值，绝不触达真实 su/sudo（不逃逸、不真实执行）。
    cat > "$VORTEX_BIN/su" <<'VORTEX_EOF'
#!/system/bin/sh
echo "[VorteX Sandbox] su 已模拟执行（轻量模式不执行真实特权命令）：$*" >&2
exit 0
VORTEX_EOF
    cat > "$VORTEX_BIN/sudo" <<'VORTEX_EOF'
#!/system/bin/sh
echo "[VorteX Sandbox] sudo 已模拟执行（轻量模式不执行真实特权命令）：$*" >&2
exit 0
VORTEX_EOF
    chmod 0755 "$VORTEX_BIN/id" "$VORTEX_BIN/whoami" "$VORTEX_BIN/su" "$VORTEX_BIN/sudo" 2>/dev/null
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
        echo "shadow_prefix=$RUN_PREFIX -> $REAL_PREFIX"
        echo "shadow_proc=$RUN_PROC (attr/current=u:r:su:s0, status Uid=0)"
        echo "su_sudo=simulated (容器内模拟执行，不调用真实特权命令)"
        [ "$ISOLATE_STORAGE" = "1" ] && echo "shadow_storage=$RUN_STORAGE -> $REAL_STORAGE" || echo "shadow_storage=shared($REAL_STORAGE)"
    else
        echo "isolation=fallback"
        echo "shadow_home=$RUN (绝对路径不隔离)"
        echo "su_sudo=simulated (轻量模式只模拟、不执行)"
    fi
    echo "real_home=$REAL_HOME"
    echo "snapshot=$SNAP"
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

# 无论正常退出、命令失败还是被信号打断，都必须清掉影子空间。
# 沙箱只是临时占用空间：一旦沙箱不运行了，影子 $PREFIX（~95MB）必须消失。
# 用 trap 而非在分支里手写 reset_run —— 后者在 exec / 信号路径上会漏掉。
trap 'reset_run' EXIT INT TERM

case "$1" in
    --check)
        # 自检不应触发清理（此时沙箱并未运行）
        trap - EXIT INT TERM
        do_check
        ;;

    --run)
        shift
        CMD="$1"
        # 单次调用（插件 / Agent）走这里：命令结束即视为沙箱结束，
        # EXIT trap 会把影子 prefix 一并清掉，不留 ~95MB 残留。
        if have_proot; then
            run_in_proot -c "$CMD"
        else
            setup_fallback
            "$REAL_PREFIX/bin/bash" -c "$CMD"
        fi
        ;;

    --interactive|"")
        if have_proot; then
            printf '\n[VorteX Sandbox] proot 隔离已启用（虚拟 ROOT，会话结束自动清空并回收空间）\n'
            printf '[VorteX Sandbox] 真实 %s 已被影子层遮蔽，删除与改写都不会泄漏。\n' "$REAL_HOME"
            printf '[VorteX Sandbox] 身份伪造已启用：/proc/self/attr/current=u:r:su:s0，uid=0；\n'
            printf '[VorteX Sandbox] su/sudo 为沙箱内模拟执行，不调用真实特权命令。\n'
            printf '[VorteX Sandbox] 本会话的所有改动在退出时彻底消失，影子 $PREFIX 也会被删除。\n\n'
            run_in_proot -i
        else
            setup_fallback
            printf '\n[VorteX Sandbox] 已进入轻量隔离沙箱（仅 $HOME 隔离）\n\n'
            "$REAL_PREFIX/bin/bash" -i
        fi
        ;;

    *)
        echo "VorteX Sandbox: unknown argument '$1'" >&2
        exit 1
        ;;
esac
