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
# メモリ・GCチューニング（アイドル時のRSSを抑える。設計判断はdocs/decision-log.md
# 「起動時JVMメモリ/GCチューニングの設計決定事項」参照）。
# - Xmx: 想定ファイル規模（数十万行）やプロジェクト全体を対象にしたコンパイル解析(javac)の
#   一時的な高負荷を吸収できる余裕を持たせつつ、上限を設けて無制限のヒープ拡張を防ぐ。
# - ParallelGCThreads/ConcGCThreads/CICompilerCount: コア数の多い実行環境ではGC/JITスレッド数が
#   コア数に比例して自動的に増え、アイドル時でも数十スレッドが常駐する。単一ユーザー向け
#   デスクトップアプリとして妥当な数へ固定する。
# - G1PeriodicGCInterval: アイドルが続いた際に定期GCを走らせ、一時的に確保したヒープ領域を
#   OSへ返却する（JEP 346）。既定値0（無効）だと解析等のスパイク後に確保したメモリが
#   アイドル中も返却されず高止まりする。
# 既にJAVA_OPTSが設定されていれば上書きしない。
export JAVA_OPTS="${JAVA_OPTS:--Xmx1536m -XX:ParallelGCThreads=4 -XX:ConcGCThreads=1 -XX:CICompilerCount=2 -XX:G1PeriodicGCInterval=60000}"
java $JAVA_OPTS -cp build dev.javatexteditor.Main
