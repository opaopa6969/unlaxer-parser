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
- `failure-memo` は `Root ::= (!Num 'x') | Num`、`Num ::= NUMBER` に `-` / `+` を与える (#794)。
  OFF の候補は `DigitParser, OneOrMore, '.', Chain`、SAFE_FAILURES は先読み内の失敗 memo を再生し、
  `DigitParser` の直後に `negative lookahead` を含む。両言語とも拒否・consumed/matched CP0・最遠 CP1・
  stack `Root@0>Num@0@1/0`。候補の内部順序と公開集合、および Detailed / Auto の実 failure hit を固定する。
  Java の key は parser identity・consumed/matched・TokenKind・invert・stateVersion・reset policy。
  深さを key に含めず、保存時より深い再生を lookup で拒否する。Chain は lookup **前**に reset policy を false にする。
  Rust のこの再現例では先読み内外の rule 深さは同じで、旧 key の差は reset bit（false / true）だった。
  Chain に当たる rule（Sequence・Number・Identifier・CAPTURE など。生成 Num の Chain を含む）は式の評価時と同じ false を lookup 前にも設定し、
  成功と Classic token mode の失敗は hit でもその false を残し、それ以外の失敗の後は memo OFF と同じく
  呼び出し前の reset に戻す（memo の透過性）。parser frame を加えない MetadataCapture を透過し、mapping 用の生成 rule も検証する。
  rule 深さを含む key・expression 深さの再生検査・他の parser の reset/nonOrdered key は変更しない。
  異なる rule 深さ全般で Java と同じ hit を保証するものではない。
- 低水準 API の Auto は Detailed。DetailedOnFailure は記録せず、最遠位置0・空候補・stack無し。
  full-input API の Auto / DetailedOnFailure は失敗時に Detailed で再解析する。
  Number の入口で記録の有無を一度だけ決める。その成功パスでは新しい診断名の割当や記録を行わず、内部テストで確認する。

## 比較の範囲と既知の差

- Num の rule 自体が失敗する形（`Root ::= Num` に `a` など）は、`bare` 以外の oracle に入れない。
  部分木に表示 hint が無い rule が失敗すると、Java はその rule の class 名（生成 parser の class 名）を
  候補に加えるが、Rust は加えない。失敗した Chain が子の hint（`negative lookahead` や literal）を展開して
  候補に加えること、失敗を1件も記録しない成功 parse の最遠位置（Java は消費位置、Rust は 0）も、Number に限らない host 間の差である。
  PBT も host 間では期待候補を比べない（[docs/property-based-testing.md](../../docs/property-based-testing.md)）。
  #752 のレビューでは、Number を使う16形・36入力と、7文字種の長さ4までの全入力（7形）を両 host で比べた。
  この3種類の一般差を除くと、Rust に余分な候補は無く、共通する候補の順序も一致した。

## 性能

記録は Detailed のときだけ行い、遅延診断（full-input API の Auto / DetailedOnFailure）の成功パスは変えない。
Detailed では Number ごとに4〜6件の失敗記録が増える。名前は context ごとに1回だけ intern する（`ParseContext::number_names`）。
`Root ::= Item*`、`Item ::= Num ';'` に1種類の項を128回並べた入力で、release・1 core 固定、master と同じプロセスで交互に測った
（11回の中央値、負荷平均17〜19。DetailedOnFailure でも ±10% 揺れる）。

| 項 | memo OFF + Detailed | SAFE_FAILURES + Detailed |
|---|---|---|
| `12;` | +8.9% | +41.6% |
| `+12.5e-3;` | +7.8% | +4.4% |
| `7;-0.5;3e2;` | +14.8% | +30.0% |

SAFE_FAILURES の増分は、Java と同じく成功 memo が hint を保存・再生するための memo frame の記録である。#197 の凍結中なので #795 で追跡する。

`NumberTokenOracleTest` と Rust の `number_token` / 内部記録順テストがこのファイルを読む。
`NumberTokenConformanceTest` は生成 Java parser を比較し、Java-host と native-host の Rust を個別に compile・実行する。
UBNF では Number の負の PEG 先読みを直接記述できないため、生成した Num の式を手書きの assertion で包んで検証する。
通常の UBNF で到達できる5形は PBT regression corpus にも登録する。
