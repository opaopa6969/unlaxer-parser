# MCP 設計: unlaxer-parser

## 1. namespace と種別

- **namespace**: `unlaxer`（割当表 #31）
- **種別**: `wrap` — 既存 Java CLI（CodegenMain / RailroadMain / UBNFToBNFMain）を薄く包んで MCP サーバ化
- **port**: 9228（割当表指定。`volta__machine_ports` で空き確認済み）
- **host**: 192.168.1.50（prod）
- **hostname**: `unlaxer.unlaxer.org`

## 2. tools 表

| name | 目的 | 入力 schema（要点） | 出力の形 | 副作用 | dry-run | job 型 | 所要時間 | min_role |
|------|------|---------------------|----------|--------|---------|--------|----------|----------|
| `validate` | UBNF 文法を検証する | `{grammar_file: string}` | `{ok, errors[], warnings[], grammarCount}` | read | — | no | < 5s | VIEWER |
| `generate` | UBNF 文法からコードを生成する | `{grammar_file: string, output_dir: string, generators?: string[], dry_run?: bool, clean_output?: bool}` | `{generated_files[], report, ok}` | write | yes | no | < 10s | MEMBER |
| `export_parser_ir` | Parser IR をエクスポートする | `{grammar_file: string, output_file?: string}` | `{ok, parser_ir, grammarCount, nodeCount, annotationCount}` | write | — | no | < 5s | VIEWER |
| `validate_parser_ir` | Parser IR を検証する | `{parser_ir: string}` | `{ok, errors[]}` | read | — | no | < 5s | VIEWER |
| `generate_railroad` | UBNF から railway diagram を生成する | `{grammar_file: string, output_dir: string, format?: "svg"\|"png"\|"both"\|"markdown"}` | `{ok, files[], count}` | write | — | no | < 10s | MEMBER |
| `convert_to_ebnf` | UBNF を EBNF に変換する | `{grammar_file: string, keep_annotations?: bool}` | `{ok, ebnf}` | read | — | no | < 5s | VIEWER |
| `init` | DSL プロジェクトを scaffold する | `{name: string, package?: string, output_dir?: string, with_dap?: bool, force?: bool}` | `{ok, project_dir, files[]}` | write | — | no | < 5s | MEMBER |

### 壊す系の confirm

- `generate` は `dry_run` デフォルト `true`（`dry_run=false` で実際にファイル書き込み）。`clean_output=true` で出力ディレクトリをクリーンする場合は `confirm` 必須。
- `init` は `force=true` で既存ディレクトリを上書きする場合に `confirm` 必須。

## 3. resources 表

| uri | 内容 | mime |
|-----|------|------|
| `unlaxer://spec` | 能力の機械可読仕様（tools/list から自動生成 + compositions/depends_on 手動追記） | `application/json` |
| `unlaxer://guide` | 使い方ガイド（CLI フラグ・UBNF 記法の要点・組み合わせ例） | `text/markdown` |
| `unlaxer://ubnf-guide` | UBNF 文法記法ガイド（構文・アノテーション・トークン定義） | `text/markdown` |

## 4. prompts / skills

| name | 種別 | 用途 | locality | applies_when | requires | min_role |
|------|------|------|----------|---------------|----------|----------|
| `write_ubnf_grammar` | skill | UBNF 文法を書く手順 | global | "UBNF 文法を定義・編集するとき" | — | VIEWER |
| `build_dsl_with_unlaxer` | skill | unlaxer で DSL を構築する手順 | global | "UBNF から DSL プロジェクトを立ち上げるとき" | — | VIEWER |

skill は `docs/skills/<name>/SKILL.md` に配置し、resource `skill://<name>` でも配信。

## 5. 組み合わせ例

1. **文法定義 → コード生成 → プロジェクト立ち上げ**
   `unlaxer__validate(grammar_file) → unlaxer__generate(grammar_file, output_dir) → (scaffolding)__create_project`
   UBNF 文法を検証 → Parser/AST/Mapper/Evaluator コードを生成 → 他サービスがプロジェクトを立ち上げる

2. **Parser IR エクスポート → 他サービスで解析**
   `unlaxer__export_parser_ir(grammar_file) → (他の解析サービス)__analyze_ir`
   Parser IR を中間表現として他サービスに渡す

