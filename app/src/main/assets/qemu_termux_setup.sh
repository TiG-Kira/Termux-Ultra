#!/data/data/com.termux/files/usr/bin/bash
# QEMU on Termux Setup (Debian)
# 使用 Ubuntu 容器生成 cloud-init seed.iso

set -e

echo "=== QEMU on Termux Setup (Debian) ==="

# ========== 检测 Android 版本，决定使用原生还是容器 ==========
ANDROID_SDK=$(getprop ro.build.version.sdk 2>/dev/null || \
    cat /system/build.prop 2>/dev/null | grep 'ro.build.version.sdk' | cut -d= -f2 || \
    echo 0)
USE_CONTAINER=0
if [ "$ANDROID_SDK" -ge 35 ] 2>/dev/null; then
    USE_CONTAINER=1
    echo "  检测到 Android 15+ (SDK $ANDROID_SDK)，将使用 proot 容器运行 QEMU"
    echo "  原因: Android 15+ 的 linker namespace 限制导致 Termux 原生 QEMU 无法链接系统库"
else
    echo "  检测到 Android (SDK $ANDROID_SDK)，将使用 Termux 原生 QEMU"
fi

# 检测 Ubuntu 容器是否存在
CONTAINER_DIR="$HOME/debian-container"
RUN_SCRIPT="$CONTAINER_DIR/run.sh"

if [ "$USE_CONTAINER" = "1" ]; then
    # ========== Android 17: 检查/创建容器 + 安装容器内 QEMU ==========
    if [ ! -f "$RUN_SCRIPT" ] || [ ! -f "$CONTAINER_DIR/rootfs/bin/bash" ]; then
        echo ""
        echo "  proot 容器不存在，开始安装..."
        if [ -f "$HOME/install_linux_container.sh" ]; then
            bash "$HOME/install_linux_container.sh"
        else
            echo "ERROR: install_linux_container.sh not found!"
            echo "Please go to Resources page and click \"Ubuntu 容器安装\" first."
            exit 1
        fi
    fi
    echo "  proot 容器已就绪"

    # 修复 run.sh 中的旧包名
    if grep -q "com\.termux\.ultra" "$RUN_SCRIPT" 2>/dev/null; then
        echo "  Fixing old package name references in run.sh..."
        sed -i 's/com\.termux\.ultra/com.termux/g' "$RUN_SCRIPT"
    fi

    # 检查容器内是否安装了 QEMU
    echo ""
    echo "[1/7] 检查容器内 QEMU 安装..."
    if ! "$RUN_SCRIPT" -c 'command -v qemu-system-aarch64' >/dev/null 2>&1; then
        echo "  容器内未安装 QEMU (aarch64)，正在安装 (需要几分钟)..."
        "$RUN_SCRIPT" -c 'export DEBIAN_FRONTEND=noninteractive; apt update -y && apt install -y --no-install-recommends qemu-system-arm qemu-system-gui qemu-utils genisoimage qemu-efi-aarch64 curl wget ca-certificates'
        if ! "$RUN_SCRIPT" -c 'command -v qemu-system-aarch64' >/dev/null 2>&1; then
            echo "  尝试安装 qemu-system-x86 (x86_64)..."
            "$RUN_SCRIPT" -c 'export DEBIAN_FRONTEND=noninteractive; apt install -y --no-install-recommends qemu-system-x86 qemu-system-gui qemu-utils genisoimage qemu-efi-aarch64 curl wget ca-certificates'
            if ! "$RUN_SCRIPT" -c 'command -v qemu-system-aarch64' >/dev/null 2>&1 && \
               ! "$RUN_SCRIPT" -c 'command -v qemu-system-x86_64' >/dev/null 2>&1; then
                echo "  ERROR: 容器内 QEMU 安装失败"
                exit 1
            fi
        fi
    fi
    echo "  容器内 QEMU 已就绪"

    # 确定 QEMU 架构（Debian QEMU 默认使用 aarch64，因为镜像为 arm64）
    if "$RUN_SCRIPT" -c 'command -v qemu-system-aarch64' >/dev/null 2>&1; then
        QEMU_BIN="qemu-system-aarch64"
        QEMU_ARCH="aarch64"
    else
        QEMU_BIN="qemu-system-x86_64"
        QEMU_ARCH="x86_64"
    fi
    echo "  使用 $QEMU_BIN ($QEMU_ARCH)"
