#!/bin/bash

export DEBIAN_FRONTEND=noninteractive

echo "=== 开始安装 QEMU ==="

echo "1. 更新软件包列表..."
apt update -y

echo "2. 升级现有软件包..."
apt upgrade -y

echo "3. 安装 QEMU 和相关工具..."
apt install -y --no-install-recommends \
    qemu-system-x86 \
    qemu-utils \
    qemu-system-gui \
    genisoimage \
    curl \
    wget \
    ca-certificates

echo ""
echo "=== QEMU 安装完成 ==="
echo "可以在容器中运行 qemu-system-x86_64 命令启动虚拟机"
