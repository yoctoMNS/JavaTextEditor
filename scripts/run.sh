#!/bin/bash
set -e
./scripts/build.sh
# Linux(IBus)でIME変換中の未確定文字列がリアルタイムにInputMethodEventとして
# アプリへ配送されない既知の問題への対処（IBUS_ENABLE_SYNC_MODE未設定だと
# IBusがキーイベントを非同期で処理し、Java(XIM)側への通知が確定時まで
# 届かないことがある）。既に設定済みなら上書きしない。
export IBUS_ENABLE_SYNC_MODE="${IBUS_ENABLE_SYNC_MODE:-1}"
java -cp build dev.javatexteditor.Main
