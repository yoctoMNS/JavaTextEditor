#!/bin/bash
set -e
./scripts/build.sh
java -cp build dev.javatexteditor.Main
