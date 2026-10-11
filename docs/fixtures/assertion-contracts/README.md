# Assertion contract fixtures

仕様: [positive-assertion-contracts.md](../../positive-assertion-contracts.md)。
基準 runtime は `875144631d932d7fe31e66a3b68045ecaf77928e`。
`cases.json` の `java` / `rust` は固定された個別 oracle で、片側の出力を期待値として他方に渡さない。

親は `prefix → positive(child) → suffix`。`suffix: null` なら後続要求なし。
Java の親は `Chain`、Rust は同じ三操作を一つの `transaction` で実行する。
assertion 直後の結果は observer が値として保存し、親が後で失敗しても比較できるようにする。
この観測記録自体は実アプリケーションに返す parse result ではない。

| child | 定義 |
|---|---|
| literal | `ab` |
| optional | `a b [!]` |
| choice | `(a b ! \| ab)` |
| failure | `a b !` |
| nested | `positive(ab)` |
| nested-sibling | `positive(a) b` |
| state | capture-site 内の `a` で owned state / scope 宣言 / 参照 / 意味診断を追加し、`b` の前で宣言と state が見えることを確認 |
| state-failure | `state !` |
| unicode | `😀\r\n界` |
| java-literal | Java は `literal` と同じ、Rust は PEG `ahead` を包まず `JavaLookahead("ab")` を直接使用 |

baseline は owned state `7`、depth `0`、宣言 `before@0`、参照 `before@[0,1)`、
意味診断 `baseline@[0,1):INFO`。state child は値 `9` と子 scope の `inside` を作る。
Rust は `Expr::capture("site")` の公開 store も読み、assertion 外では空になることを確認する。
Java は capture-site の token metadata を観測し、存在しないグローバル capture store を模倣しない。

`tokens` は Word leaf の `[kind,startCP,endCP,text]`。
Java assertion の返す match-only tree と、親の consumed tree は分けて記録する。
Rust の literal expression は node を作らず、assertion も child node / capture を破棄するので空配列。
`assertion.span` は Java の root Token と Rust の ParseMatch の範囲。
Rust の `consumed` ラベルは ParseMatch が消費範囲を返すことを表す観測用ラベルで、
Rust に Java の TokenKind が存在するという意味ではない。`JavaLookahead` の matched が進んでも
ParseMatch の span は `[start,start)` である。失敗した assertion の span は null。

原文位置は Unicode code points の半開区間。特に `P:😀\r\n界;` の
Java match-only span は `[2,6)`、Rust PEG の返す span は `[2,2)`。
`cases.json` は Detailed の oracle。低水準 Deferred では farthest / expected のみ
`0` / `[]`、Auto は Detailed と同一という別の固定規則を driver が適用する。

`retries.json` は全入力入口の oracle。`Retry.ubnf` から生成する Java Mapper と、
Rust runtime の本物の retry 経路を使う。成功の farthest `-1` は「公開エラーなし」の
観測用値で、ParseContext の native diagnostic を無理に同一化するものではない。
