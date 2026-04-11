# Tutorial: Grammar Editor LSP — .ubnf ファイルで IDE 支援を受ける

## 概要
unlaxer は「文法 → 言語ツールチェーン」ジェネレータ。その**文法言語 (UBNF) 自身を unlaxer で扱う**ことで、`.ubnf` ファイル編集時に IDE 支援が受けられる。

## アーキテクチャ

```
ubnf.ubnf (UBNF自身の文法)
    │
    ├─ ParserGenerator → UBNFParsers.java (生成)
    ├─ ASTGenerator    → UBNFAST.java (生成)
    ├─ MapperGenerator → UBNFMapper.java (生成)
    └─ LSPGenerator    → UBNFLSPServer.java (生成) ★
                            │
                            └─ @declares → ルール定義登録
                               @backref  → Go-to-Definition
```

## 現状 (v2.9.0 時点)

### 完成済み
- [x] ubnf.ubnf 完全文法 (全構文サポート)
- [x] self-hosting round-trip 検証
- [x] fixpoint (Stage1 == Stage2) 検証
- [x] 全codegenパイプライン動作確認 (Parser/AST/Mapper/LSP)
- [x] @declares / @backref でルール定義/参照のセマンティクス付与

### 残タスク
- [ ] 生成 UBNFLSPServer の実際のデプロイ (VS Code Extension)
- [ ] .ubnf 専用の TextMate grammar (構文ハイライト)
- [ ] カタログ補完 (アノテーション名、キーワード)

## 使い方 (現在手動)

### 1. ubnf.ubnf から LSP サーバーを生成

```bash
cd unlaxer-dsl

# codegen 実行 (Java API 直接)
java -cp target/classes org.unlaxer.dsl.CodegenMain \
     --grammar grammar/ubnf.ubnf \
     --out target/generated-sources/ubnf-lsp \
     --generators Parser,AST,Mapper,LSP,LSPLauncher
```

### 2. 生成物をビルド

```bash
mvn -pl ubnf-lsp package
```

### 3. VS Code Extension として配布 (次バージョン予定)

## Developer Notes

### ubnf.ubnf への変更時
1. `SelfHostingRoundTripTest` で動作確認
2. `testFixpointStage1EqualsStage2` で fixpoint 確認
3. `UbnfCodegenPipelineTest` で全generator動作確認

### 変更禁止事項
- UBNFMapper の semantic processing は**手書きのまま**維持
  - エスケープシーケンス処理 (`\n` → 改行)
  - 整数パース (precedence level, bounded quantifier bounds)
  - 修飾名再構築 (dotted package names)
  - @typeof キャプチャ名 fixup

## 関連 Issue
- #4: Grammar Editor LSP (本ドキュメント対象)
- #5: VS Code Extension テンプレート
- #8: Self-hosting 切り替え (UBNFParsers.java 削除)
