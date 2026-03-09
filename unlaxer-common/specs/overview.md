# unlaxer-common 概要

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは unlaxer-common プロジェクト全体の目的、設計思想、スコープ、および用語を定義する。個々のコンポーネントの詳細仕様は関連ドキュメントを参照すること。

このドキュメントが **扱わない** 範囲:
- UBNF DSL による文法定義（→ unlaxer-dsl/specs/）
- TinyExpression 式言語（→ tinyexpression/specs/）
- チュートリアル・使い方ガイド（→ doc/en/, doc/ja/）

## 関連ドキュメント

- [core-types.md](core-types.md) — コアデータ型仕様
- [parse-context.md](parse-context.md) — ParseContext 仕様
- [combinators.md](combinators.md) — コンビネータ仕様
- [terminal-parsers.md](terminal-parsers.md) — 端末パーサー仕様
- [ast-filtering.md](ast-filtering.md) — AST フィルタリング仕様
- [debug-system.md](debug-system.md) — デバッグ/ログシステム仕様
- [CLAUDE.md](../CLAUDE.md) — 開発ガイドライン
- [doc/en/tinycalc-internal/](../doc/en/tinycalc-internal/) — チュートリアル（参考）

## プロジェクト目的

unlaxer-common は **Java 向けパーサーコンビネータライブラリ** である。RELAX NG のスキーマ合成パターンに着想を得て、小さなパーサーを組み合わせて複雑な文法を構築する。

### 設計目標

1. **合成可能性**: すべてのパーサーは `Parser` インタフェースを実装し、コンビネータにより自由に合成できる
2. **無制限先読み（Infinite Lookahead）**: バックトラッキングベースの解析により、文脈に依存しない任意の先読みを可能にする
3. **トランザクショナルパース**: begin/commit/rollback によるトランザクションモデルで、パース状態の安全な管理を保証する
4. **リッチなデバッグ**: リスナーベースのデバッグシステムにより、パース過程の詳細な追跡を提供する
5. **AST 射影**: トークン木からの選択的フィルタリングにより、クリーンな AST を生成する

### RELAX NG からの着想

unlaxer は RELAX NG の以下の概念を参考にしている:

| RELAX NG | unlaxer |
|----------|---------|
| `<group>` | `Chain` — 順序付きシーケンス |
| `<choice>` | `Choice` — 選択 |
| `<zeroOrMore>` | `ZeroOrMore` — 0回以上の繰り返し |
| `<oneOrMore>` | `OneOrMore` — 1回以上の繰り返し |
| `<optional>` | `Optional` — 省略可能 |
| `<interleave>` | `NonOrdered` — 順序不問 |

## パッケージ構造

| パッケージ | 役割 |
|-----------|------|
| `org.unlaxer` | コア型: Cursor, Parsed, Token, Source, CodePoint, TokenKind |
| `org.unlaxer.parser` | Parser インタフェース、パーサーファクトリ |
| `org.unlaxer.parser.combinator` | コンビネータ: Chain, Choice, ZeroOrMore, OneOrMore, Optional, NonOrdered, Not, Flatten, MatchOnly, ASTNode 等 |
| `org.unlaxer.parser.elementary` | 文字/文字列パーサー: WordParser, SingleCharacterParser, QuotedParser, NumberParser 等 |
| `org.unlaxer.parser.posix` | POSIX 文字クラス: AlphabetParser, DigitParser, SpaceParser 等 |
| `org.unlaxer.context` | ParseContext, Transaction, ParseFailureDiagnostics |
| `org.unlaxer.listener` | デバッグリスナー: ParserListener, TransactionListener |
| `org.unlaxer.reducer` | トークン木の縮約: TagBasedReducer |

## 用語定義

| 用語 | 定義 |
|------|------|
| **Parser** | 入力ソースを消費しパース結果（`Parsed`）を返すインタフェース。`parse(ParseContext) -> Parsed` が基本契約 |
| **Parsed** | パース結果。`succeeded`（成功）、`stopped`（部分成功・停止）、`failed`（失敗）の3状態を持つ |
| **Token** | パーサーが生成するトークン。木構造を形成し、parent-child 関係を持つ。original children と filtered children（AST ノード）の2つの子リストを保持する |
| **Cursor** | ソース内の位置を表す。CodePoint 単位の位置、行番号、行内位置を保持する。`StartInclusiveCursor` と `EndExclusiveCursor` の2種類がある |
| **Source** | パースされる入力テキストの抽象。root, detached, attached, subSource の4種類のソースカインドを持つ |
| **ParseContext** | パースのライフサイクル全体を管理する。ソース、カーソル位置、トークンスタック、トランザクション、デバッグリスナー、スコープツリーを保持する |
| **Transaction** | ParseContext のトランザクション操作（begin/commit/rollback）を定義するインタフェース。ネストしたトランザクションによりバックトラッキングを実現する |
| **TokenKind** | トークンの種別。`consumed`（消費済み）、`matchOnly`（マッチのみ）、`virtualTokenConsumed`、`virtualTokenMatchOnly` の4値 |
| **CodePoint** | Unicode コードポイント。位置計算は文字数ではなくコードポイント数で行う |
| **NodeKind** | AST ノードの種別。`node`（AST に含まれる）と `notNode`（AST から除外される）の2値。Tag ベースで管理される |
| **Combinator** | 他のパーサーを子として受け取り、合成パーサーを構築するパーサー |
| **Terminal Symbol** | 入力テキストの文字を直接消費するパーサー（端末記号） |

## 実行環境

- **Java**: 21以降（コンパイルターゲット: 11）
- **ビルドツール**: Maven
- **エンコーディング**: UTF-8
- **テストフレームワーク**: JUnit 4.13.2

## 現在の制限事項

- メモ化（memoize）は `ParseContext` にフラグが存在するが、完全な実装は未完了
- `Token` クラスの一部コンストラクタは `@Deprecated` マーク付き

## 変更履歴

- 2026-03-01: 初版作成
