# 値渡しの引数（#704）

[一階の引数付き規則](parameterized-rules.md)（#701）の引数に、値渡し（call-by-value）の印 `=` を
付けられる。値渡しの引数は呼び出しの位置で入力と照合され、一致した文字列がその規則の中での値になる。
規則の本体でその引数を使うと、同じ文字列をもう一度照合する（後方参照）。名前付きの閉じタグや
heredoc を、規則の中に閉じた形で書ける。

```ubnf
grammar Markup {
  @whitespace: none
  @root
  Root ::= Elem { Elem } ;
  Elem ::= '<' Body(Name) ;
  Name ::= Letter { Letter } ;
  Letter ::= 'a' | 'b' ;
  Text ::= 'x' { 'x' } ;
  Body(=tag) ::= '>' { Elem | Text } '</' tag '>' ;
}
```

`<a><b>x</b></a>` を受理し、`<a><b></a></b>` を拒否する。入れ子の `Elem` が同じ `Body` を
呼んでも、内側の呼び出しが終われば外側の `tag` に戻る。

これは糖衣構文で、新しい runtime の意味は足さない。展開は既存の
[CAPTURE / SAME_AS / CAPTURE_SCOPE](general-owned-capture-syntax.md)（[owned capture の契約](owned-capture-replay.md)）
になり、Java と native Rust の frontend が #701 の単相化と同じ手順で行う。生成器と runtime は変えない。

## 構文

- 定義の引数の前に `=` を書く（`Body(=tag) ::= ...`）。`=` と名前の間には、引数の並びの他の字句の
  間と同じく空白を置ける（`Body(= tag)`）。値渡しと名前渡しは混ぜられる（`Swap(=x, =y, sep) ::= ...`）。
- 呼び出しの構文は #701 と同じで、呼び出し側には印を付けない。`F(='a')` は構文エラー。
- `F(==x)`、`F(=)`、`F(x=)` は構文エラー。`=` のない定義は #701 と同じに展開する。
- それ以外の検査（予約名、注釈、重複、規則名・token 名と同じ引数名、引数の数、未定義名、
  呼び出しの循環、実引数と定義本体の `@capture` / `@typeof`、展開の上限、展開名の衝突）は
  #701 と同じ code・本文・位置で拒否する（[検査の順序](parameterized-rules.md#検査の順序)）。
- 値渡しの規則に固有の検査として、本体と実引数の中の `CAPTURE(...)`（`E-PARAM-CAPTURE`）、本体の中の
  値渡しの引数と同じ名前の `SAME_AS(...)`（`E-PARAM-SHADOW`）、展開名にだけ一致する参照
  （`E-PARAM-UNDEFINED`）を位置付きで拒否する（下記）。どれも既存の code で、新しい error code はない。
  本文と位置（CP 区間・行・列）は両 host で同じ。

この印を使った以前の綴りはすべて構文エラーだったので、既存の文法の意味は変わらない。

## 展開

値渡しの引数 `p1 ... pk`（宣言順）を持つ規則 `F` の instance は、次の本体を持つ。

```ubnf
F_<hash> ::= CAPTURE_SCOPE(CAPTURE(F(=p1), a1) ... CAPTURE(F(=pk), ak) body') ;
```

- `ai` は `pi` に渡した実引数で、名前渡しの代入と同じく 1 個の要素ならそのまま、それ以外は
  group にする（`Body('a' 'b')` は `CAPTURE(Body(=tag), ( 'a' 'b' ))`）。
- `body'` は定義本体で、値渡しの引数の使用をすべて `SAME_AS(F(=pi))` に、名前渡しの引数を実引数に
  置き換えたもの。本体が 1 個の選択肢なら要素を CAPTURE の後に並べ、`|` を含むなら group にする。
- 値渡しの引数は、本体より前に宣言順（左から右）に照合する（Macro PEG の CallByValueSeq と同じ順）。
  名前渡しの引数は従来どおり使った位置で照合する（ただし `CAPTURE_SCOPE` の中なので、そこでの
  `CAPTURE` は下記のとおり拒否する）。本体で使わない値渡しの引数も入力を消費する。
- 値渡しの引数を別の呼び出しに渡すと、その実引数は `SAME_AS(F(=p))` になる。名前渡しに渡せば
  呼び出し先の使用位置で、値渡しに渡せば呼び出し先の CAPTURE で、同じ文字列をもう一度照合する。
