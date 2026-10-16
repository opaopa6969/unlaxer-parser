# 明示的な nested capture の型付き生成

`@nestedCaptures` は rule 単位の opt-in である。annotation のない規則は従来の flat capture / mapper / evaluator API を使う。#619 は #583 の最初の型付き生成単位であり、nested AST 全体の完了を意味しない。

```ubnf
grammar Example {
  @package: example
  @root @nestedCaptures @mapping(Root, params=[value])
  Root ::= [ {'a'} ] @value;
}
```

上記の Java field は `Optional<List<String>>`、Rust field は `Option<Vec<String>>`。空入力でも optional の子である repetition が成功するため、結果は `Optional.of(List.of())` / `Some(vec![])` になる。`[[ 'a' ]] @value` の空入力は `Some(None)` 相当であり、outer optional 自体の不成立と区別する。JSON の `null` だけでは区別できないため共通試験は実際の typed 値と manual evaluator の再帰的な観測値も照合する。

## この単位で扱う形

- capture が所有する全体の形を明示した scalar、optional、repetition、bounded repetition、group。その葉は terminal または token 宣言による text / signed 32-bit integer。
- `NumberParser` / `DigitParser` の葉は Java `Integer` / Rust `i32`。optional/list を任意の順序で合成する。複数の scalar 要素だけからなる group は原文全体の text になる。
- list は出現順と重複を保つ。Java に合わせ、進捗しない nullable repetition は成功した最後の空 iteration を一度記録して停止する。
- capture 自体は container 全体に一度束縛される。annotation のない flat capture と異なり、個々の要素を同じ名前の独立 capture として再列挙しない。
- Java/Rust の生成 parser が実解析時に Value/Optional/List 境界を CST に保存し、mapper はその境界から型付き値を作る。capture 復元のために原文を再解析しない。
- text は元の Unicode scalar 列を保持する。正規化、quote 除去、trimming をしない。整数変換だけ Java `String.trim()` と同じ U+0000..U+0020 の端の文字を除外してから十進 `i32` として変換する。小数・範囲外は捕捉位置付き mapping error とする。
- AST root の source map は既存 CP span 契約。個々の container / leaf の原文 CP span は元 CST の `CaptureSnapshot` / `capture_tree::snapshot` から取得する。primitive / String 自体に新たな identity source map を付ける API ではない。
- manual evaluator には再帰型全体を渡す。Rust `Semantics` 引数は `&Option<Vec<String>>` のように集約値全体を借用する。既存 flat field の借用型は変更しない。
- Java API schema と両ホストの Rust API schema / impact は実際の再帰型、外側の cardinality、evaluator 引数型を報告する。

## 一般 capture / scope / replay との合成

`CAPTURE(name, body)` と `CAPTURE_SCOPE(body)` は型を決める際に body の形を引き継ぐ。たとえば `CAPTURE(word, [{'😀'}]) @value` は `Optional<List<String>>` / `Option<Vec<String>>` を生成する。実解析では一般 binding の保存・scope 退出・失敗時 rollback を実行し、型付けのためにその操作を省略しない。`SAME_AS(name)` の葉は text であり、空の binding の replay も present な空文字列として保存する。

この透過性は text / i32 / mapped node / enum、preferred metadata、同一 mapping の共有 shape 判定に適用する。outer / inner AST capture の重複や深さ制限は通常の group と同様に検査する。一般の binding 名と AST の field 名は別のままである。

AST field の入力由来という契約を nested 境界にも適用する。生成 parser は Java の `CaptureBoundaryParser.input` / Rust の `Expr::InputCaptureBoundary` で、子の実結果を collector が包む前に検査する。Generated / Projected token を field の入力 span として扱わない。通常 runtime の `CaptureBoundaryParser.value/optional/list/wrap` と `Expr::CaptureBoundary` は従来どおり Generated / Projected snapshot を扱える。一般 `CAPTURE` 自体の利用範囲を狭める変更ではない。

## 拒否と資源境界

capture は unique な外側の site であり、mapping params が全 site を列挙する。enclosing repetition 内の部分 capture、同名 site の合流、outer/inner capture の重複、container を含む複数要素 group / choice はこの単位では拒否する。暗黙に flat 化しない。

capture / typeof の準備走査も深さ 64 で停止し、schema 解析の再帰深さは 32 に制限する。準備走査は Java の `ChoiceBody` とその `SequenceBody` 代替枝を一つの論理 body と数え、native の body/alternatives 表現と揃える。これは AST の atomic/body/group 走査を含む解析深さであり、「container を必ず 32 個入れ子にできる」という保証ではない。不正な source shape は両 lowerer が `E-NESTED-SHAPE` で生成前に拒否する。Java の structured diagnostic は規則名から source snapshot の CP span へ解決でき、native diagnostic は規則の CP 範囲を表示する。public Rust IR も unsupported leaf / container / cardinality / typeof の組合せを検証する。

実行時 snapshot は #609 の上限（深さ 256 未満、訪問数 65,536、保持 UTF-8 text 合計 2 MiB）を共有する。これらは source 全体・既存 CST・生成 AST を含めた全プロセスメモリの上限ではない。

## #583 に残る範囲

| 組合せ | 状態 |
| --- | --- |
| text / i32 の直接 optional/list/group と manual evaluator | 本単位で両言語対応 |
| 直接 mapped node / enum の再帰 container | 後続 #626 で対応。[契約と検証範囲](nested-mapped-leaves.md)参照 |
| mixed value の再帰 container | 未対応、capture leaf の source shape を拒否 |
| 透過 rule alias / recursive helper を経由する container | 未対応、capture 内の rule reference を拒否 |
| separated list | #646 で対応。[型・値・separator 境界の契約](nested-separated-captures.md)参照 |
| 同一 mapping 型を共有する複数規則 | #637 で全 recursive shape / nominal leaf / opt-in が一致する場合に対応 |
| nested family の `@commonField` | #637 で nested field の実 accessor と、field を持たない mapped sum の opt-in を検証 |
| nested 規則の `@typeof` / associative projection / `@eval` | 未対応、明示拒否 |
| token-stream と nested の併用 | 未対応、明示拒否 |
| nested 値を扱う family commonField / preferred snapshot | #626 で直接 mapped family 葉の commonField と preferred を実検証。全組合せは #583 に残す |

既存 preferred / flat AST family の契約は変更しない。新しい nested shape と全ての family / preferred / auto-eval 組合せの実用同等性は、この単位の検証だけで完了扱いにしない。

## 独立共通 oracle

`spec-corpus/typed-nested-captures/` は両言語に同じ入力と手書き期待型・値・境界 CP/text を与える。`TypedNestedCaptureConformanceTest` は Java21 と Rust1.85 の実生成物を compile し、両 frontend の Rust 出力 byte 一致、parse/map/manual eval、API schema / impact、不正 shape と mapping error、失敗後の再利用を照合する。実行には `-DrustConformance=true` が必要である。既存 `NestedCaptureConformanceTest` は annotation のない flat 契約の回帰として残す。
