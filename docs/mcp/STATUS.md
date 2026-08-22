# MCP 化ステータス: unlaxer-parser

## 現在の状態: **registered** (volta 参加完了)

- **namespace**: `unlaxer`
- **port**: 9228
- **hostname**: `unlaxer.unlaxer.org`
- **host**: 192.168.1.50 (prod)
- **runtime**: systemd (Node.js + Java CLI)
- **healthz**: `https://unlaxer.unlaxer.org/healthz` → 200

## 完了した作業

### Phase 1: 調査
- `docs/mcp/survey.json` / `docs/mcp/SURVEY.md` 作成済み
- 判定: `wrap`（Java CLI を MCP で包む）

### Phase 2: 設計
- `docs/mcp/DESIGN.md` 作成済み

### Phase 2: 実装
- MCP サーバ: `mcp/server.mjs`（Node.js + @modelcontextprotocol/sdk 1.30.0）
- 7 tools: validate, generate, export_parser_ir, validate_parser_ir, generate_railroad, convert_to_ebnf, init
- 3 resources: unlaxer://spec, unlaxer://guide, unlaxer://ubnf-guide
- 2 skill resources: skill://write_ubnf_grammar, skill://build_dsl_with_unlaxer
- e2e テスト: `mcp/test-e2e.mjs`（15 チェック、全合格）

### Phase 2: volta 参加
- `volta.service.json`: manifest 作成
- `deploy/unlaxer-parser.service`: systemd user unit
- `run.sh`: 起動スクリプト（Java 21 + Maven classpath 構築 + Node.js MCP サーバ起動）
- `volta__svc_add(confirm=true)`: services.json に登録完了
- `volta__gateway_routes_apply(confirm=true)`: gateway ルート適用完了（1 件新規）
  - dry-run 確認: `[新規] unlaxer.unlaxer.org -> http://192.168.1.50:9228`（自分の 1 件のみ、既存ルートへの影響なし）
- prod デプロイ: git clone + Maven ビルド + npm install + systemd enable --now
- `https://unlaxer.unlaxer.org/healthz` → 200 確認済み
- `catalog__backend_status` → namespace `unlaxer` status `ready` 確認済み（7 tools）

### Phase 2: 協調
- issue-hub #277: `[mcp] unlaxer ↔ tinyexpr: UBNF validate → compile 連携` 作成済み
  - https://github.com/opaopa6969/issue-hub/issues/277
  - 返答を待たず暫定仕様で進行

### Phase 2: skill
- `docs/skills/write_ubnf_grammar/SKILL.md`: UBNF 文法を書く手順
- `docs/skills/build_dsl_with_unlaxer/SKILL.md`: DSL 構築手順

### Phase 2: README
- `README.md` / `README-ja.md` に MCP 節を追加

## dry-run 差分記録

### svc_add dry-run
- 既存 `unlaxer-parser`（library 登録のみ）を上書き
- 変更内容: type=library→node, MCP 有効化, port=9228, hostname=unlaxer.unlaxer.org, cloudflare 有効化
- 持ち主了承済み（2026-08-22）のため confirm=true で実行

### gateway_routes_diff
- `[新規] unlaxer.unlaxer.org -> http://192.168.1.50:9228`（1 件のみ）
- 既存ルートへの影響なし
- 持ち主了承済みのため confirm=true で実行

## 未決事項

1. tinyexpression の MCP 化完了後、`unlaxer__validate → tinyexpr__compile` の協調を確定（issue-hub #277 で対応中）
2. onigiri-parser, fraud-alert も同様（Maven artifact 依存のみ、MCP 入口としては未接続）

## 持ち主への質問

Phase 1 からの質問で解決済みのもの:
1. ~~volta の稼働ノードに Java 21+ の JDK がインストールされているか？~~ → インストール済み（`~/opt/jdk-21.0.12.1`）
2. ~~MCP サーバは unlaxer-dsl の fat jar で起動するか？~~ → Node.js で Java CLI を子プロセス呼び出し（classpath 構築）
3. ~~コード生成の出力先は一時ディレクトリ固定か、呼び出し側が指定するか？~~ → 呼び出し側が `output_dir` で指定

残りの質問:
4. 既存 CLI の `--report-format ndjson` をそのまま MCP の出力として使うか？ → ndjson をパースして JSON で返す形に実装済み
5. tinyexpression/onigiri-parser 等、unlaxer-parser で作られた DSL の MCP 化を将来的に統合するか？ → issue-hub で協調中

## コミット履歴

- `59353a0` feat: MCP server for volta participation (namespace: unlaxer, port 9228)
- `c187825` fix: JAVA_HOME and MVN paths for prod deployment
- `7c89b17` fix: REPO_DIR should be SCRIPT_DIR (run.sh is in repo root)
- `ae70537` fix: polyfill globalThis.crypto for Node 18 compatibility

## prod 環境の追加設定

- Java 21: `~/opt/jdk-21.0.12.1`（Oracle JDK 21 LTS、手動ダウンロード）
- Maven 3.9.9: `~/opt/apache-maven-3.9.9`（手動ダウンロード）
- systemd unit: `~/.config/systemd/user/unlaxer-parser.service`（`deploy/` からコピー）
