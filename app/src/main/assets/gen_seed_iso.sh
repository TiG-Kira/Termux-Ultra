#!/bin/bash
# 在 Debian 容器内生成 cloud-init seed.iso
# 此脚本由 run_in_container.sh 调用，在容器内运行

set -e

echo "=== Generating cloud-init seed.iso in container ==="

# 检查 genisoimage 是否可用
if ! command -v genisoimage &>/dev/null; then
    echo "ERROR: genisoimage not found in container"
    echo "Please install it: apt install -y genisoimage"
    exit 1
fi

# 创建临时目录
WORK_DIR="/tmp/cloud-init-seed"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

# 生成 user-data
cat > user-data <<EOF
#cloud-config
hostname: docker-phone
users:
  - name: debian
    lock_passwd: false
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
chpasswd:
  list: |
    root:dockerphone
    debian:dockerphone
  expire: False
ssh_pwauth: true
disable_root: false
runcmd:
  - sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
  - sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config
  - systemctl restart sshd
package_update: true
packages:
  - qemu-guest-agent
  - ca-certificates
  - curl
growpart:
  mode: auto
  devices: ['/']
EOF

# 生成 meta-data
cat > meta-data <<EOF
instance-id: docker-phone-001
local-hostname: docker-phone
EOF

# 生成 seed.iso
echo "Running genisoimage..."
genisoimage -output seed.iso -volid "CIDATA" -joliet -rock user-data meta-data

# 将 seed.iso 复制到共享目录
SHARED_DIR="/root/shared"
mkdir -p "$SHARED_DIR"
cp seed.iso "$SHARED_DIR/seed.iso"

echo ""
echo "seed.iso generated successfully at: $SHARED_DIR/seed.iso"
echo "Size: $(ls -lh seed.iso | awk '{print $2}')"

# 清理
rm -rf "$WORK_DIR"
echo "=== seed.iso generation complete ==="
