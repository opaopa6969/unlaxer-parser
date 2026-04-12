# `grammar/experimental/` — UBNF 文法仕様 v2 の実験場

このディレクトリは「現在の `ubnf.ubnf` (= production 用) では表現しきれない
UBNF 拡張」の実験スケッチを置く場所です。

## なぜ存在するか

Issue #8 (UBNFParsers self-hosting) を進める中で、ubnf.ubnf を「手書き
UBNFAST と完全等価な API を生成する形」に書き直そうとして、UBNF 自身の
表現力が不足していることが分かりました。

具体的に発見した不足:

| 項目 | 手書き UBNFAST | UBNF で書ける? | 改修案 |
|---|---|---|---|
| `TokenDecl.Simple/Until/...` の sealed inner record | あり | ✓ (ドット記法 `@mapping(TokenDecl.Simple)` で `21fb085` 対応済み) | — |
| `Annotation` を中間 sealed interface に | あり | ✓ (中間 sealed 機能 `21fb085` 対応済み) | — |
| `OneOrMoreElement(body)` / `BoundedRepeatElement(body, min, max)` / `SeparatedElement(elem, sep)` を独立 record として | あり | ✗ (現状の AnnotatedElement の後置量化子は record 化されない) | AnnotatedElement 構造の見直し |
| `PrecedenceAnnotation.level: int` (UNSIGNED_INTEGER token を数値型へ) | あり | ✗ (現状は `String` 推論) | ASTGenerator に token type 推論を追加 |
| `RecoveryAnnotation.mode: RecoveryMode` (enum) | あり | ✗ (UBNF に enum 構文がない) | UBNF に enum 宣言構文を追加 |
| `TokenDecl.name()` 共通 default メソッド | あり | ✗ (sealed の共通メソッド宣言を UBNF で書けない) | 自動推論 (全 permit が同じ field 名 + 同じ型なら interface に abstract method 宣言) |

## いつ使うか

新 Issue「UBNF 文法仕様 v2: 完全 self-describing 化」着手時に、ここに
実験版 `ubnf-v2.ubnf` を置いて、各拡張機能を一つずつ実装・検証していきます。

## 関連

- `21fb085` — ドット記法 + 中間 sealed interface 対応 (Y2/Y3.5 完了)
- `unlaxer-dsl/grammar/ubnf.ubnf` — production 用 (Plan S で UBNFParsers のみ self-hosting)
- `docs/ubnf-self-hosting.md` — self-hosting マイルストーン
