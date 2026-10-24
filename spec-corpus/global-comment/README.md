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

## LOOKAHEAD の到達位置を保つ trivia（#753）

`*Lookahead` の9文法18入力は、trivia の後で失敗する正の `LOOKAHEAD` の回帰ケース。
期待値は Java の parse frame の規則 `max(consumed, matched)` から手で求めた。
`CommentLookahead` の `a//c\nxy` では `a` の消費後 CP1、先読みの到達 CP7、
コメントの消費 CP4、行末の先読み CP5 なので、後続の `z` の失敗位置は CP7。
CRLF と補助文字も code point で数え、UTF-8 の byte 数や UTF-16 の unit 数は使わない。

javaStyle の空白・block comment・line comment の連続、規則の `@whitespace`、
全体と規則の名前付き whitespace（1文字の GAP を繰り返し読む）、入れ子と選択による
複数回の trivia 試行も同じ最大到達の規則を使う。根の失敗は transaction の rollback
により prefix が `[false, 0, 0]`。`CommentPrefixLookahead` は任意の `z` が無くても
成功し、根の消費はコメント末尾 CP4 までなので prefix は `[true, 4, 4]`、
全入力診断は `trailing_input` CP4（先読み CP7 ではない）になる。

`RuleTriviaConformanceTest` の共通 harness は prefix と全入力診断を memo OFF/ON ×
Detailed / DetailedOnFailure / Auto の6設定で照合する。Java-host と native Rust は
別々に生成・compile・実行し、既存の明示 Classic 入口との比較も行う。

修正は Rust の非 Classic・session 外の読み飛ばしで現在の matched と trivia の到達位置の
最大値を保つだけで、Java の実装、Classic の専用 trivia parser、token-stream session の
消費位置、#706 / #734 の frontier 選択は変更しない。修正前の runtime で診断の16入力が
失敗し、prefix 成功の2入力は前後とも一致することを mutation 確認した。
