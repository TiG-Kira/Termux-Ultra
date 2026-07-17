#!/data/data/com.termux/files/usr/bin/bash
set -e

CONTAINER_DIR="$HOME/debian-container"
RUN_SCRIPT="$CONTAINER_DIR/run.sh"
SCRIPT_TO_RUN="$1"

if [ ! -f "$RUN_SCRIPT" ]; then
    echo "Error: Container not found."
    echo "Please install the container first by running install_linux_container.sh"
    exit 1
fi

if [ -z "$SCRIPT_TO_RUN" ]; then
    echo "Usage: $0 <script_path>"
    echo "No script specified, entering container shell..."
    "$RUN_SCRIPT"
    exit 0
fi

if [ ! -f "$SCRIPT_TO_RUN" ]; then
    echo "Error: Script not found: $SCRIPT_TO_RUN"
    exit 1
fi

SCRIPT_NAME=$(basename "$SCRIPT_TO_RUN")
SHARED_DIR="$CONTAINER_DIR/rootfs/root/shared"

mkdir -p "$SHARED_DIR"
cp "$SCRIPT_TO_RUN" "$SHARED_DIR/"

chmod +x "$SHARED_DIR/$SCRIPT_NAME" 2>/dev/null || true

CONTAINER_SCRIPT="/root/shared/$SCRIPT_NAME"

echo "Running script in container: $CONTAINER_SCRIPT"
"$RUN_SCRIPT" "$CONTAINER_SCRIPT"
