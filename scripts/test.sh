#!/bin/bash
set -e
./scripts/build.sh
find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build

OVERALL_PASS=0
OVERALL_FAIL=0

for classfile in $(find build -name "*Test.class" ! -name '*$*' | sort); do
    classname=$(echo "$classfile" | sed 's|build/||' | sed 's|/|.|g' | sed 's|\.class$||')
    echo "=== $classname ==="
    if java -cp build "$classname"; then
        OVERALL_PASS=$((OVERALL_PASS + 1))
    else
        OVERALL_FAIL=$((OVERALL_FAIL + 1))
    fi
done

echo ""
echo "=== Summary: $OVERALL_PASS class(es) passed, $OVERALL_FAIL class(es) failed ==="
if [ "$OVERALL_FAIL" -gt 0 ]; then
    exit 1
fi