else
    # ========== Android <= 14: 原生 Termux QEMU ==========
    if [ ! -f "$RUN_SCRIPT" ] || [ ! -f "$CONTAINER_DIR/rootfs/bin/bash" ]; then
        echo ""
        echo "ERROR: Ubuntu container not found!"
        echo "Please go to Resources page and click \"Ubuntu 容器安装\" first."
        echo ""
        exit 1
    fi
    echo "  Ubuntu container found."

    # 自动修复 run.sh 中的旧包名路径
    if grep -q "com.termux.ultra" "$RUN_SCRIPT" 2>/dev/null; then
        echo "  Fixing old package name references in run.sh..."
        sed -i 's/com\.termux\.ultra/com.termux/g' "$RUN_SCRIPT"
        echo "  run.sh updated successfully."
    fi

    echo ""
    echo "[1/6] Installing Termux repository packages..."
    pkg install -y unstable-repo x11-repo 2>/dev/null || {
        pkg update -y
        pkg install -y unstable-repo x11-repo
    }

    echo ""
    echo "[2/6] Updating package list..."
    pkg update -y

    echo ""
    echo "[3/6] Installing QEMU and dependencies..."
    pkg install -y qemu-system-aarch64 qemu-utils python curl wget openssh termux-api 2>/dev/null || {
        echo "  Trying x86_64 version..."
        pkg install -y qemu-system-x86-64 qemu-utils python curl wget openssh termux-api
    }

    if command -v qemu-system-aarch64 &>/dev/null; then
        QEMU_BIN="qemu-system-aarch64"
        QEMU_ARCH="aarch64"
    else
        QEMU_BIN="qemu-system-x86_64"
        QEMU_ARCH="x86_64"
    fi
    echo "  Using $QEMU_BIN"
fi

# ========== 公共部分: 下载/复用 Debian 镜像 ==========
VM_DIR="$HOME/qemu-vm"
IMG="$VM_DIR/debian-12-arm64.qcow2"
DEBIAN_URL="https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"
MIRROR_URL="https://mirrors.tuna.tsinghua.edu.cn/debian-cloud/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"

DOWNLOAD_NEEDED=0
SKIP_TO_BOOTSCRIPT=0

if [[ -f "$IMG" ]]; then
    echo ""
    echo "  WARNING: Debian QEMU container already exists!"
    read -p "  Do you want to reinstall (delete existing and start fresh)? [y/N] " -n1 -r
    echo ""
    if [[ "$REPLY" =~ ^[Yy]$ ]]; then
        echo "  Removing existing container..."
        read -p "  Delete downloaded image file ($(du -h "$IMG" | awk '{print $1}'))? [y/N] " -n1 -r
        echo ""
        if [[ "$REPLY" =~ ^[Yy]$ ]]; then
            rm -rf "$VM_DIR"
            mkdir -p "$VM_DIR"
            DOWNLOAD_NEEDED=1
        else
            rm -rf "$VM_DIR/seed.iso" "$VM_DIR/edk2-vars.fd" 2>/dev/null || true
            DOWNLOAD_NEEDED=0
        fi
    else
        echo "  Skipping download, using existing container..."
        # 询问是否仅更新启动脚本（不重装镜像，只重新生成 boot-qemu.sh / vm_boot.sh）
        read -p "  是否更新启动脚本和容器配置 (不影响已有虚拟机数据)? [Y/n] " -n1 -r
        echo ""
        if [[ "$REPLY" =~ ^[Yy]$ ]] || [[ -z "$REPLY" ]]; then
            SKIP_TO_BOOTSCRIPT=1
        fi
    fi
else
    DOWNLOAD_NEEDED=1
    mkdir -p "$VM_DIR"
fi

if [[ "$DOWNLOAD_NEEDED" == "1" ]]; then
    cd "$VM_DIR" || exit 1
    echo "  Downloading Debian 12 arm64 cloud image (~1.2 GB)..."
    echo "  ========================================================"
    curl -L --progress-bar -o "$IMG" "$DEBIAN_URL" || {
        echo ""
        echo "  Failed to download, trying mirror..."
        curl -L --progress-bar -o "$IMG" "$MIRROR_URL" || {
            echo ""
            echo "  ERROR: Failed to download Debian image!"
            exit 1
        }
    }
    echo ""
else
    cd "$VM_DIR" || exit 1
