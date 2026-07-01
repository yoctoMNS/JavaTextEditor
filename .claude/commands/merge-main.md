---
description: 現在の作業ブランチをGitHub PR経由でmainブランチへマージする
---

現在チェックアウトしているブランチをmainブランチへマージしてください。手順:

1. `git status`/`git branch --show-current` で現在のブランチ名を確認する。mainブランチ自身の場合は何もせず、その旨を報告して終了する。
2. GitHub MCPツール（`mcp__github__list_pull_requests`）で、このブランチ→mainの既存オープンPRがあるか確認する。
3. 既存PRが無ければ `mcp__github__create_pull_request` で新規作成する。タイトルは変更内容を要約した日本語の短い一文、本文はSummary/Test planセクションを含む（これまでの会話で使ってきた形式を踏襲する）。
4. `mcp__github__merge_pull_request`（merge_method: "merge"）でマージする。
5. マージ後、PRのURLとマージコミットのSHAを報告する。

ユーザーが明示的にこのコマンドを実行しない限り、mainへのマージは行わないこと。
