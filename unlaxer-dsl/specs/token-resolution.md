# トークン解決仕様

> ステータス: draft
> 最終更新: 2026-03-01

## スコープ

このドキュメントは `token NAME = ParserClass` 宣言のランタイムパーサー解決メカニズムを定義する。

このドキュメントが **扱わない** 範囲:
- UBNF 構文の全体仕様（→ [ubnf-syntax.md](ubnf-syntax.md)）

## 関連ドキュメント

- [ubnf-syntax.md](ubnf-syntax.md) — トークン宣言の構文
- [generators.md](generators.md) — パーサーコード生成

---

## トークン宣言

```
token NAME = ParserClass
```

### セマンティクス

- `NAME` はルール本体で参照可能なトークン名
- `ParserClass` は unlaxer-common（または unlaxer-dsl）のパーサークラスの **単純クラス名**
- 生成コードでは `Parser.get(ParserClass.class)` に変換される（MUST）

---

## 解決プロセス

1. `ParserClass` の単純クラス名を受け取る
2. 既知のパーサーパッケージを順に検索し、完全修飾クラス名を解決する
3. 解決されたクラスの import 文を生成コードに追加する
4. ルール本体中の `NAME` 参照を `Parser.get(ParserClass.class)` に置換する

---

## 既知パーサーパッケージ

以下のパッケージがインポート解決の対象となる（順序は実装依存）:

| パッケージ | 説明 |
|-----------|------|
| `org.unlaxer.parser.elementary` | WordParser, NumberParser, QuotedParser 等 |
| `org.unlaxer.parser.posix` | AlphabetParser, DigitParser 等 |
| `org.unlaxer.parser.ascii` | ASCII 句読点パーサー |
| `org.unlaxer.parser.combinator` | コンビネータ（通常はトークン宣言には使用しない） |

---

## エラー動作

### 未解決時

パーサークラスが既知パッケージで解決できない場合:

- コード生成自体は成功する（単純クラス名がそのまま使用される）
- 生成された Java ソースの **コンパイル時** にエラーとなる
- `GrammarValidator` レベルでの未解決エラーは現在提供されていない

---

## 標準トークン例

| トークン名 | パーサークラス | 用途 |
|-----------|--------------|------|
| `IDENTIFIER` | `IdentifierParser` | 識別子 |
| `STRING` | `SingleQuotedParser` | シングルクォート文字列 |
| `CLASS_NAME` | `IdentifierParser` | クラス名（識別子と同一パーサー） |
| `UNSIGNED_INTEGER` | `NumberParser` | 符号なし整数 |

---

## 現在の制限事項

- パーサークラスの解決はコンパイル時に委ねられており、UBNF バリデーション段階での検証は行われない
- カスタムパッケージのパーサークラスを使用する場合、消費プロジェクト側で import パスを適切に設定する必要がある
- 明示的なトークンインポート名前空間の設定機能は未実装（将来候補）

## 変更履歴

- 2026-03-01: 初版作成
