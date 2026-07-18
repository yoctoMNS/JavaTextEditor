#!/bin/bash
set -e
./scripts/build.sh
# Linux(IBus)でIME変換中の未確定文字列がリアルタイムにInputMethodEventとして
# アプリへ配送されない既知の問題への対処。
# - IBUS_ENABLE_SYNC_MODE: 未設定だとIBusがキーイベントを非同期で処理し、
#   Java(XIM)側への通知が確定時まで届かないことがある。
# - XMODIFIERS/GTK_IM_MODULE/QT_IM_MODULE: ターミナルやランチャー経由の起動では
#   これらがエクスポートされておらず、AWTがXIMサーバ(IBus)を発見できず
#   ローカルの素朴な入力方式にフォールバックしてしまうことがある。
# いずれも既に設定済みの値があれば上書きしない。
export IBUS_ENABLE_SYNC_MODE="${IBUS_ENABLE_SYNC_MODE:-1}"
export XMODIFIERS="${XMODIFIERS:-@im=ibus}"
export GTK_IM_MODULE="${GTK_IM_MODULE:-ibus}"
export QT_IM_MODULE="${QT_IM_MODULE:-ibus}"
java -cp build dev.javatexteditor.Main