fi

# 如果用户选择仅更新启动脚本，跳过下载/resize/seed.iso 生成
if [[ "$SKIP_TO_BOOTSCRIPT" == "1" ]]; then
    echo ""
    echo "  跳过镜像下载和 seed.iso 生成，仅更新启动脚本..."
    echo ""
    # 直接跳到启动脚本生成环节
    goto_bootscript=1
else
    goto_bootscript=0
fi

if [[ "$goto_bootscript" == "0" ]]; then

echo "  Resizing to 20 GB..."
if [ "$USE_CONTAINER" = "1" ]; then
    # 用容器内的 qemu-img resize
    if "$RUN_SCRIPT" -c 'command -v qemu-img' >/dev/null 2>&1; then
        "$RUN_SCRIPT" qemu-img resize "/root/shared/qemu-vm/debian-12-arm64.qcow2" 20G 2>/dev/null || \
            echo "  WARNING: qemu-img resize 失败，可能镜像已存在或格式不支持"
    else
        echo "  WARNING: 容器内 qemu-img 不可用，跳过 resize"
    fi
else
    if command -v qemu-img >/dev/null 2>&1; then
        qemu-img resize "$IMG" 20G 2>/dev/null || \
            echo "  WARNING: qemu-img resize 失败"
    else
        echo "  WARNING: qemu-img 不可用，跳过 resize"
    fi
fi

echo ""
echo "[5/6] Generating cloud-init seed.iso using Ubuntu container..."

# 检查生成脚本是否存在
GEN_SEED_SCRIPT="$HOME/gen_seed_iso.sh"
if [ ! -f "$GEN_SEED_SCRIPT" ]; then
    echo "  ERROR: gen_seed_iso.sh not found at $GEN_SEED_SCRIPT"
    exit 1
fi

chmod +x "$GEN_SEED_SCRIPT"

# 准备共享目录 - 使用 Termux 的 $HOME（容器内会挂载为 /root/shared）
SHARED_DIR="$HOME"

# 清理旧的 seed.iso
rm -f "$SHARED_DIR/seed.iso"

# 在容器内运行生成脚本（容器内 /root/shared 映射到 Termux 的 $HOME）
echo "  Running gen_seed_iso.sh in container..."
"$RUN_SCRIPT" "/root/shared/gen_seed_iso.sh" || {
    echo "  ERROR: Failed to run gen_seed_iso.sh in container!"
    echo "  Make sure QEMU (with genisoimage) is installed in container."
    echo "  Run 'QEMU 安装' in Resources page first."
    exit 1
}

# 检查 seed.iso 是否生成成功（文件在 Termux 的 $HOME/seed.iso）
if [ ! -f "$SHARED_DIR/seed.iso" ]; then
    echo "  ERROR: seed.iso was not generated!"
    echo "  Possible reasons:"
    echo "    1. genisoimage not installed in container"
    echo "    2. Container filesystem error"
    echo "    3. Script execution failed"
    echo ""
    echo "  Please run 'QEMU 安装' first to install genisoimage in container."
    exit 1
fi

# 复制 seed.iso 到 VM 目录
cp "$SHARED_DIR/seed.iso" "$VM_DIR/seed.iso"
SEED_ISO_SIZE=$(du -h "$VM_DIR/seed.iso" | awk '{print $1}')
echo "  seed.iso generated successfully ($SEED_ISO_SIZE)"

# 清理共享目录中的临时文件
rm -f "$SHARED_DIR/seed.iso"

fi  # end of: if [[ "$goto_bootscript" == "0" ]]

echo ""
echo "[6/6] Creating boot script..."

