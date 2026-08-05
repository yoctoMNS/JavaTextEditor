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
# アイドル時のRSSをOSへ返却するための最小限のGCチューニング（設計判断は
# docs/decision-log.md「起動時JVMメモリ/GCチューニングの設計決定事項」参照）。
# 前回導入した-Xmx/ParallelGCThreads/ConcGCThreads/CICompilerCountの明示的な固定値は
# 操作時のカクつきを招いたため撤回した（GCスレッド数をコア数の少ない値へ固定すると、
# 実際にGCが走った際のポーズ時間がハードウェアのコア数なりに短くならず伸びるため）。
# G1PeriodicGCIntervalは新たなアロケーションが無く実質アイドルな期間にのみGCを走らせる
# （JEP 346）ため、対話操作中の応答性には影響しない。
# 既にJAVA_OPTSが設定されていれば上書きしない。
export JAVA_OPTS="${JAVA_OPTS:--XX:G1PeriodicGCInterval=60000}"
java $JAVA_OPTS -cp build dev.javatexteditor.Main
