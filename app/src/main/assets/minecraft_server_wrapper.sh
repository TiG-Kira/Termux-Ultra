#!/bin/bash
# Minecraft Server Wrapper - 确保不以 root 身份运行 Minecraft 服务器

set -e

SCRIPT_TO_RUN="$1"

if [ -z "$SCRIPT_TO_RUN" ]; then
    echo "ERROR: No script specified"
    exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "Running as non-root user ($(whoami)), proceeding..."
    exec bash "$SCRIPT_TO_RUN"
fi

echo "Running as root, checking for non-root users..."

NON_ROOT_USERS=$(awk -F: '$3 >= 1000 && $3 < 65534 && $7 != "/usr/sbin/nologin" && $7 != "/bin/false" {print $1}' /etc/passwd | grep -v root || true)

if [ -z "$NON_ROOT_USERS" ]; then
    echo ""
    echo "No non-root user found. Creating 'minecraft' user..."
    useradd -m -s /bin/bash minecraft
    echo "minecraft:minecraft" | chpasswd
    usermod -aG sudo minecraft 2>/dev/null || true
    echo ""
    echo "========================================="
    echo "Created new user for Minecraft server:"
    echo "  Username: minecraft"
    echo "  Password: minecraft"
    echo "========================================="
    echo ""
    RUN_USER="minecraft"
else
    USER_COUNT=$(echo "$NON_ROOT_USERS" | wc -l)
    if [ "$USER_COUNT" -eq 1 ]; then
        RUN_USER="$NON_ROOT_USERS"
        echo "Found non-root user: $RUN_USER"
    else
        echo ""
        echo "Found multiple non-root users:"
        echo "$NON_ROOT_USERS" | nl
        echo ""
        echo "Using first user: $(echo "$NON_ROOT_USERS" | head -1)"
        RUN_USER=$(echo "$NON_ROOT_USERS" | head -1)
    fi
fi

echo "Switching to user '$RUN_USER' to run Minecraft server..."
echo ""

USER_HOME=$(eval echo "~$RUN_USER")
chown -R "$RUN_USER:$RUN_USER" "$USER_HOME" 2>/dev/null || true

exec su - "$RUN_USER" -c "cd '$USER_HOME' && bash '$SCRIPT_TO_RUN'"
