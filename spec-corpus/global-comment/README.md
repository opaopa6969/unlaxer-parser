# global `@comment` の共通 corpus（#712）

`corpus.json` は文法・入力・独立期待値、`invalid.json` は拒否する設定の code・subject・code point span、
`ignored.json` は全体の `@whitespace: javaStyle` と併記したために拒否せず `W-COMMENT-IGNORED` を出す
設定の code・subject・code point span と、生成物が一致すべき javaStyle 単独の文法（`baseline`）。
契約は [trivia 契約の global `@comment`](../../docs/rule-trivia.md#global-comment712)。

`GlobalCommentConformanceTest`（`RuleTriviaConformanceTest` の共通 harness）が Java と
Java-host / native の Rust 生成物を別々に compile・実行し、受理、prefix の consumed / matched
cursor（memo 有無）、診断種別と位置、AST の全 field と node span を照合する。明示 Classic
入口（Java の原文・AST、Rust の token mode）も同じ入力で比較する。

- `comment-only` / `comment-with-global-none`: 行コメントだけの既定。末尾コメント、コメント内の
  引用符・`/*`・補助文字、LF / CRLF / CR が trivia でないこと、空白・block comment・単独 `/`、
  終端の先読みで matched cursor と診断位置が改行の後ろへ進むこと。
- `comment-lines`: 改行を構文 token とする v2 文法。行末コメント、コメントだけの行、反復の
  rollback 後の cursor、閉じ忘れた block comment。
- `comment-with-global-java-style`: javaStyle と同じ動作。
- `comment-rule-priority`: 継承 / `@whitespace(none)` / `@whitespace` / `@interleave` の rule を
  1 文法に混在させ、別 rule の設定が継承 rule に漏れないこと（変更前の Java は漏れていた）。
- `comment-lexical-context` / `comment-named-rule-whitespace`: lexical context の rule と、
  rule の名前付き空白との合成。
- `comment-candidate-exclusion`: trivia の無い rule から、行コメントの前で継承 rule を候補に
  した選択（規則参照と、継承 rule の本体の選択肢の列）。受理・cursor・診断位置を Java と
  照合する。FIRST 集合による候補除外（DetailedOnFailure）がこれらの候補を誤って落とさないことは、
  `rust/unlaxer-runtime/tests/candidate_exclusion.rs` が audit mode と、再試行の無い
  `ParseContext` の入口で確かめる（全入力の解析関数は deferred pass の失敗後に Detailed で
  解析し直すので、この corpus の結果には誤った除外が現れない）。

期待値は契約から独立に作った。変更前の Java と照合した差は、上記の漏れ 3 件と、行末記号の
先読みで matched cursor が進む 2 件（Java が正しく、期待値側を Java の仕様に合わせた）だった。

## LOOKAHEAD の後に失敗する rule（#753）

`issue: "#753"` の25文法37入力は、正の `LOOKAHEAD` の後に呼んだ rule の回帰ケース。
期待値は Java の parse frame の規則から手で求めた。rule を呼んだときの `ParseFrame` の開始は
max(consumed, matched) で、その rule が失敗するとこの開始が失敗候補になる。一方、trivia と消費は
transaction の matched cursor を consumed（と行コメント終端の先読み）まで下げ、後続の `LOOKAHEAD` /
`NEGATIVE_LOOKAHEAD` / `UNTIL` はその cursor から読む。CRLF と補助文字も code point で数え、
UTF-8 の byte 数や UTF-16 の unit 数は使わない。

- `CommentLookahead` から `CommentPrefixLookahead` まで（9文法18入力）: trivia の後で失敗する rule。`CommentLookahead` の `a//c\nxy` では
  `a` の消費後 CP1、先読みの到達 CP7 で R1 を呼ぶので R1 の frame は CP7 から始まる。R1 の中の
  コメントの消費 CP4・行末の先読み CP5 の後で `z` が失敗し、R1 の失敗で CP7。javaStyle の空白・
  block comment・line comment の連続、規則の `@whitespace`、全体と規則の名前付き whitespace
  （1文字の GAP を繰り返し読む）、入れ子と選択による複数回の trivia 試行も同じ規則。根の失敗は
  transaction の rollback により prefix が `[false, 0, 0]`。`CommentPrefixLookahead` は任意の `z` が
  無くても成功し、根の消費はコメント末尾 CP4 までなので prefix は `[true, 4, 4]`、全入力診断は
  `trailing_input` CP4（先読み CP7 ではない）。
- `*AfterTrivia`（4文法7入力）: trivia の後の matched cursor から読む要素。javaStyle の R1 は空白を
  CP3 まで消費し、matched も CP3 になるので、`LOOKAHEAD('xy')`・`UNTIL('x')` は CP3 の `xy` を見て
  受理し、`NEGATIVE_LOOKAHEAD('x')` は失敗する（第2候補の `w` が CP5 で失敗）。行コメントでは
  matched が行末の先読み CP5 なので、`LOOKAHEAD('xy')` が成功し、`'\n'` を CP4 から消費する。
  先読みの到達（CP5 / CP7）を trivia が保つと、これらは逆の結果になる。
- `JavaStyleLookaheadRuleSucceeds`: 成功した rule の開始は候補にならない。R1 の `['q']` は CP3 で
  失敗して R1 は成功し、続く `w` が CP3 で失敗するので syntax CP3（R1 の開始 CP5 ではない）。
- `JavaStyleConsumeAfterTrivia` / `ConsumeAfterLookahead*`: rule の中の消費の後の失敗。
  `axyz` では R1 が CP4 から始まり、`x` の消費後 CP2 で `q` が失敗、R1 の失敗で CP4。選択の全候補が
  失敗する場合と、同じ位置で2度呼ぶ rule（memo ON では2度目が memo の再生）も同じ。

`RuleTriviaConformanceTest` の共通 harness は prefix と全入力診断を memo OFF/ON ×
Detailed / DetailedOnFailure / Auto の6設定で照合する。Java-host と native Rust は
別々に生成・compile・実行し、既存の明示 Classic 入口との比較も行う。

修正は Rust の rule 呼び出しが失敗したときに frame の開始を失敗位置として記録するだけで、
trivia の読み飛ばし、matched cursor、Java の実装、Classic の専用 trivia parser、token-stream
session の消費位置、#706 / #734 の frontier 選択は変えない（[説明](../../docs/prior-matched-diagnostics.md#失敗した-rule-frame-の開始753)）。
最初の18文法30入力は、修正前の runtime では20入力（trivia の16と消費の4）が失敗し、trivia で matched を
max に保つ実装では12入力（`*AfterTrivia` の7、成功した rule の1、消費の4）が失敗する。

再レビューで追加した7文法は、後続の Choice の枝が成功した後の失敗、全体の成功、任意・反復の
最後の失敗、成功 memo の再生、末端が同じ frontier / さらに遠方に届く場合を扱う。`axyz` の
失敗した子 rule は CP4 に候補を残すので、後続の枝が `x` で成功した後に `v` が CP2 で失敗しても
診断は CP4。全入力を受理すれば診断は返さない。末端の `q` が CP4 / CP5 で失敗した場合は
その候補を保持する。

`memoHitsAtLeast` は `[failure, success]` の最低 hit 数で、memo ON の各診断方針について
Java と両 Rust host の実 hit を検証する。単に ON/OFF を切り替えただけの試験とは区別する。
`expectedHints` は backend 固有の候補を両列に固定する。たとえば `CommentLookahead` は
Java `["'z'"]` / Rust `[]`、`LookaheadRuleFartherFailure` は Java `["'q'"]` / Rust `["q"]`。
共通契約は種別・位置であり、候補を追加しないことを「Java と候補も一致」とは扱わない。
再レビューの mutation では `fail_rule_frame` を無効にした専用コピーで corpus の
`CommentLookahead/reach` が CP7 / CP5 の差で落ち、Rust の2つの rule-frame 回帰テストも落ちる。
