#!/bin/bash
set -e
mkdir -p build
find src -name "*.java" | xargs javac -encoding UTF-8 -d build
echo "Build OK"
