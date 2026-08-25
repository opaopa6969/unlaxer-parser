# MCP 化調査: unlaxer-parser

## 概要

**unlaxer-parser** は Java 向けのパーサコンボネータライブラリ（`unlaxer-common`）と、UBNF 文法から Parser/AST/Mapper/Evaluator/LSP/DAP を自動生成するコードジェネレータ（`unlaxer-dsl`）の 2 モジュールからなる Maven マルチモジュールプロジェクト。Maven Central で `org.unlaxer:unlaxer-common` / `org.unlaxer:unlaxer-dsl` として配布中（v3.0.11-SNAPSHOT 開発中、最新リリース v3.0.10）。

CLI ツール（`CodegenMain`, `RailroadMain`, `UBNFToBNFMain`）を持ち、`CodegenMain` は `--validate-only`、`--dry-run`、`--export-parser-ir`、`--report-format ndjson` などエージェント向きのフラグを既に備えている。HTTP API・MCP・volta manifest・healthz は未実装。

## 判定と理由

**判定: `wrap`** — 既存 CLI を薄く包んで MCP サーバ化する。

理由:
- `CodegenMain` は ndjson 出力・dry-run・validate-only を持っており、MCP で包むコストが極めて低い。
- 文法検証・コード生成・Parser IR エクスポートは、scaffolding サービスや LSP テスト、tinyexpression 等の下流プロジェクトと組み合わせる絵が描ける。
- JVM 常駐プロセスにすれば起動コストを償却でき、複数文法の連続検証・生成に価値がある。
- ただし既存 CLI が self-contained なので、MCP で包む主目的は「他サービスとの協調」にあり、単体 CLI で済む場合は skip でもよい。

## 公開候補

| kind | name | io | 副作用 | 長時間 | maps_to |
|------|------|----|--------|--------|---------|
| tool | `validate` | `{grammar_file_path} → {ok, errors[], warnings[]}` | read | no | `CodegenMain --validate-only` |
| tool | `generate` | `{grammar_file, output_dir, generators[]?} → {generated_files[], report}` | write | no | `CodegenMain --grammar --output --generators` |
| tool | `export_parser_ir` | `{grammar_file} → parser-ir.json` | write | no | `CodegenMain --export-parser-ir` |
| tool | `validate_parser_ir` | `{parser_ir_file} → {ok, errors[]}` | read | no | `CodegenMain --validate-parser-ir` |
| tool | `generate_railroad` | `{grammar_file, output_dir?, format?} → {svg_files[]}` | write | no | `RailroadMain` |
| tool | `convert_to_ebnf` | `{grammar_file} → ebnf_text` | read | no | `UBNFToBNFMain` |
| tool | `init` | `{name, package?, with_dap?} → {project_dir, files[]}` | write | no | `CodegenMain init` |
| resource | `spec` | 能力の機械可読仕様 | — | — | `unlaxer://spec` |
| resource | `ubnf_guide` | UBNF文法ガイド | — | — | `unlaxer://ubnf_guide` |
| resource | `guide` | 使い方 | — | — | `unlaxer://guide` |
| skill | `write_ubnf_grammar` | UBNF文法を書く手順 | — | — | locality: global |
| skill | `build_dsl_with_unlaxer` | unlaxerでDSLを構築する手順 | — | — | locality: global |

## 組み合わせ例

1. `unlaxer__validate → unlaxer__generate → (scaffoldingサービス)__create_project → (code-server)__open` — 文法定義からプロジェクト立ち上げまでを自動化
2. `unlaxer__export_parser_ir → (他の解析サービス)__analyze_ir` — Parser IR を中間表現として他サービスに渡す
3. `unlaxer__generate_railroad → (design__compose_page_starter)` — 生成した railroad diagram をドキュメントに埋め込む

## 依存と協調

| 相手 repo | 方向 | 能力 | 現存 | 備考 |
|-----------|------|------|------|------|
| tinyexpression | depends_on | unlaxer-common / unlaxer-dsl Maven artifact | no | MCP 入口としては未接続 |
| fraud-alert | depends_on | unlaxer-common / unlaxer-dsl Maven artifact (2.x) | no | 2.x 依存で 3.x 未検証。MCP 入口としては未接続 |
| onigiri-parser | depends_on | unlaxer-common / unlaxer-dsl Maven artifact | no | 3.0.1 で検証済み。MCP 入口としては未接続 |

これらは Maven artifact としての依存であり、MCP 入口経由ではない。Phase 2 で tinyexpression 等の MCP 化が進めば、`unlaxer__validate → tinyexpression__compile` のような協調が可能になる。

## ライブラリのサーバ化

本リポジトリは `library` だが、サーバ化して volta に参加させる価値がある。

- **必要な新規実装**:
  - `/healthz` エンドポイント（200 を返す）
  - `PORT` 環境変数によるポート指定
  - `volta.service.json`（manifest）
  - systemd user unit または docker
  - MCP サーバ（Streamable HTTP `/mcp`）
- **ランタイム**: Java
- **推定工数**: M — 既存 CLI ロジックの薄い wrapper だが、Java での Streamable HTTP MCP サーバ実装が必要

## リスク

- JVM 常駐プロセスのメモリ消費（~200-400MB）
- コード生成 tool はファイルシステムに書き込むため、出力ディレクトリのサンドボックスが必要
- Java 21+ が必要（volta のランタイムに Java がインストールされている必要がある）
- 破壊的操作（`init --force`, `--clean-output`）には `confirm` フラグが必要
- Maven Central への deploy 機能は MCP に公開しない（リリース手順は別途）

## 持ち主への質問

1. volta の稼働ノードに Java 21+ の JDK がインストールされているか？
2. MCP サーバは unlaxer-dsl の fat jar で起動するか、Spring/Spark 等の軽量フレームワークを使うか？
3. コード生成の出力先は一時ディレクトリ固定か、呼び出し側が指定するか？
4. 既存 CLI の `--report-format ndjson` をそのまま MCP の出力として使うか、MCP 用に変換層を挟むか？
5. tinyexpression/onigiri-parser 等、unlaxer-parser で作られた DSL の MCP 化を将来的に統合するか（親子関係）？
