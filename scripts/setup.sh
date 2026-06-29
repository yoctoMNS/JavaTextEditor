#!/bin/bash
# セットアップスクリプト: OpenJDK 21 src.zip を lib/ に配置する
# Linux / macOS / WSL 対応
set -e

DEST="$(cd "$(dirname "$0")/.." && pwd)/lib/src.zip"
mkdir -p "$(dirname "$DEST")"

if [ -f "$DEST" ]; then
    echo "Already exists: $DEST"
    exit 0
fi

echo "=== Setting up OpenJDK 21 source (src.zip) ==="

# 1. インストール済み JDK の lib/src.zip を探す
JAVA_HOME_CANDIDATE=""
if [ -n "$JAVA_HOME" ]; then
    JAVA_HOME_CANDIDATE="$JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN=$(command -v java)
    # シンボリックリンクを解決
    while [ -L "$JAVA_BIN" ]; do
        JAVA_BIN=$(readlink -f "$JAVA_BIN" 2>/dev/null || readlink "$JAVA_BIN")
    done
    JAVA_HOME_CANDIDATE=$(dirname "$(dirname "$JAVA_BIN")")
fi

for CANDIDATE in \
    "$JAVA_HOME_CANDIDATE/lib/src.zip" \
    "$JAVA_HOME_CANDIDATE/../lib/src.zip" \
    "$JAVA_HOME_CANDIDATE/../../lib/src.zip"
do
    if [ -f "$CANDIDATE" ]; then
        echo "Found: $CANDIDATE"
        cp "$CANDIDATE" "$DEST"
        echo "Copied to: $DEST"
        exit 0
    fi
done

# macOS: Homebrew の openjdk@21
for BREW_PREFIX in /opt/homebrew /usr/local; do
    CANDIDATE="$BREW_PREFIX/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/lib/src.zip"
    if [ -f "$CANDIDATE" ]; then
        echo "Found (Homebrew): $CANDIDATE"
        cp "$CANDIDATE" "$DEST"
        echo "Copied to: $DEST"
        exit 0
    fi
done

# Linux: apt パッケージ openjdk-21-source
if command -v apt-get >/dev/null 2>&1; then
    echo "Installing openjdk-21-source via apt..."
    sudo apt-get install -y openjdk-21-source
    # パッケージが src.zip を /usr/lib/jvm/*/lib/src.zip に置く
    for F in /usr/lib/jvm/java-21*/lib/src.zip /usr/lib/jvm/openjdk-21*/lib/src.zip; do
        if [ -f "$F" ]; then
            cp "$F" "$DEST"
            echo "Installed and copied to: $DEST"
            exit 0
        fi
    done
fi

# Linux: dnf/yum (Fedora/RHEL系)
if command -v dnf >/dev/null 2>&1; then
    echo "Installing java-21-openjdk-src via dnf..."
    sudo dnf install -y java-21-openjdk-src
    for F in /usr/lib/jvm/java-21*/lib/src.zip; do
        if [ -f "$F" ]; then
            cp "$F" "$DEST"
            echo "Installed and copied to: $DEST"
            exit 0
        fi
    done
fi

echo ""
echo "ERROR: src.zip not found automatically."
echo "Please place OpenJDK 21 src.zip manually:"
echo "  $DEST"
echo ""
echo "Download from: https://jdk.java.net/21/"
echo "  (or install: apt install openjdk-21-source / brew install openjdk@21)"
exit 1
