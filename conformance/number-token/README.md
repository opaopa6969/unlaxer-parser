# Number token の共通 oracle

Java `NumberParser` を正本とする。`cases.tsv` は #717 の既存36入力 × consumed/matchOnly × invert の144行を保つ。
`diagnostics.tsv` は #752 の内部失敗を、実際の Java parser から採取する。再生成は次のコマンドで行う。

```sh
mvn -B -o -pl unlaxer-common -am test -Dtest=NumberTokenOracleTest \
  -Dnumber.oracle.write=true -Dsurefire.failIfNoSpecifiedTests=false
```

追加 oracle は Number 単体、負の PEG 先読み、Optional、Choice の最後の候補、Optional の後、
成功する後続要素、成功 memo 再生を含む。仮数部・小数点・不完全/完全な指数・EOF・Unicode suffix を使い、
成功/拒否、consumed/matched CP、最遠失敗 CP、期待候補の記録順、rule stack を比較する。
`bare` には仮数部が無い失敗も含む。既存 oracle と同じく受理と消費は変更しない。

- 詳細診断では成功した Number の内部失敗も hint に残す。整数末尾は
  `DigitParser → '.' → EParser → ExponentParser`、`2e` は `SignParser → DigitParser → OneOrMore`、
  `2e+` は `DigitParser → OneOrMore`。成功した指数末尾は `DigitParser`。
  従来の曖昧な `number` 候補は残さない。
- Java の表示文字列で引用された literal は、Rust の候補では原文になる（`'.'` と `.`）。
  Rust の内部記録順は Java と比較する。公開 `ParseError.expected` の sort/dedup 契約は維持し、公開 API は候補集合を比較する。
- 負の PEG 先読み内で表示 hint の無い内部 parser が失敗すると、Java はその候補の直後に
  外側の `negative lookahead` を加える。Rust も同じ位置・順序で記録する。
- 成功 memo は Java と同じく、初回評価で記録した hint を再生する。
  `memo` は同じ Num を正の先読みで2回、負の先読みで1回呼ぶ。
  memo OFF は最後の評価時の `negative lookahead` を含み、SAFE_FAILURES は正の先読みで保存した候補を再生するため含まない。
  この Java 自体の memo による違いも各設定の oracle で固定し、実際の成功 memo hit を両言語で確認する。
- 低水準 API の Auto は Detailed。DetailedOnFailure は記録せず、最遠位置0・空候補・stack無し。
  full-input API の Auto / DetailedOnFailure は失敗時に Detailed で再解析する。
  Number の入口で記録の有無を一度だけ決める。その成功パスでは新しい診断名の割当や記録を行わず、内部テストで確認する。

`NumberTokenOracleTest` と Rust の `number_token` / 内部記録順テストがこのファイルを読む。
`NumberTokenConformanceTest` は生成 Java parser を比較し、Java-host と native-host の Rust を個別に compile・実行する。
UBNF では Number の負の PEG 先読みを直接記述できないため、生成した Num の式を手書きの assertion で包んで検証する。
通常の UBNF で到達できる5形は PBT regression corpus にも登録する。