- instance の名前、正規形の鍵、共有、規則の並び、上限の検査は #701 と同じ。鍵は呼び出しの実引数から
  作るので、値渡しかどうかで名前は変わらない。鍵の中では値の使用が `y(F(=p))` と書かれる。
- 原文の位置: 値の使用を置き換えた `SAME_AS` はその使用の位置、CAPTURE の中身は実引数の位置を持つ。
  `CAPTURE_SCOPE`、`CAPTURE` と、それらを包む本体・列・group は定義本体の位置を持つ。Java の
  `parseWithSource` では、これらと置き換えた `SAME_AS` の origin が `SYNTHETIC`。

### capture の名前

値渡しの引数 `p` の binding 名は `F(=p)`（規則名、`(=`、引数名、`)`）である。UBNF で書く
`CAPTURE` / `SAME_AS` の名前は識別子なので、`(`、`=`、`)` を含む名前は書けない
（`CAPTURE(F(=x), 'a')` は構文エラー）。このため展開が作る binding は、同じ文法・import した文法の
どちらのユーザーの capture とも名前を共有しない。ユーザーの capture が引数と同じ名前
（`CAPTURE(tag, ...)` と `Body(=tag)`）でも別の binding で、名前渡しの実引数の中の `SAME_AS(tag)` は
ユーザーの binding を読む（corpus の `user-captures-beside-values`）。

生成物はこの名前を文字列として持つだけで、識別子にはしない（Java の `NamedCapture("Body(=tag)", ...)`、
Rust の `Expr::OwnedCapture("Body(=tag)", ...)`）。

### 値渡しの規則の中の capture

`CAPTURE_SCOPE` は、出るときに中で作ったすべての binding を以前の状態に戻す。値渡しの引数を 1 個でも
持つ instance は本体全体をこの scope で包むので、そこに書いたユーザーの `CAPTURE` は呼び出しの後に
見えなくなる。名前渡しだけの規則（#701）では見えるので、黙って意味が変わらないように次を拒否する。

- 値渡しの引数を持つ規則 `F` の本体の中の `CAPTURE(name, ...)`。本体の中の呼び出しの実引数も含む。
- `F` の呼び出しの実引数（名前渡し・値渡しのどちらも、さらにその中の呼び出しの実引数も）の中の
  `CAPTURE(name, ...)`。どこから呼んでも、その実引数は `F` の scope の中で照合されるため。
- 位置はその `CAPTURE` 要素。本文は
  `E-PARAM-CAPTURE: CAPTURE(u, ...) cannot appear in the body or call arguments of rule F, whose value parameters restore every capture when it returns`。

```ubnf
Main ::= F('a', CAPTURE(u, 'b')) SAME_AS(u) ;   // E-PARAM-CAPTURE（名前渡しの F(x, y) なら abb を受理する）
F(=x, y) ::= x y ;
```

`CAPTURE_SCOPE(...)` と `SAME_AS(...)` は書ける。本体から参照した引数なしの規則や別の引数付き規則の
本体にある `CAPTURE` は拒否しない。それらの binding も `F` から戻るときに戻る（手で
`CAPTURE_SCOPE` を書いた場合と同じ）。将来、`CAPTURE_SCOPE` の代わりに値の binding だけを戻す仕組み
（新しい runtime の操作）を足せば、この拒否は緩められる。この issue は runtime を変えない制約なので、
拒否を選んだ。

また、本体の中で値渡しの引数と同じ名前の `SAME_AS(x)` は、引数ではなく同名のユーザー binding を読むため
（`F(=x) ::= SAME_AS(x) x;` は実行時に必ず失敗する）、その位置で `E-PARAM-SHADOW` として拒否する。
値を照合するには `x` と書く。同名の `CAPTURE(x, ...)` は上の規則で拒否される。

### 展開名への参照

instance の名前 `F_<hash>` は展開が作る名前で、文法に書いた参照からは届かない。引数付きの定義の外で
書いた参照が、文法に定義がなく展開名にだけ一致するとき、その参照の位置で
`E-PARAM-UNDEFINED: rule Root references 'H_...', which only names a rule created by parameterized expansion`
として拒否する（展開の後、最初の 1 個）。このような参照は値渡しの binding を `F` の外から読めて
しまう。名前渡しだけの規則（#701）にも同じに当てる。展開名と同じ名前の規則を書いた場合は従来どおり
`E-PARAM-NAME-COLLISION`。

