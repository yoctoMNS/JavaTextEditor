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
HOTSPOT_DIR="$NATIVE_DIR/hotspot"

JDK_SOURCES_READY=0
if [ -f "$SRC_ZIP" ] && [ -d "$NATIVE_DIR" ] \
    && [ "$(find "$NATIVE_DIR" -name '*.c' | head -1)" != "" ] \
    && [ -d "$HOTSPOT_DIR" ] && [ "$(find "$HOTSPOT_DIR" -name '*.cpp' | head -1)" != "" ]; then
    echo "src.zip, openjdk-native, and hotspot sources already exist. Nothing to do."
    JDK_SOURCES_READY=1
fi

if [ "$JDK_SOURCES_READY" -eq 0 ] && ! command -v git >/dev/null 2>&1; then
    echo "ERROR: git not found. This script requires git to fetch OpenJDK 21 sources."
    exit 1
fi

if [ "$JDK_SOURCES_READY" -eq 0 ]; then

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
    "src/*/windows/native" \
    "src/hotspot/share"

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

# ---- 3. HotSpot (share) ソースの配置 ----
# src/hotspot は "native" という名前のサブディレクトリを持たないため、
# 上記のループでは拾えない。JVM_GC() 等のランタイム関数はここに実装されている。
# os/cpu 固有部分（src/hotspot/os/*, src/hotspot/cpu/*）はサイズが大きく、
# 対応プラットフォームの絞り込みが必要になるため、まずは共通部分（share）のみを対象にする。
if [ -d "$HOTSPOT_DIR" ] && [ "$(find "$HOTSPOT_DIR" -name '*.cpp' | head -1)" != "" ]; then
    echo "hotspot sources already exist: $HOTSPOT_DIR"
elif [ -d "$WORK_DIR/src/hotspot/share" ]; then
    echo "=== Placing HotSpot (share) sources ==="
    mkdir -p "$HOTSPOT_DIR"
    cp -r "$WORK_DIR/src/hotspot/share" "$HOTSPOT_DIR/"
    echo "HotSpot sources stored at: $HOTSPOT_DIR/share"
else
    echo "WARNING: src/hotspot/share not found in checkout; hotspot sources were not placed."
fi

rm -rf "$WORK_DIR"

fi # JDK_SOURCES_READY

# ---- 4. IBM Plex Mono Regular (TTF) の取得 ----
# 半角ASCIIの描画に使うフォント本体。SIL OFL 1.1 で配布されており、ソース同梱
# ではなく実行時にダウンロードして lib/fonts/ に置く（lib/ は .gitignore 対象の
# ため、src.zip/openjdk-native と同じ「外部リソースは setup.sh で取得」という
# 既存の方針に合わせている）。
FONTS_DIR="$LIB_DIR/fonts"
FONT_TTF="$FONTS_DIR/IBMPlexMono-Regular.ttf"
FONT_LICENSE="$FONTS_DIR/IBMPlexMono-OFL.txt"
FONT_TTF_URL="https://raw.githubusercontent.com/IBM/plex/master/packages/plex-mono/fonts/complete/ttf/IBMPlexMono-Regular.ttf"
FONT_LICENSE_URL="https://raw.githubusercontent.com/IBM/plex/master/LICENSE.txt"

if [ -f "$FONT_TTF" ]; then
    echo "IBM Plex Mono Regular already exists: $FONT_TTF"
else
    if ! command -v curl >/dev/null 2>&1; then
        echo "WARNING: curl not found; skipping IBM Plex Mono Regular download."
        echo "         The editor will fall back to a substitute monospace font."
    else
        echo "=== Downloading IBM Plex Mono Regular (SIL OFL 1.1) ==="
        mkdir -p "$FONTS_DIR"
        if curl -fsSL -o "$FONT_TTF.tmp" "$FONT_TTF_URL"; then
            mv "$FONT_TTF.tmp" "$FONT_TTF"
            echo "Saved: $FONT_TTF"
        else
            echo "WARNING: failed to download IBM Plex Mono Regular from $FONT_TTF_URL"
            echo "         The editor will fall back to a substitute monospace font."
            rm -f "$FONT_TTF.tmp"
        fi
        if [ -f "$FONT_TTF" ] && [ ! -f "$FONT_LICENSE" ]; then
            curl -fsSL -o "$FONT_LICENSE" "$FONT_LICENSE_URL" \
                || echo "WARNING: failed to download the OFL license text (non-fatal)."
        fi
    fi
fi

echo ""
echo "=== Setup complete ==="
[ -f "$SRC_ZIP" ]    && echo "  src.zip    : $SRC_ZIP"
[ -d "$NATIVE_DIR" ] && echo "  native src : $NATIVE_DIR"
[ -d "$HOTSPOT_DIR" ] && echo "  hotspot src: $HOTSPOT_DIR"
[ -f "$FONT_TTF" ]   && echo "  font       : $FONT_TTF"
