# 型付き nested separated capture

`@nestedCaptures` の field に `item % separator @value` を指定すると、Java は
`List<item-shape>`、Rust は `Vec<item-shape>` を生成する。item の optional/list は
内側に保たれ、separator は field の値にはならない。従来の annotation のない
flat capture API は変わらない。

```ubnf
grammar Items {
  @package: example
  @root @nestedCaptures @mapping(Root, params=[value])
  Root ::= ['a'] % ',' @value;
}
```

この field は `List<Optional<String>>` / `Vec<Option<String>>` である。空入力は
一つの absent item `[None]` に、`a,a,` は `[Some(a), Some(a), None]` に対応する。
optional item の不成立を list から省かず、outer optional の不成立とも区別する。

実行は [Classic separated の契約](nullable-separation.md) を使う。mandatory first
item が失敗すれば list も失敗する。成功した tail `(separator item)` は数えてから
active cursor の無進捗で停止し、両方 nullable なら同 CP の空 item が二つ残る。
空 `SAME_AS`、外側の min/max、max=0 の子非実行もこの既存契約を保つ。
separator または tail item が失敗した場合は pair 全体を rollback する。

list boundary の CP span/text は separator を含む全体、各 item の boundary はその
item の原文である。出現順、重複、同 CP にある別の CST node、Unicode scalar を
保持する。復元に原文の再解析や text/span による node 同一視は使わない。
Java API schema と両ホストの Rust schema/impact、manual evaluator の引数も実際の
再帰型を報告する。

## separator の境界

Java `CaptureBoundaryParser.Kind.SEPARATOR` / Rust `capture_tree::Kind::Separator`
は内部の opaque CST marker である。通常の解析、CST、登録 state、owned binding
は保持し、outer snapshot と mapped leaf の収集だけが内部へ降りない。separator に
別の nested mapped rule があっても、その field boundary は outer item に混入しない。
separator の mapped candidate は通常の preferred snapshot に引き続き存在する。
separator marker 自体を snapshot root に指定すると `NOT_BOUNDARY` / `NotBoundary`
を返す。値の schema は従来の Value / Optional / List の三種であり、この marker を
`Element` の値として公開しない。

この enum は 3.3.0 後に追加された開発中 API であり、今回の Separator 追加により
開発版の exhaustive match には case の追加が必要になる。公開済み 3.3.0 にこの
Kind API が存在したという意味ではない。

生成 AST field は [入力由来境界](typed-nested-captures.md) を保つ。生成 item の
Value/Optional/List は `InputCaptureBoundary` / `CaptureBoundaryParser.input`、
separator は通常の general boundary を使う。general snapshot 自体の
Generated/Projected text の保持を制限せず、separator 内の元の Value boundary を
直接指定すれば、その owned snapshot を取得できる。入力由来でない item を元の
AST field の原文として読み替えることはない。

## 対応範囲と検証

対応する leaf は既存の text / i32 / mapped node / enum、container は既存の
optional/list/group/owned capture/scope と今回の separated である。同一 nominal
shape の shared mapping、mapped family の commonField、preferred metadata、
manual evaluator を検証する。mixed、透過 rule alias/recursive helper、typeof、
自動評価、associative projection、token-stream との組合せは、この単位では既存の位置付き診断を維持した。
現在の対応範囲は [typed nested capture の表](typed-nested-captures.md#nested-の対応範囲と拡張先)を参照する。

`spec-corpus/nested-separated-captures/` に手書きの型・AST・値・CP boundary・preferred
期待値を置く。Java21 の独立 consumer と Java/native の両ホストが生成した Rust1.85
consumer を同じ入力で比較し、全生成 Rust file の byte 一致と memo 2種 × diagnostics
3種の結果を確認する。`conformance/nested-separated/` は状態/outer rollback、
各 list 位置の sticky resource abort、一般 Generated/Projected snapshot の共有 oracle。
`reset.tsv` は token 4種 × 初期 reset 2種 × memo 2種 × diagnostics 3種で、
構文失敗後の同 context での fallback と outer rollback による初期 reset 復元を確認する。
`snapshot-limits.tsv` は separator marker 自体を含めた 65,536 visits と depth 256 の
境界値を確認する。opaque 内部は snapshot が走査・コピーしないためその予算の対象外だが、
生成 mapper の MappingGuard は内部も検証する。同じ深い内部 CST を snapshot は除外し、
mapper guard は depth 超過として拒否する対照を Java/Rust で固定する。
通常 child callback exception の一般 parser cleanup は #649 の境界を使用する。
`OrdinaryParserExceptionConformanceTest` は typed nested separated の first item /
separator / tail を故障させ、Java の元例外同一性、abandoned/manual frame、登録 state /
metadata / owned binding の復元と、両ホスト Rust の通常 Err / rollback / retry を
共通の入力・値・CP境界で確認する。Rust の Result::Err rollback と panic 後の context
破棄という境界を混同しない。

本機能は #646。親 #583 / #568 の全体完了を意味しない。