## 有効範囲と巻き戻し

- 有効範囲は呼び出された instance の `CAPTURE_SCOPE` の中に閉じる。scope は外側の binding を読めて、
  出るときに成功・失敗・Java の子の例外のどれでも以前の binding に戻す
  （[owned capture の契約](owned-capture-replay.md)）。parse の後に `F(=p)` の binding は残らず、
  外側に同じ名前の binding があればそれに戻る。
- 失敗した選択肢、資源上限、例外での巻き戻しは owned capture の契約に従う。新しい規則は足さない。
- 既存の生成器は binding を読み書きする規則（と、それに依存する規則）の memo を保守的に止める。
  値渡しの instance と、それを呼ぶ規則も同じ扱いになる。

### 同じ名前を持つ instance の入れ子

同じ規則の instance は同じ binding 名を使う（`Pair(Pair('b'))`、`Elem` の入れ子）。それでも各使用は
その使用を作った呼び出しの値を読む。

1. `F(=p)` を束縛するのは `F` の instance の CAPTURE だけで、その scope の中だけで有効になる。
2. `SAME_AS(F(=p))` が現れるのは、`F` の定義本体から作った式（`F` の instance の本体と、そこから
   呼び出し先へ渡した実引数）だけである。
3. 内側の `F` の scope の中でこの式が評価されるには、式が内側の `F` の本体か実引数に入っている
   必要がある。本体には `F` 自身の使用しかない。実引数に入るには、外側の `F` から引数付きの呼び出しを
   たどって `F` に戻る経路が要るが、その経路は手順 3 の循環として拒否している。引数なしの規則を通る
   再帰（`Elem ::= '<' Body(Name)`）は引数を運ばないので、その中の `F` の呼び出しの実引数に外側の式は
   入らない。
4. したがって内側の scope が開いている間に外側の使用は評価されず、内側の scope は終わるときに外側の
   値に戻す。各使用は、名前渡しの代入と同じく字句的に対応する呼び出しの値を読む。

別の文法の同名の規則が作る binding も、同じ理由（その instance の本体と実引数はその文法の展開からしか
来ない）で互いの使用に割り込まない。

## 上限と停止性

