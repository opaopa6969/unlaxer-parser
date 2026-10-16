# Nested mixed scalar oracle (#653)

`cases.json` は 13 文法・31 入力。期待値は生成 mapper の JSON をコピーせず、原文を CP で数えて記述した。`expected.value` の tuple は次の独立表現を使う。

- `["list", children]` / `["some", child]` / `["none"]`: container 境界。
- `["text", source, startCP, endCP]`: trim しない原文。
- `["integer", i32, startCP, endCP]`: 数値 variant。
- `["enum", enumName, literal, startCP, endCP]`: enum の型・値。
- `["node", "Leaf", text, startCP, endCP]`: 元 mapped AST。

EOF は成功する zero length parser として使用する。空文字の WordParser は成功する EOF の代用にならない。`list-optional` の最後の `None` は nullable Repeat が成功した zero progress iteration を 1 回保持する既存契約を表す。

`text-budget` の `repeat` は入力 `[` + `x` の指定回数 + `]` を構築する。IdentifierParser で巨大な単一 text scalar を読み、nested snapshot の text budget を mapper が拒否することを検証する。generated UNTIL の文字ごとの CST 量を、この capture text budget の試験に代用しない。

各 successful case は通常 mapper・manual evaluator・DAP の独立期待値に加えて preferred default candidate と同値であること、source snapshot の保持、公開 CST に cycle を追加した場合の拒否を検証する。失敗時は別の正常入力が再び処理できることを確認する。

同じ grammar を Java host で Java / Rust に、native host で Rust に生成する。Rust は基本 5 ファイルと DAP を byte 比較したうえで、双方の出力を別々に compile / run する。各入力を memoization 2 × diagnostics 3 の 6 mode で実行し、合計 558 観測を比較する。生成 schema / impact の variant payload 型も両 host で確認する。

`invalid.json` は choice 内 container、多要素 mixed group、reserved generated type、深さ上限、shared mapping の異なる形を拒否する source rule の独立 CP 範囲を記録する。`grammar-and-helper-namespace` は `NestedValue` という文法名・helper 名を許可し、生成型名の予約を過剰に拡張しないことを確認する。

修正前の direct mixed choice は Java/native のいずれも `E-NESTED-SHAPE: ... heterogeneous/container/mapped choice requires the remaining #583 implementation` として拒否された。旧 mapped-choice / enum-choice rejection 2 件は、本 corpus の `same-node-choice` / `same-enum-choice` positive consumer へ移した。旧 flat の `MixedEnumConformanceTest` と既存 typed / mapped / shared / preferred corpus は別に回帰検証する。