3. **Railway diagram → ドキュメント埋め込み**
   `unlaxer__generate_railroad(grammar_file, output_dir) → (design)__compose_page_starter`
   生成した railroad diagram をドキュメントに埋め込む

## 6. 依存と協調

| 相手 repo | 方向 | 能力 | 合意したいこと | 暫定案 |
|-----------|------|------|----------------|--------|
| tinyexpression | depends_on（Maven artifact） | unlaxer-common / unlaxer-dsl | MCP 入口経由の協調（`tinyexpr__compile → unlaxer__validate`） | tinyexpression 側が MCP 化されたら接続。それまでは Maven artifact 依存のみ |
| onigiri-parser | depends_on（Maven artifact） | unlaxer-common / unlaxer-dsl | 同上 | 同上 |
| fraud-alert | depends_on（Maven artifact 2.x） | unlaxer-common / unlaxer-dsl (2.x) | 3.x 互換性確認 | 2.x 依存のまま別途検証 |

これらは Maven artifact としての依存であり、MCP 入口経由ではない。Phase 2 で tinyexpression 等の MCP 化が進めば `unlaxer__validate → tinyexpr__compile` のような協調が可能になる。issue-hub で協調を依頼する。

## 7. 非対応にした候補

Phase 1 からの差分なし。survey の候補をすべて実装する。

## 8. 参加方法

### manifest（volta.service.json）

```json
{
  "id": "unlaxer-parser",
  "name": "Unlaxer Parser MCP",
  "description": "UBNF文法からParser/AST/Mapper/Evaluator/LSP/DAPを生成するコードジェネレータのMCPラッパー",
  "type": "node",
  "hostname": "unlaxer.unlaxer.org",
  "port": 9228,
  "host": "192.168.1.50",
  "runtime": "systemd",
  "exec_start": "/home/opa/unlaxer-parser/run.sh",
  "user": "opa",
  "auth": "minRole:MEMBER",
  "health_check": "/healthz",
  "tags": ["mcp", "parser", "code-generator", "ubnf", "java"],
  "repo_url": "https://github.com/opaopa6969/unlaxer-parser",
  "mcp": {
    "enabled": true,
    "port": 9228,
    "path": "/mcp",
    "namespace": "unlaxer",
    "min_role": "MEMBER",
    "timeoutMs": 110000,
    "description": "UBNF文法の検証・コード生成・Parser IR エクスポート・railway diagram 生成"
  }
}
```

### アーキテクチャ

```
[volta ファサード] → http://192.168.1.50:9228/mcp → [Node.js MCP server (server.mjs)]
                                                        ↓ child_process.execFile
                                                   [Java CLI (CodegenMain / RailroadMain / UBNFToBNFMain)]
                                                        ↓ classpath
                                                   [unlaxer-common.jar + unlaxer-dsl/target/classes + deps]
```

Node.js サーバが Java CLI を子プロセスで呼び出す。classpath は `run.sh` で `mvn dependency:build-classpath` から動的構築、または `mcp/classpath.txt` にフォールバック。

### runtime

- Node.js（`@modelcontextprotocol/sdk`）
- Java 21+（CLI 実行用）
- systemd user unit

### auth

- `minRole: MEMBER`（generate/init/railroad は書き込みのため MEMBER、validate/convert/ir 系は VIEWER でもよいが manifest は MEMBER 統一）

## 9. テスト方針

e2e テスト（`mcp/test-e2e.mjs`）:

1. サーバ起動 → `GET /healthz` が 200
2. MCP `initialize` → `tools/list` で 7 tool が表示
3. `validate` — `story/code/calc.ubnf` で `ok: true`
4. `generate` — dry_run でファイル生成予定が返る
5. `export_parser_ir` — Parser IR JSON が返る
6. `validate_parser_ir` — エクスポートした IR で `ok: true`
7. `generate_railroad` — SVG ファイルが生成される
8. `convert_to_ebnf` — EBNF テキストが返る
9. `init` — プロジェクトディレクトリが作成される
10. `unlaxer://spec` resource が JSON で返る
11. `unlaxer://guide` resource が markdown で返る

クライアント: `@modelcontextprotocol/sdk` の `Client` + `StreamableHTTPClientTransport`
