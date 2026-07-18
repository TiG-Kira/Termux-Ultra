#!/data/data/com.termux/files/usr/bin/bash
# QEMU on Termux Setup (Debian)
# 使用 Debian 容器生成 cloud-init seed.iso

set -e

echo "=== QEMU on Termux Setup (Debian) ==="

# 检测 Debian 容器是否存在
CONTAINER_DIR="$HOME/debian-container"
RUN_SCRIPT="$CONTAINER_DIR/run.sh"

if [ ! -f "$RUN_SCRIPT" ] || [ ! -f "$CONTAINER_DIR/rootfs/bin/bash" ]; then
    echo ""
    echo "ERROR: Debian container not found!"
    echo "Please go to Resources page and click \"Debian 容器安装\" first."
    echo ""
    exit 1
fi
echo "  Debian container found."

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

echo ""
echo "[4/6] Setting up VM environment..."
VM_DIR="$HOME/qemu-vm"
IMG="$VM_DIR/debian-12-arm64.qcow2"
DEBIAN_URL="https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"
MIRROR_URL="https://mirrors.tuna.tsinghua.edu.cn/debian-cloud/images/cloud/bookworm/latest/debian-12-genericcloud-arm64.qcow2"

DOWNLOAD_NEEDED=0

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

echo "  Resizing to 20 GB..."
qemu-img resize "$IMG" 20G

echo ""
echo "[5/6] Generating cloud-init seed.iso using Debian container..."

# 检查生成脚本是否存在
GEN_SEED_SCRIPT="$HOME/gen_seed_iso.sh"
if [ ! -f "$GEN_SEED_SCRIPT" ]; then
    echo "  ERROR: gen_seed_iso.sh not found at $GEN_SEED_SCRIPT"
    exit 1
fi

# 准备 shared 目录
SHARED_DIR="$CONTAINER_DIR/rootfs/root/shared"
mkdir -p "$SHARED_DIR"

# 复制生成脚本到共享目录
cp "$GEN_SEED_SCRIPT" "$SHARED_DIR/gen_seed_iso.sh"
chmod +x "$SHARED_DIR/gen_seed_iso.sh"

# 清理旧的 seed.iso
rm -f "$SHARED_DIR/seed.iso"

# 在容器内运行生成脚本
echo "  Running gen_seed_iso.sh in container..."
"$RUN_SCRIPT" "/root/shared/gen_seed_iso.sh" || {
    echo "  ERROR: Failed to run gen_seed_iso.sh in container!"
    echo "  Make sure QEMU (with genisoimage) is installed in container."
    echo "  Run 'QEMU 安装' in Resources page first."
    exit 1
}

# 检查 seed.iso 是否生成成功
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
rm -f "$SHARED_DIR/seed.iso" "$SHARED_DIR/gen_seed_iso.sh"

echo ""
echo "[6/6] Creating boot script..."

if [[ "$QEMU_ARCH" == "aarch64" ]]; then
    CODE_FD="$PREFIX/share/qemu/edk2-aarch64-code.fd"
    VARS_FD="$VM_DIR/edk2-vars.fd"

    if [[ ! -f "$VARS_FD" ]]; then
        if [[ -f "$PREFIX/share/qemu/edk2-aarch64-vars.fd" ]]; then
            cp "$PREFIX/share/qemu/edk2-aarch64-vars.fd" "$VARS_FD"
        elif [[ -f "$PREFIX/share/qemu/edk2-aarch64-code.fd" ]]; then
            echo "  Creating UEFI vars file..."
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
    -nic user,model=virtio-net-pci,hostfwd=tcp::2222-:22 \\
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

echo ""
echo "========================================="
echo "QEMU setup complete!"
echo "========================================="
echo ""
echo "VM directory: $VM_DIR"
echo "Boot script: $HOME/boot-qemu.sh"
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
