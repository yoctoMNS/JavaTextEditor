#!/bin/bash
# セットアップスクリプト: OpenJDK 21 の Java ソース(src.zip) と native C ソースを
# git clone (sparse-checkout) 一本で lib/ に配置する。
# git がインストール済みであることを前提とする。
# Linux / macOS / WSL 対応
set -e

LIB_DIR="$(cd "$(dirname "$0")/.." && pwd)/lib"
mkdir -p "$LIB_DIR"

SRC_ZIP="$LIB_DIR/src.zip"
NATIVE_DIR="$LIB_DIR/openjdk-native"

if [ -f "$SRC_ZIP" ] && [ -d "$NATIVE_DIR" ] && [ "$(find "$NATIVE_DIR" -name '*.c' | head -1)" != "" ]; then
    echo "src.zip and openjdk-native already exist. Nothing to do."
    exit 0
fi

if ! command -v git >/dev/null 2>&1; then
    echo "ERROR: git not found. This script requires git to fetch OpenJDK 21 sources."
    exit 1
fi

WORK_DIR="$LIB_DIR/_openjdk_clone_tmp"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

echo "=== Cloning openjdk/jdk (blob:none, depth=1) ==="
git clone \
    --no-checkout \
    --depth=1 \
    --filter=blob:none \
    --branch jdk-21+35 \
    https://github.com/openjdk/jdk.git \
    "$WORK_DIR"

echo "Configuring sparse-checkout for Java/native sources ..."
git -C "$WORK_DIR" sparse-checkout init --no-cone
git -C "$WORK_DIR" sparse-checkout set \
    "src/*/share/classes" \
    "src/*/unix/classes" \
    "src/*/windows/classes" \
    "src/*/share/native" \
    "src/*/unix/native" \
    "src/*/windows/native"

git -C "$WORK_DIR" checkout

# ---- 1. src.zip の生成（module/pkg/Class.java 形式） ----

if [ -f "$SRC_ZIP" ]; then
    echo "src.zip already exists: $SRC_ZIP"
else
    echo "=== Building src.zip from checked-out Java sources ==="
    ZIP_STAGE="$LIB_DIR/_src_zip_stage_tmp"
    rm -rf "$ZIP_STAGE"
    mkdir -p "$ZIP_STAGE"

    find "$WORK_DIR/src" -maxdepth 3 -type d -name "classes" | while read -r CLASSES_DIR; do
        # 例: $WORK_DIR/src/java.base/share/classes -> module=java.base
        MODULE=$(echo "$CLASSES_DIR" | sed -E "s#^$WORK_DIR/src/([^/]+)/.*#\1#")
        mkdir -p "$ZIP_STAGE/$MODULE"
        cp -rn "$CLASSES_DIR/." "$ZIP_STAGE/$MODULE/" 2>/dev/null || true
    done

    if [ -n "$(find "$ZIP_STAGE" -name '*.java' | head -1)" ]; then
        jar cf "$SRC_ZIP" -C "$ZIP_STAGE" .
        echo "Created src.zip: $SRC_ZIP"
    else
        echo "WARNING: No Java sources found; src.zip was not created."
    fi
    rm -rf "$ZIP_STAGE"
fi

# ---- 2. openjdk-native への native C ソース配置 ----

if [ -d "$NATIVE_DIR" ] && [ "$(find "$NATIVE_DIR" -name '*.c' | head -1)" != "" ]; then
    echo "openjdk-native already exists: $NATIVE_DIR"
else
    echo "=== Placing native C sources ==="
    mkdir -p "$NATIVE_DIR"
    find "$WORK_DIR/src" -type d -name "native" | while read -r NATIVE_SUBDIR; do
        REL="${NATIVE_SUBDIR#$WORK_DIR/src/}"
        mkdir -p "$NATIVE_DIR/$(dirname "$REL")"
        cp -r "$NATIVE_SUBDIR" "$NATIVE_DIR/$(dirname "$REL")/"
    done
    echo "Native C sources stored at: $NATIVE_DIR"
fi

rm -rf "$WORK_DIR"

echo ""
echo "=== Setup complete ==="
[ -f "$SRC_ZIP" ]    && echo "  src.zip    : $SRC_ZIP"
[ -d "$NATIVE_DIR" ] && echo "  native src : $NATIVE_DIR"
