# NonOrdered: Java / Rust の公開 runtime 契約

Java の `new NonOrdered(children...)` と Rust の
`Expr::non_ordered(children)`（`Expr::NonOrdered(Vec<Expr>)`）は、宣言された
**各 child 出現を一度ずつ**選択する、宣言順優先の greedy combinator である。
同じ parser オブジェクト・同じ式を二度指定した場合も、二つの出現として扱う。
UBNF の新構文・annotation は追加しない。既存の trivia 設定 `@interleave` とは別機能である。

```java
Parser parser = new NonOrdered(new WordParser("a"), new WordParser("b"));
// "ab" と "ba" に成功。"bax" では "ba" だけを消費する。
```

```rust
let parser = Expr::non_ordered([Expr::literal("a"), Expr::literal("b")]);
```

## 選択・位置・空入力

1. 現在位置から、未選択 child を宣言順に試す。
2. 失敗した候補の入力位置と所有状態を復元する。最初に成功した候補を選択済みにする。
3. 全出現を選択したら成功する。どの未選択候補も成功しなければ全体を復元して失敗する。

後から失敗しても、確定した選択を別の順列に置き換えない。
例えば `["a", "ab"]` は入力 `"aba"` に失敗するが、`["ab", "a"]` は成功する。
最長一致の自動選択や全順列探索はしない。child 数を n とすると、候補呼び出しは最大
n(n+1)/2 回、選択走査は O(n²)、選択済み管理は O(n) 空間である。
この上限は child 内部の解析コスト・保持する CST や state の量を含まない。

child が空の NonOrdered は空入力でも成功し、入力を進めない。
nullable child も一度で選択済みになるため、同じ child の無限再試行はない。
`[Optional("a"), "b"]` は `"ba"` に対して optional を CP 0 で空成功させ、
`"b"` のみを消費する。後続の `"a"` に戻って optional を再実行しない。
EOF は通常の child としてその時点の位置で評価する。
Java `EmptyParser` / Rust `Expr::JavaEmpty` の matched cursor 契約は維持する。
非 EOF でこの child だけなら consumed は 0、matched は 1 となり、CST span は 0..0 である。

NonOrdered 自身は trivia を飛ばさず、EOF も強制しない。子の Sequence / Chain や
明示 trivia parser が行う処理はその child の契約に従う。
成功した子の CST・capture は宣言順でなく**実際に選ばれた入力順**で保持する。
位置と span は従来どおりコードポイント単位。CRLF・補助平面文字でも UTF-16 / byte 数に置き換えない。

## Transaction と診断

失敗候補および失敗した全体から、消費・matched cursor、CST、capture、selection metadata、
登録済み user state、scope / declaration / reference / semantic diagnostic、recovery diagnostic
を漏らさない。外側の Choice の失敗・再試行でも同じ境界に従う。
Java の外部状態は `TransactionalState` として登録する必要があり、Rust では context の
所有 state API を使う。ファイル書き込み等の任意の外部副作用は復元対象ではない。
構文失敗診断の最遠位置・expected は、既存の診断方針に従って失敗試行から収集する。

Rust の FIRST は安全側の UNKNOWN とし、開始文字や nullable を誤って断定しない。
失敗 memo と deferred diagnostics の安全性判定は全 children をたどり、状態依存 custom child
を安全な純粋式として扱わない。各言語の既存 memo 方針・診断 3 モードで解析結果を維持する。
直接 ParseContext を使う deferred mode は詳細診断を蓄積しない既存契約のまま。
Rust の `parse_detailed_with_options` は必要時の再解析で詳細を返す。
trace 有効時は選択した子の trace を入力順で保持し、失敗した全体の trace は復元する。
completion の literal 収集も NonOrdered の children を走査する。

再帰・式深さ上限は既存 runtime の上限に従う。resource-limit は構文不一致として
次候補や fallback に吸収しない。上限到達後の同じ context の再利用も既存の拒否契約を維持する。
child 数そのものに新しい上限は追加しない。

## Java 修正と残る言語固有 API

従来の Java NonOrdered は候補ごとの transaction がなく、入力や登録 state を変更して
`Parsed.FAILED` を返す custom child が次候補を汚染できた。候補ごとの境界を追加して修正した。
例: 入力 `"ba"` で child 0 が CP 0 から `"b"` を消費して失敗し、CP 1 なら `"a"` に成功する場合、
child 1 の `"b"` は CP 0 から再試行できる。失敗した child 0 の state 変更も復元する。
内部 transaction owner は CollectingParser ではなく、CST に wrapper を追加しない。
**TransactionListener には候補ごとの begin / commit / rollback 通知が増える。**
`ParseContext.addActions` の persistent commit action も、成功した候補境界の owner を受け取る。
独自 listener / action が固定の transaction 数やすべての owner が文法 parser であることを
前提にする場合は調整が必要である。

Java の `TokenKind` / `invertMatch` 引数の子への伝播、`getOrdered` と
`MatchedNonOrderedParser` は維持する。Rust に汎用 `TokenKind` 引数や
`MatchedNonOrderedParser` 相当の選択順再適用 API を新設する変更ではない。
Rust の既存 `JavaEmpty`、Java 互換 optional など、対応する child の組合せを共通 oracle で検証する。
Rust 公開 enum `Expr` への variant 追加なので、利用側の exhaustive match には対応が必要である。

## 検証

独立した期待値は [`spec-corpus/nonordered`](../spec-corpus/nonordered/README.md) に置く。
Java `NonOrderedParityTest` / `NonOrderedStateTest` と Rust `tests/nonordered.rs` が
同じ fixture を読み、memo 2 方針 × 診断 3 モードで検証する。
Java 既存 NonOrdered / MatchedNonOrderedParser と transaction / recovery 回帰も合わせて確認する。
EOF、empty、nullable、重複、曖昧 prefix、Unicode CP span、非ゼロ起点、nest、
失敗後再試行、scope / capture / state / recovery の復元を含む。