if [ "$USE_CONTAINER" = "1" ]; then
    # ========== Android 17: 生成容器内启动脚本 ==========
    # 检测/准备 UEFI 固件（aarch64 需要）
    if [[ "$QEMU_ARCH" == "aarch64" ]]; then
        # 容器内 qemu-efi-aarch64 包提供 /usr/share/qemu-efi-aarch64/QEMU_EFI.fd
        # 复制到 VM 目录，作为 code 和 vars 使用
        # 注意: QEMU aarch64 virt 板要求 pflash 至少 64MB，需用 truncate 填充
        CODE_FD="$VM_DIR/edk2-aarch64-code.fd"
        VARS_FD="$VM_DIR/edk2-vars.fd"

        if [[ ! -f "$CODE_FD" ]] || [[ $(stat -c%s "$CODE_FD" 2>/dev/null || echo 0) -lt 67108864 ]]; then
            echo "  提取容器内 UEFI 固件..."
            "$RUN_SCRIPT" -c 'cp /usr/share/qemu-efi-aarch64/QEMU_EFI.fd /root/shared/qemu-vm/edk2-aarch64-code.fd 2>/dev/null || echo "warn: UEFI firmware not found in container"'
            if [[ -f "$CODE_FD" ]]; then
                echo "  填充 UEFI 固件至 64MB..."
                truncate -s 64M "$CODE_FD" 2>/dev/null || \
                    "$RUN_SCRIPT" -c "truncate -s 64M /root/shared/qemu-vm/edk2-aarch64-code.fd 2>/dev/null || true"
            fi
        fi

        if [[ ! -f "$VARS_FD" ]] || [[ $(stat -c%s "$VARS_FD" 2>/dev/null || echo 0) -lt 67108864 ]]; then
            if [[ -f "$CODE_FD" ]]; then
                echo "  创建 UEFI vars 文件..."
                cp "$CODE_FD" "$VARS_FD"
            else
                echo "  WARNING: UEFI 固件不可用，QEMU 可能无法启动"
            fi
        fi
    fi

    # 写入容器内执行的 VM 启动脚本
    VM_BOOT_SCRIPT="$VM_DIR/vm_boot.sh"
    cat > "$VM_BOOT_SCRIPT" <<VMEOF
#!/bin/bash
set -e
echo "  [容器内] 启动 Debian QEMU 虚拟机..."
killall -9 $QEMU_BIN 2>/dev/null || true
sleep 1

# 容器内路径: Termux \$HOME -> /root/shared
VM_DIR_C="/root/shared/qemu-vm"
IMG_C="\$VM_DIR_C/debian-12-arm64.qcow2"
SEED_C="\$VM_DIR_C/seed.iso"
VMEOF

    if [[ "$QEMU_ARCH" == "aarch64" ]]; then
        cat >> "$VM_BOOT_SCRIPT" <<VMEOF
CODE_FD_C="\$VM_DIR_C/edk2-aarch64-code.fd"
VARS_FD_C="\$VM_DIR_C/edk2-vars.fd"

$QEMU_BIN \\
    -M virt -m 2G -cpu cortex-a72 -smp 2 \\
    -drive file="\$CODE_FD_C",format=raw,if=pflash,readonly=on \\
    -drive file="\$VARS_FD_C",format=raw,if=pflash \\
    -drive file="\$IMG_C",format=qcow2 \\
    -drive file="\$SEED_C",media=cdrom,format=raw \\
    -netdev user,id=net0,hostfwd=tcp::2222-:22 \\
    -device virtio-net-pci,netdev=net0,romfile= \\
    -serial mon:stdio \\
    -nographic
VMEOF
    else
        cat >> "$VM_BOOT_SCRIPT" <<VMEOF
$QEMU_BIN \\
    -M pc -m 2G -smp 2 \\
    -drive file="\$IMG_C",format=qcow2 \\
    -drive file="\$SEED_C",media=cdrom,format=raw \\
    -nic user,hostfwd=tcp::2222-:22 \\
    -serial mon:stdio \\
    -nographic
VMEOF
    fi

    chmod +x "$VM_BOOT_SCRIPT"

    # 生成 Termux 层的 boot-qemu.sh，它负责进入容器执行 vm_boot.sh
    cat > "$HOME/boot-qemu.sh" <<BOOTEOF
#!/data/data/com.termux/files/usr/bin/bash
# Android 17+ boot script: 通过 proot 容器启动 QEMU
echo "=== Debian QEMU (proot 容器模式, Android 17+) ==="
CONTAINER_DIR="\$HOME/debian-container"
RUN_SCRIPT="\$CONTAINER_DIR/run.sh"

if [ ! -f "\$RUN_SCRIPT" ]; then
    echo "ERROR: 容器不存在，请先执行 Debian QEMU 安装"
    exit 1
fi

# 修复旧包名
if grep -q "com\\.termux\\.ultra" "\$RUN_SCRIPT" 2>/dev/null; then
    sed -i 's/com\\.termux\\.ultra/com.termux/g' "\$RUN_SCRIPT"
fi

termux-wake-lock 2>/dev/null

