# 一階の引数付き規則（#701）

Macro PEG（[macro-peg/macro_peg](https://github.com/macro-peg/macro_peg)）の引数付き規則のうち、
**一階の範囲**を UBNF に入れる。引数は名前渡し（call-by-name）の式で、規則やラムダを値として
渡す高階の形は扱わない。展開は生成前の文法変換で、Java と native Rust の UBNF frontend が同じ
手順で単相化（monomorphize）する。runtime と生成器は変えない。

```ubnf
grammar Let {
  @whitespace: none
  @root @mapping(Let, params=[name, value])
  Let ::= Kw('let') Ident @name Token('=') Num @value Token(';') ;
  ...
  Spacing ::= { ' ' } ;
  Token(p) ::= p Spacing ;
  Kw(n) ::= Token(n) ;
  Sep(item, sep) ::= item { sep item } ;
}
```

## 構文

- 定義は `Name(p1, ..., pn) ::= body ;`、呼び出しは `Name(e1, ..., en)`。名前と `(` の間に
  空白・改行・コメントを置かない。各引数は空でない規則本体で、`|` の choice も書ける。
  呼び出しにも後置の `?` `*` `+` `{n,m}` `% sep` と `@capture` を付けられる。
- 隣接した `Name(` が呼び出しになるのは、**同じ grammar ブロックに `Name(params) ::=` の定義がある
  名前だけ**。同じファイルの別の grammar の定義は影響しない。それ以外の `Foo('a')` は従来どおり
  「参照 `Foo` と group `('a')`」のまま。定義を持たない
  文法の構文木・生成物は変わらない（両 frontend は展開処理そのものを通らない）。
- 定義の引数に `=` を付けると値渡しの引数になる（`Body(=tag) ::= ...`。展開は
  [値渡しの引数](call-by-value-arguments.md)、#704）。
- `CAPTURE` `SAME_AS` `CAPTURE_SCOPE` `ERROR` は既存の関数形式の綴りなので、引数付き規則の名前に
  できない（`E-PARAM-RESERVED`）。これらの呼び出しの構文は従来どおり。
- 定義側の `F (x) ::=`、空の `F()`、末尾 `,`、引数位置の literal は構文エラー。
- UBNF 自身を記述した `unlaxer-dsl/grammar/ubnf.ubnf` には入れていない。呼び出しかどうかが
  同じ grammar の定義に依存する（文脈依存）ためで、frontend の手書き解析だけが扱う。

## 検査の順序

frontend は構文解析のあと、文法ごとに次の順で検査し、最初の違反を位置付きで拒否する。

1. **定義**: 予約名（`E-PARAM-RESERVED`）、注釈付きの定義（`E-PARAM-ANNOTATION`）、規則名・
   引数名の重複（`E-PARAM-DUPLICATE`）、規則名・token 名と同じ引数名（`E-PARAM-SHADOW`）。
2. **使用**: 引数の数（`E-PARAM-ARITY`。引数なしの裸の参照 `F` と `F ('a')` は 0 個として数える）、
   定義本体の中の未定義の名前（`E-PARAM-UNDEFINED`）、
   定義本体と実引数の中の `@capture` / `@typeof`（`E-PARAM-CAPTURE`）。
3. **循環**: 引数付きの呼び出しのグラフ（下記）が循環すれば拒否（`E-PARAM-CYCLE`）。
4. **展開**: 単相化する。次のどれかで拒否する（`E-PARAM-LIMIT`）。
   - 実体化した atomic 要素の合計が 65536 を超える。数えるのは、呼び出しごとに作る実引数と、
     新しく作る instance の本体（同じ鍵の呼び出しが instance を共有しても、実引数は呼び出しの度に数える）。
   - **展開の入れ子**が 128 段を超える。展開の入れ子は、呼び出しの位置で開いている規則本体の数
     （呼び出し側の規則本体と囲む group・optional など、および展開中の instance 本体を、instance の
     境界をまたいで数える）に、そこで作る実引数または instance 本体の入れ子を足したもの。すべての
     instance を呼び出しの位置に書いた場合の入れ子にあたる。実引数を先に、instance を後に判定する。
   生成した名前が既存の規則・token・別 instance と重なれば拒否（`E-PARAM-NAME-COLLISION`）。
5. 展開後の文法に、既存の validator と生成器の検査（`E-LEFT-RECURSION` の nullable 不動点と
   消費前到達グラフ、`E-RULE-UNDEFINED` など）をそのまま当てる。

上限は既存の値にそろえた。65536 は生成器の traversal の visits 上限、128 は native frontend の
入れ子上限（`MAX_NESTING`）である。大きさは、代入する実引数ごとに一度だけ測った大きさと深さを使い、
置換後の木を作らずに飽和加算で見積もる（呼び出しは参照になるので 1 要素、その実引数は呼び出しの
位置で数える）。実引数は見積もりが上限内のときだけ作り、正規形の鍵もその後で作る。したがって鍵の
文字列と作る木は上限に比例する大きさに収まり、呼び出し先が引数を使わない場合
（`D0(x) ::= 'a'; Dk(x) ::= D(k-1)(x x)`）の指数的な引数の成長も位置付きで拒否する。
展開の入れ子の上限は、展開の再帰の深さを両 host で固定するためのもの（Java の
`StackOverflowError` や Rust の stack overflow ではなく `E-PARAM-LIMIT` にする）。呼び出しの連鎖と
1 instance の入れ子を別々に 128 で抑えると、再帰はその積（最大で約 16000 段）になる。このため 1 本の
累積の値で抑える。再帰の深さは、規則本体 1 個あたりの展開関数のフレーム数に 128 を掛けたもの
（と、instance を作るときの見積もりの再帰）までになる。これは frontend が構文解析で許す入れ子
128 と同じ規模なので、Rust の debug / release、Java の JIT / インタプリタのどれでも同じ結果になる
（corpus と再現入力 71 文法で、Rust debug / release と Java の JIT / `-Xint` の結果が一致することを確認した）。

| code | 位置 | 内容 |
|---|---|---|
| `E-PARAM-ARITY` | 呼び出し・参照 | 引数の数が定義と違う。定義の行・列も示す |
| `E-PARAM-UNDEFINED` | 参照 | 定義本体の未定義の名前 |
| `E-PARAM-CYCLE` | 循環を閉じる呼び出し | `F -> G -> F` の経路を示す |
| `E-PARAM-LIMIT` | 上限を超えた呼び出し | 要素数 65536、展開の入れ子 128 |
| `E-PARAM-ANNOTATION` | 定義 | `@mapping` などの注釈を持つ引数付き規則 |
| `E-PARAM-CAPTURE` | 呼び出し・定義 | 引数・定義本体の `@capture` / `@typeof` |
| `E-PARAM-SHADOW` | 定義 | 規則名・token 名と同じ引数名 |
| `E-PARAM-DUPLICATE` | 定義 | 同名の規則・token、同名の引数 |
| `E-PARAM-RESERVED` | 定義 | 既存の関数形式の綴り |
| `E-PARAM-NAME-COLLISION` | 呼び出し | 展開名が既存の名前と重なる |

位置は Unicode scalar 単位の半開区間で、行・列は 1 始まり（CR、LF、CRLF をそれぞれ 1 改行）。
Java は `ParameterizedRules.ExpansionException`（`code()` `start()` `end()` `line()` `column()`
`diagnostic()`）、native Rust は `Diagnostic { kind: InvalidValue, .. }` で、本文は両 host で同一。
`check --target rust`（両 host）は `structure: "unavailable"` とこの code・span を返す。

## 展開の規則

- 実引数を先に展開してから（呼び出しを含まない式にしてから）定義本体に代入し、その本体に残る
  呼び出しを続けて展開する。参照実装（Scala `MacroExpander`）と同じ順である。
- 引数を 1 個の要素だけで書いた場合（capture なし）はその要素をそのまま、それ以外は group として
  代入する。`Token('a' 'b')` の本体は `( 'a' 'b' ) Spacing` と書いた場合と同じ構文木になる。
- instance の名前は `Name_` に、引数の正規形の FNV-1a 64bit を 16 桁の小文字 16 進で付けたもの。
  正規形の鍵は `Name(arg1,arg2,...)` で、各引数は `|` で区切った選択肢・空白で区切った列と、
  次の要素表記からなる（`t(` 符号位置の 16 進を `.` で区切る `)` が literal、`r(名前)` が参照、
  `g(...)` `o(...)` `z(...)` `k(...)` が group・optional・repeat・capture scope、`p(x)` が `x+`、
  `b(min,max,x)`（上限なしは `*`）、`s(x,sep)`、`c(名前,...)`、`y(名前)`、`e(...)` が ERROR）。
  鍵は単射なので、**同じ鍵の呼び出しだけが 1 個の instance を共有する**。
  名前に `__` を含めないのは、import の `alias.Rule` が生成物で `alias__Rule` になるのと重ねないため。
- 規則の並びは、引数なしの規則を元の順に置き、その後に instance を作った順に並べる。instance は
  本体を展開する前に自分の位置を取る（先行順）。引数付き規則の定義そのものは出力に残らない。
  呼ばれない定義も 1〜3 の検査は受ける。
- 原文の位置を保つ。instance の規則は定義の span、呼び出しを置き換えた参照は呼び出しの span、
  代入した要素は実引数の span、代入時に作る group は実引数全体の span を持つ。Java の
  `parseWithSource` では instance と group の origin が `SYNTHETIC`、それ以外は元の origin。
  `ApiImpact` と portability は展開後の規則にもこの位置を使う。
- 展開後の規則は注釈を持たない transparent な規則なので、AST 型や評価 API を増やさない。
  手で展開した文法との `impact` は差分なしになる（共通 corpus で確認）。

## なぜこの順序で安全か

出典は [kmizu/lean4-peg の docs/theorems.md](https://github.com/kmizu/lean4-peg/blob/main/docs/theorems.md)
（M-PEG-5、M-PEG-6、M-PEG-7、CE-002、CE-005）である。以下はこの実装をそれらの定理へ対応付ける
証明スケッチで、新しい機械証明ではない。

**記法。** 引数付き規則の集合を P とする。F ∈ P の本体（実引数の中を含む）に呼び出し H(...)（H ∈ P、
引数 1 個以上）があるとき辺 F → H を引く。これは M-PEG-5 の「0 引数でない呼び出し」のグラフと同じで、
引数なしの規則からの辺は数えない（引数なしの規則を経由する再帰 `Expr ::= Paren(Expr) | 'n'` は
展開の循環にならない）。

**補題 1（展開は止まる。M-PEG-5 `rank_lt_of_acyclic`、`expand_hasCall_eq_false`）。**
手順 3 でグラフが非循環なので、各 F に有限の rank（F から出る最長経路の長さ）があり、辺 F → H では
rank(H) < rank(F)。H(a1, ..., an) の展開では、まず各 ai を展開して呼び出しを含まない式 ai' にする。
ai' を H の本体に代入しても呼び出しは増えない（M-PEG-5 `subst_hasCall_eq_false`）。したがって代入後に
残る呼び出しは H の本体にもともとあったものだけで、その callee の rank は rank(H) より真に小さい。
手順 4 の上限は展開を途中で拒否するだけで、受理した展開結果は変えない。展開の再帰は、呼び出しの
連鎖が rank 以下であることに加えて、展開の入れ子 128 以下に収まる。
（rank, 式の大きさ）の辞書式順序がどの再帰でも下がるので、展開は有限回で終わり、出力に呼び出しは
残らない（T-fix）。instance の共有は同じ鍵の再計算を省くだけで、この測度を増やさない。

**補題 2（展開は意味を変えない。M-PEG-6 `expand_preserves_cbn`、CE-005）。**
M-PEG-6 は、非循環・アリティが正しい・callable な値を返す規則がない、という前提で、
call-by-name の導出が展開前後で一致することを示す。一階の範囲では引数は式だけなので最後の前提は
満たされる。アリティの前提は CE-005（`F(x) = "a"; S = F("b","c")` が展開前は失敗、展開後は成功）が
示すとおり省けないので、手順 2 で展開前に検査する。この実装は M-PEG-6 のインライン展開の代わりに、
展開結果を名前付きの引数なし規則に置く。PEG では引数なしの参照はその本体と置き換えても導出が
変わらない（規則の展開）ので、名前付きにしても受理する言語・残り入力は同じである。この最後の
置き換えは本リポジトリ側の議論で、lean4-peg の定理には含まれない。

**補題 3（検査は展開後にだけ当てる。M-PEG-7 `generated_parser_total`、CE-002）。**
M-PEG-7 は、非循環なマクロ文法の展開が整形式性検査 wfB（nullable の不動点、`checkRepetition`、
消費前に到達する呼び出しのグラフの非循環性 `leadsToSelf`）を通れば、生成パーサが全入力・全開始規則で
停止することを示す（`generated_parser_total`）。一方 CE-002 の `F(x) ::= x; S ::= F(S)` は、展開前の
文法では S の本体に F の呼び出ししか見えず、F の本体は引数 x だけなので、S が入力を消費せずに S へ戻る
経路が検査から見えない。展開後は `S ::= F_h; F_h ::= S` になり、既存の `E-LEFT-RECURSION`
（nullable の不動点と消費前到達グラフ。[left-recursion.md](left-recursion.md)）が拒否する。
したがって既存検査は手順 5 で展開後の文法に当て、展開前の文法を検査して安全と判断しない。
corpus の `ce002-self-argument` と、nullable な接頭辞が展開後にだけ現れる
`nullable-prefix-after-expansion` がこの負例である。

この repository は wfB と 1 点だけ違う。wfB の `checkRepetition` は nullable な本体の繰り返しを
拒否するが、Classic の繰り返しは「進まなかった成功で止まる」progress guard を持つ
（[nullable-repetition.md](nullable-repetition.md)）。M-PEG-7 の三重帰納（入力長 → 左 rank →
式の構造）のうち star の場合は「本体が nullable でないので毎周入力が縮む」を使うが、progress guard の
下では各周回が入力を消費するか、その周回で繰り返しが終わるので、同じ測度で止まる。展開後にだけ
現れる nullable な繰り返しが手書き展開と同じ結果になることを `nullable-repeat-after-expansion` で
確認している。

## 範囲外と制約

- 高階規則、ラムダ、型注釈。値渡しの引数（後方参照）は [call-by-value-arguments.md](call-by-value-arguments.md)（#704）。
- 注釈付きの引数付き規則と、実引数・定義本体の中の `@capture` / `@typeof`。capture は
  instance 側の規則に移り、呼び出し側の `@mapping` から見えなくなるため、まず拒否する。
  呼び出し全体への capture（`Either('a' 'b', 'c') @text`）はできる。
- 呼び出せるのは同じ文法で定義した引数付き規則だけ。import した文法はそれぞれの frontend 解析で
  展開済みになるので、`alias.F(x)` のような修飾付きの呼び出しはない（従来どおり参照と group）。
- 展開名は引数の綴りから決まる。引数を書き換えると instance の名前も変わる。

## 検証

`spec-corpus/parameterized-rules/cases.json` は 10 文法 26 入力の受理例を持つ。受理例には、#699 の
`@nestedCaptures` が instance を alias として透過する例と、連鎖の長さと本体の入れ子の積がちょうど 128 段に
なる 2 例を含む。ほかに、展開後の検査で拒否する 2 例と、位置付きで拒否する 23 例を持つ。
上限の負例の位置は、生成 script が上記の数え方を手で追って求める。期待値はすべて手書きで、受理例は手で展開した等価文法、
instance 名は手書きの正規形の鍵から独立に計算した FNV 値、入力ごとの受理・拒否、CP 位置、AST を
持つ。

- `rust/unlaxer-ubnf/tests/parameterized_rules.rs`: native frontend の展開結果が手書き展開と同じ
  構文木になること、instance 名と span、拒否の code・本文・span・行・列。
- `ParameterizedRuleConformanceTest`: Java frontend について同じ比較と `parseWithSource` の origin、
  portability と Java / Rust 両 target の `impact`（手書き展開との差分なし）。Java の 4 生成器の出力が
  手書き展開と文字単位で一致し、実 compile した parser / mapper が memo 2 × 診断 3 の 6 設定で
  期待値どおりになること。`-DrustConformance=true` では、native の生成物が手書き展開の生成物・
  Java host の Rust 生成物と byte 単位で一致し、両 host の生成物から作った consumer が 6 設定で
  期待値どおりになること、native の `generate` / `check` / `impact` の結果。
- `RustUbnfFrontendConformanceTest`: `tests/fixtures/positive/parameterized-rules.ubnf` と、定義が
  grammar 単位で効くことを示す `parameterized-rules-two-grammars.ubnf` の展開後の構文木が両 frontend で
  一致すること、`negative.tsv` の構文エラー 6 例。
- 展開の入れ子の境界: 平らな連鎖（127 instance で 128 段）と、本体の入れ子 0〜126 段の 8 通りで
  上限ちょうどの文法を Rust は既定の test thread（2 MiB）で、Java は 512 KiB の thread で展開できることと、
  1 段増やすと同じ本文で拒否することを確認する。平らな 127 段の連鎖は、手書きで展開しても既存の Rust
  生成器の構造解析の深さ上限（256）に掛かるため、生成と consumer の比較からは外している。

```sh
RUSTUP_TOOLCHAIN=1.85.0 cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-ubnf --test parameterized_rules
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ParameterizedRuleConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

結果は `unlaxer-dsl/target/parameterized-rules-java.tsv` と `parameterized-rules-rust.tsv` に残り、
CI の "Rust backend and Java conformance" job が行数を確認する。公開済みの 3.3.0 には含まれない。
