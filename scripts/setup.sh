#!/bin/bash
# セットアップスクリプト: OpenJDK 21 src.zip と native C ソースを lib/ に配置する
# Linux / macOS / WSL 対応
set -e

LIB_DIR="$(cd "$(dirname "$0")/.." && pwd)/lib"
mkdir -p "$LIB_DIR"

SRC_ZIP="$LIB_DIR/src.zip"
NATIVE_DIR="$LIB_DIR/openjdk-native"

# ---- ヘルパー関数 ----

fetch_native_via_git() {
    local DEST_DIR="$1"
    local WORK_DIR="$LIB_DIR/_openjdk_clone_tmp"
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"

    echo "Cloning openjdk/jdk (blob:none, depth=1) ..."
    if ! git clone \
            --no-checkout \
            --depth=1 \
            --filter=blob:none \
            --branch jdk-21+35 \
            https://github.com/openjdk/jdk.git \
            "$WORK_DIR" 2>&1; then
        rm -rf "$WORK_DIR"
        return 1
    fi

    echo "Configuring sparse-checkout for native sources ..."
    git -C "$WORK_DIR" sparse-checkout init --cone
    git -C "$WORK_DIR" sparse-checkout set \
        "src/java.base/share/native" \
        "src/java.base/unix/native" \
        "src/java.base/windows/native" \
        "src/java.desktop/share/native" \
        "src/java.desktop/unix/native" \
        "src/java.desktop/windows/native" \
        "src/java.lang.instrument/share/native" \
        "src/jdk.management/share/native" \
        "src/jdk.sctp/unix/native"

    git -C "$WORK_DIR" checkout
    mv "$WORK_DIR/src" "$DEST_DIR"
    rm -rf "$WORK_DIR"
    return 0
}

fetch_native_via_apt() {
    local DEST_DIR="$1"
    local WORK_DIR="$LIB_DIR/_apt_source_tmp"
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"

    echo "Downloading OpenJDK 21 source package via apt-get source ..."
    ( cd "$WORK_DIR" && apt-get source openjdk-21 2>&1 ) || {
        rm -rf "$WORK_DIR"
        return 1
    }

    local SRC_DIR
    SRC_DIR=$(find "$WORK_DIR" -maxdepth 1 -type d -name "openjdk-21*" | head -1)
    if [ -z "$SRC_DIR" ] || [ ! -d "$SRC_DIR/src" ]; then
        rm -rf "$WORK_DIR"
        return 1
    fi

    mkdir -p "$DEST_DIR"
    find "$SRC_DIR/src" -type d -name "native" | while read -r NATDIR; do
        REL="${NATDIR#$SRC_DIR/src/}"
        PARENT=$(dirname "$REL")
        mkdir -p "$DEST_DIR/$PARENT"
        cp -r "$NATDIR" "$DEST_DIR/$PARENT/"
    done
    rm -rf "$WORK_DIR"
    # コピー結果が空でないか確認
    [ "$(find "$DEST_DIR" -name "*.c" | head -1)" != "" ]
}

# ---- 1. src.zip の配置 ----

if [ -f "$SRC_ZIP" ]; then
    echo "src.zip already exists: $SRC_ZIP"
else
    echo "=== Setting up OpenJDK 21 source (src.zip) ==="

    JAVA_HOME_CANDIDATE=""
    if [ -n "$JAVA_HOME" ]; then
        JAVA_HOME_CANDIDATE="$JAVA_HOME"
    elif command -v java >/dev/null 2>&1; then
        JAVA_BIN=$(command -v java)
        while [ -L "$JAVA_BIN" ]; do
            JAVA_BIN=$(readlink -f "$JAVA_BIN" 2>/dev/null || readlink "$JAVA_BIN")
        done
        JAVA_HOME_CANDIDATE=$(dirname "$(dirname "$JAVA_BIN")")
    fi

    FOUND_SRC=""
    for CANDIDATE in \
        "$JAVA_HOME_CANDIDATE/lib/src.zip" \
        "$JAVA_HOME_CANDIDATE/../lib/src.zip" \
        "$JAVA_HOME_CANDIDATE/../../lib/src.zip"
    do
        if [ -f "$CANDIDATE" ]; then
            FOUND_SRC="$CANDIDATE"
            break
        fi
    done

    # macOS: Homebrew の openjdk@21
    if [ -z "$FOUND_SRC" ]; then
        for BREW_PREFIX in /opt/homebrew /usr/local; do
            CANDIDATE="$BREW_PREFIX/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/lib/src.zip"
            if [ -f "$CANDIDATE" ]; then
                FOUND_SRC="$CANDIDATE"
                break
            fi
        done
    fi

    # Linux: apt パッケージ
    if [ -z "$FOUND_SRC" ] && command -v apt-get >/dev/null 2>&1; then
        echo "Installing openjdk-21-source via apt..."
        apt-get install -y openjdk-21-source
        for F in /usr/lib/jvm/java-21*/lib/src.zip /usr/lib/jvm/openjdk-21*/lib/src.zip; do
            if [ -f "$F" ]; then FOUND_SRC="$F"; break; fi
        done
    fi

    # Linux: dnf/yum (Fedora/RHEL系)
    if [ -z "$FOUND_SRC" ] && command -v dnf >/dev/null 2>&1; then
        echo "Installing java-21-openjdk-src via dnf..."
        dnf install -y java-21-openjdk-src
        for F in /usr/lib/jvm/java-21*/lib/src.zip; do
            if [ -f "$F" ]; then FOUND_SRC="$F"; break; fi
        done
    fi

    if [ -n "$FOUND_SRC" ]; then
        cp "$FOUND_SRC" "$SRC_ZIP"
        echo "Copied src.zip to: $SRC_ZIP"
    else
        echo "WARNING: src.zip not found. Place it manually at: $SRC_ZIP"
    fi
fi

# ---- 2. OpenJDK native C ソースの取得 ----

if [ -d "$NATIVE_DIR" ] && [ "$(find "$NATIVE_DIR" -name '*.c' | head -1)" != "" ]; then
    echo "openjdk-native already exists: $NATIVE_DIR"
else
    echo "=== Fetching OpenJDK 21 native C sources ==="

    if command -v git >/dev/null 2>&1 && fetch_native_via_git "$NATIVE_DIR"; then
        echo "Native C sources stored at: $NATIVE_DIR (via git sparse-checkout)"
    elif command -v apt-get >/dev/null 2>&1 && fetch_native_via_apt "$NATIVE_DIR"; then
        echo "Native C sources stored at: $NATIVE_DIR (via apt-get source)"
    else
        echo "WARNING: Could not fetch native C sources automatically."
        echo "  Install git and re-run setup.sh to enable native method source tracing."
    fi
fi

echo ""
echo "=== Setup complete ==="
[ -f "$SRC_ZIP" ]    && echo "  src.zip    : $SRC_ZIP"
[ -d "$NATIVE_DIR" ] && echo "  native src : $NATIVE_DIR"
