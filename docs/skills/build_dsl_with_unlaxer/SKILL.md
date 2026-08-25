---
name: build_dsl_with_unlaxer
description: UBNFからDSLプロジェクトを立ち上げる手順
volta:
  version: 1
  namespace: unlaxer
  locality: global
  applies_when: "UBNFからDSLプロジェクトを立ち上げるとき"
  requires:
    tools:
      - unlaxer__init
      - unlaxer__validate
      - unlaxer__generate
  min_role: MEMBER
---

# unlaxerでDSLを構築する手順

## 1. プロジェクトの scaffold

`unlaxer__init` でプロジェクトを scaffold する:

```json
{ "name": "mydsl", "output_dir": "/tmp/mydsl" }
```

生成されるファイル:
- `grammar/` — UBNF 文法ファイル
- `pom.xml` — Maven プロジェクト
- `Makefile` — ビルド・インストールコマンド
- `vscode-extension/` — VS Code 拡張
- `IMPLEMENTATION.md` / `IMPLEMENTATION.ja.md` — 実装ガイド

## 2. 文法の作成

`grammar/` ディレクトリに UBNF 文法を書く。手順は `write_ubnf_grammar` skill を参照。

## 3. 検証

`unlaxer__validate` で文法を検証する:

```json
{ "grammar_file": "grammar/mydsl.ubnf" }
```

## 4. コード生成

`unlaxer__generate` で Parser/AST/Mapper/Evaluator コードを生成する:

```json
{ "grammar_file": "grammar/mydsl.ubnf", "output_dir": "src/main/java", "dry_run": false }
```

生成されるコード:
- `*Parsers.java` — パーサクラス
- `*LanguageServer.java` — LSP サーバ
- `*LspLauncher.java` — LSP ランチャ

## 5. ドキュメント生成（任意）

`unlaxer__generate_railroad` で railway diagram を生成し、ドキュメントに埋め込む。

## 6. Parser IR エクスポート（任意）

`unlaxer__export_parser_ir` で Parser IR をエクスポートし、他サービスに渡す。
