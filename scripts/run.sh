#!/bin/bash
set -e
./scripts/build.sh
# Linux(IBus)でIME変換中の未確定文字列がリアルタイムにInputMethodEventとして
# アプリへ配送されない既知の問題への対処。
# - IBUS_ENABLE_SYNC_MODE: 未設定だとIBusがキーイベントを非同期で処理し、
#   Java(XIM)側への通知が確定時まで届かないことがある。
# - XMODIFIERS: ターミナルやランチャー経由の起動ではこれがエクスポートされておらず、
#   AWT(XToolkit)がXIMサーバ(IBus)を発見できずローカルの素朴な入力方式に
#   フォールバックしてしまうことがある。
# GTK_IM_MODULE/QT_IM_MODULEはGTK/Qtツールキット自体のIME連携方式を指定する変数で、
# GTK/Qtを使わない本アプリ(Swing/AWT)には効果がないため設定しない。
# いずれも既に設定済みの値があれば上書きしない。
export IBUS_ENABLE_SYNC_MODE="${IBUS_ENABLE_SYNC_MODE:-1}"
export XMODIFIERS="${XMODIFIERS:-@im=ibus}"
java -cp build dev.javatexteditor.Main