テンプレートは定義ごとに 1 回作る固定の大きさの本体で、#701 の展開・見積もり・上限の手順はこれを
普通の定義本体として扱う。停止性の議論（[補題 1〜3](parameterized-rules.md#なぜこの順序で安全か)）は
そのまま当てはまる。

- **要素数**: instance 本体は `CAPTURE_SCOPE`（1）、値渡しの引数ごとの `CAPTURE`（1）と代入した実引数、
  選択肢を包む group（1）を含めて数える。たとえば `Root ::= F(S 個の要素); F(=x) ::= 'b';` は呼び出しの
  実引数 S と instance 本体 S + 4 で 2S + 4 要素になり、S = 32766 で 65536 ちょうど、S = 32767 で拒否する。
- **展開の入れ子**: instance 本体と `CAPTURE_SCOPE` の本体で 2 段、値の実引数はさらに CAPTURE の本体の
  1 段の中に入る。呼び出しの位置で開いている本体が 1 段のとき、`F(=x)` の本体は group 125 段まで
  （1 + 2 + 125 = 128）、名前渡しの `F(x)` は 126 段まで受理する。`Ck(=x) ::= C(k+1)(x)` の連鎖は
  C0..C62 まで（C63 が 130 段で拒否）。
- **大きさが増えない**: 値の使用は `SAME_AS` 1 要素に置き換わるので、使用を何度書いても実引数は
  増えない（`Dk(=x) ::= D(k-1)(x x)` を 40 段重ねても instance は 41 個で、名前渡しの指数的な成長が
  起きない）。

背景: Macro PEG の CallByValueSeq は値を入力の部分文字列に限る。[kmizu/lean4-peg](https://github.com/kmizu/lean4-peg)
は、値渡しでは引数が入力の部分文字列に限られるので受理の判定が多項式時間に収まることを示している
（`iterBoundV_le`）。この実装では値は runtime の owned capture として持ち、文法の展開は有限
（上の上限）なので、上の議論はその定理に依らない。runtime の計算量はこの変更で変わらず、
binding に依存する規則は memo を止めるので、packrat の線形時間は保証しない。

## 範囲外と制約

- UBNF の規則本体には否定先読みがない。このため heredoc の例（corpus の `heredoc`）は本文の行を
  字下げで閉じタグの行と区別する。任意の本文の heredoc には規則本体の否定先読みが要る。
- 値は文字列で、照合は `SAME_AS` と同じ完全一致（正規化・大文字小文字の同一視なし、空の値は幅 0 で
  一致）。値の構造や AST は渡さない。
- 呼び出し側での値渡しの指定、高階規則、ラムダは扱わない。引数付きの呼び出しの循環は #701 と同じく
  拒否する。値は呼び出された規則の中でしか読めないので、`Rest(=t) ::= t | Line Rest(t)` のような
  値を運ぶ再帰は書けず、繰り返し（`{ Line } tag`）で書く。
- 生成名 `F(=p)` は parse 中の runtime の binding 一覧（Java の `ParseContext.captured`、Rust の
  `captured_text` など）には見える。parse の後には残らない。
- Rust の preferred snapshot の fingerprint は parser の生成文字列から作るので、binding 名を変えると
  値が変わる（手書きの等価文法と比べるときはこの値だけを伏せて比べる）。
- `unlaxer-dsl/grammar/ubnf.ubnf` には #701 と同じ理由で入れていない。
- 公開済みの 3.3.0 には含まれない。

## 検証

`spec-corpus/call-by-value/cases.json`（[README](../spec-corpus/call-by-value/README.md)）は 8 文法 36 入力の
受理例と、位置付きで拒否する 20 例（上の capture・`SAME_AS`・展開名への参照の 8 例を含む）を持つ。受理例は名前付きの閉じタグ、heredoc、複数の値と名前渡しの
混在、値の転送と同じ規則の入れ子、空の値と使わない値、失敗した選択肢での再試行と選択肢の本体、
ユーザーの capture と同名の引数、入れ子 128 段ちょうどの境界で、それぞれ CAPTURE_SCOPE / CAPTURE /
SAME_AS で手書きした等価文法、instance の鍵と名前、入力ごとの受理・拒否、失敗位置、AST、parse 後の
context の状態（成功・消費位置・一致位置）を持つ。期待値はすべて手書きである。

- `rust/unlaxer-ubnf/tests/call_by_value.rs`: native frontend の展開が手書きの等価文法と同じ構文木
  （binding 名だけを対応表で置き換える）になること、instance 名と span、拒否の code・本文・span・行・列、
  要素数の上限ちょうど、値の連鎖の入れ子の上限（既定の 2 MiB の test thread）、生成名が書けないこと。
- `CallByValueConformanceTest`: Java frontend の同じ比較と `parseWithSource` の origin、portability と
  Java / Rust 両 target の `impact`（手書きの等価文法との差分なし）。Java の 4 生成器の出力が、
  binding 名を置き換えると手書きの等価文法の出力と文字単位で一致すること。両方の生成物を実 compile し、
  memo 2 × 診断 3 の 6 設定で期待値どおりになり、parse の前に置いた生成名（手書き側はその識別子）の
  binding が parse の後に元の値へ戻ること。`-DrustConformance=true` では、Java host と native の Rust
  生成物が byte 単位で一致し、native の生成物が手書きの等価文法の生成物と（binding 名を置き換え、上記の
  fingerprint だけを伏せて）一致し、両 host の生成物から作った consumer が 6 設定で同じ期待値と
  context の状態になること、native の `generate` / `check` / `impact` の結果。
- `RustUbnfFrontendConformanceTest`: `tests/fixtures/positive/call-by-value.ubnf`（`=` の後の空白、
  選択肢の本体、値と名前渡しの混在）の展開後の構文木が両 frontend で一致すること、`negative.tsv` の
  構文エラー 5 例。
- 既存の文法が変わらないこと: base（36ee3e18）と head で、repository の `.ubnf` と corpus・tsv に
  埋め込まれた文法 1424 個について、native の構文木（`inspect`）と `generate` の全生成物、Java の
  構文木と 4 生成器・Java host の Rust 生成物が一致することを確認した（#704 の PR 本文）。

```sh
RUSTUP_TOOLCHAIN=1.85.0 cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-ubnf --test call_by_value
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=CallByValueConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

結果は `unlaxer-dsl/target/call-by-value-java.tsv` と `call-by-value-rust.tsv` に残り、CI の
"Rust backend and Java conformance" job が行数を確認する。