# 进入容器执行 VM 启动脚本
# vm_boot.sh 在 \$HOME/qemu-vm/ 下，容器内通过 /root/shared 访问
exec "\$RUN_SCRIPT" "/root/shared/qemu-vm/vm_boot.sh"
BOOTEOF
    chmod +x "$HOME/boot-qemu.sh"

else
    # ========== Android <= 14: 原生 Termux QEMU ==========
    if [[ "$QEMU_ARCH" == "aarch64" ]]; then
        CODE_FD="$PREFIX/share/qemu/edk2-aarch64-code.fd"
        VARS_FD="$VM_DIR/edk2-vars.fd"

        # 确保 CODE_FD 至少 64MB（QEMU virt 板要求）
        if [[ -f "$CODE_FD" ]] && [[ $(stat -c%s "$CODE_FD" 2>/dev/null || echo 0) -lt 67108864 ]]; then
            echo "  填充 UEFI code 固件至 64MB..."
            truncate -s 64M "$CODE_FD" 2>/dev/null || true
        fi

        if [[ ! -f "$VARS_FD" ]] || [[ $(stat -c%s "$VARS_FD" 2>/dev/null || echo 0) -lt 67108864 ]]; then
            if [[ -f "$PREFIX/share/qemu/edk2-aarch64-vars.fd" ]] && [[ $(stat -c%s "$PREFIX/share/qemu/edk2-aarch64-vars.fd" 2>/dev/null || echo 0) -ge 67108864 ]]; then
                cp "$PREFIX/share/qemu/edk2-aarch64-vars.fd" "$VARS_FD"
            elif [[ -f "$CODE_FD" ]]; then
                echo "  创建 UEFI vars 文件 (64MB)..."
                dd if=/dev/zero of="$VARS_FD" bs=1M count=64 2>/dev/null || truncate -s 64M "$VARS_FD"
            else
                echo "  WARNING: UEFI firmware not found, QEMU may not boot"
            fi
        fi

        cat > "$HOME/boot-qemu.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
cd "$VM_DIR"
termux-wake-lock 2>/dev/null

$QEMU_BIN \\
    -M virt -m 2G -cpu cortex-a72 -smp 2 \\
    -drive file="$CODE_FD",format=raw,if=pflash,readonly=on \\
    -drive file="$VARS_FD",format=raw,if=pflash \\
    -drive file="$IMG",format=qcow2 \\
    -drive file="$VM_DIR/seed.iso",media=cdrom,format=raw \\
    -netdev user,id=net0,hostfwd=tcp::2222-:22 \\
    -device virtio-net-pci,netdev=net0,romfile= \\
    -serial mon:stdio \\
    -nographic
EOF
    else
        cat > "$HOME/boot-qemu.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
cd "$VM_DIR"
termux-wake-lock 2>/dev/null

$QEMU_BIN \\
    -M pc -m 2G -smp 2 \\
    -drive file="$IMG",format=qcow2 \\
    -nic user,hostfwd=tcp::2222-:22 \\
    -nographic
EOF
    fi
    chmod +x "$HOME/boot-qemu.sh"
fi

echo ""
echo "========================================="
if [[ "$SKIP_TO_BOOTSCRIPT" == "1" ]]; then
    echo "启动脚本更新完成！"
else
    echo "QEMU setup complete!"
fi
echo "========================================="
echo ""
echo "VM directory: $VM_DIR"
echo "Boot script: $HOME/boot-qemu.sh"
if [ "$USE_CONTAINER" = "1" ]; then
    echo "Mode: proot 容器 (Android 17+ 兼容)"
else
    echo "Mode: Termux 原生"
fi
if [[ "$SKIP_TO_BOOTSCRIPT" == "1" ]]; then
    echo "注意: 仅更新了启动脚本，虚拟机数据未变动"
fi
echo ""
echo "To start VM:"
echo "  bash $HOME/boot-qemu.sh"
echo ""
echo "========================================="
echo "DEBIAN LOGIN CREDENTIALS"
echo "========================================="
echo "  Username: debian"
echo "  Password: dockerphone"
echo ""
echo "  OR"
echo ""
echo "  Username: root"
echo "  Password: dockerphone"
echo "========================================="
echo ""
echo "SSH access (after VM boots):"
echo "  ssh debian@localhost -p 2222"
echo ""
read -p "Press any key to continue..." -n1 -s
echo ""
