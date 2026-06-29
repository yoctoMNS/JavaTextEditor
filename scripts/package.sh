#!/bin/bash
# Builds an executable JAR in dist/javatexteditor.jar
# Usage: ./scripts/package.sh [--skip-build]
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

if [[ "$1" != "--skip-build" ]]; then
    ./scripts/build.sh
fi

mkdir -p dist

# Write MANIFEST.MF
MANIFEST=$(mktemp)
cat > "$MANIFEST" <<EOF
Manifest-Version: 1.0
Main-Class: dev.javatexteditor.Main
EOF

jar --create \
    --file=dist/javatexteditor.jar \
    --manifest="$MANIFEST" \
    -C build .

rm -f "$MANIFEST"

echo ""
echo "Packaged: dist/javatexteditor.jar"
echo ""
echo "Usage:"
echo "  java -jar dist/javatexteditor.jar              # 新規ファイル"
echo "  java -jar dist/javatexteditor.jar path/to/file # ファイルを開く"
