#!/data/data/com.termux/files/usr/bin/bash

echo "=== 开始安装 QEMU ==="

echo "1. 检查并配置软件源..."
if ! grep -q 'mirrors.tuna.tsinghua.edu.cn/termux/termux-packages-24' $PREFIX/etc/apt/sources.list; then
    echo "添加清华源..."
    sed -i 's@^\(deb.*stable main\)$@#\1\ndeb https://mirrors.tuna.tsinghua.edu.cn/termux/termux-packages-24 stable main@' $PREFIX/etc/apt/sources.list
else
    echo "清华源已配置"
fi

echo "2. 更新软件包列表..."
pkg update

echo "3. 升级现有软件包..."
pkg upgrade -y

echo "4. 安装扩展源..."
pkg install unstable-repo x11-repo

echo "5. 安装 QEMU..."
pkg install qemu-system-x86-64 qemu-utils -y

echo ""
echo "=== QEMU 安装完成 ==="
echo "可以在终端中运行 qemu-system-x86_64 命令启动虚拟机"