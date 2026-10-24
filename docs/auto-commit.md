# FIRST 集合による cut 位置の lint と `@autoCommit`（#702、#728）

[#700](cut-and-labeled-failures.md) の `COMMIT` を入れても意味が変わらない位置を、FIRST 集合から
求める。位置は lint として報告し、規則に `@autoCommit` を付けたときだけ frontend がそこへ
`COMMIT` を挿入する。既定は OFF で、注釈の無い文法の構文木と生成物は変わらない。参考にしたのは
kmizu/yapp の `translator/AutoCutInserterUsingFirstSet.java`（PASTE 2010）である。yapp の
`truncate`（cut 位置より前の memo を捨てる最適化）は性能目的なので、#197 の凍結に従い扱わない。
文法全体の `@whitespace: javaStyle`（空白とコメントを自動で読み飛ばす文法）も対象にする（#728、
下の「空白のある文法」）。

```ubnf
grammar Stmts {
  @root
  Root ::= { Statement } ;
  @autoCommit
  Statement ::= 'let' Name '=' Value ';' | 'print' Value ';' ;
  Name ::= 'n' ;
  Value ::= 'v' | '(' Value ')' ;
}
```

`Statement` は次の文法と同じ構文木になり、生成物は byte 単位で一致する。

```ubnf
  Statement ::= 'let' COMMIT(Name) '=' COMMIT(Value) ';' | 'print' COMMIT(Value) ';' ;
```

入力 `letx` は、最も遠い失敗ではなく `Name` を始めた位置 3 で確定エラー
（`commit`、`unexpected input`）になる。`letn=v;` のように受理される入力の結果は変わらない。

## 挿入の形

- 対象は `@autoCommit` を付けた規則の本体にある選択肢（入れ子の group・optional・repeat・
  separated の中を含む）。挿入できる選択肢は「選択肢 `e1 e2 … en`（n ≥ 2）」で、下の条件を
  満たすもの。
- `e1` の後ろの要素のうち、**名前空間の無い規則参照・token 参照**（`@typeof` なし）を 1 個ずつ
  `COMMIT(要素)` で包む。`@name` の capture は `COMMIT` の内側に残す（`COMMIT(Name @name)`）。
  この形だと AST 型と mapper は変わらない。`COMMIT(Name) @name` にすると Java の AST 型が
  `Optional<Object>` に変わるので使わない。
- 終端記号・group・optional・repeat などは包まない。Java の CST では `COMMIT('b')` の本体が
  group helper になり、CST に 1 階層増える（Rust の CST は変わらない）。規則参照を包んだ
  `COMMIT(B)` は両 host とも CST を変えない。終端記号の失敗は従来どおり通常の失敗として報告される。
- Java の ParserGenerator は、参照 1 個だけを包む `COMMIT` / `LABEL` には group helper を作らない
  （`new Commit(Parser.get(BParser.class))` と inline で生成するので、helper はどこからも使われない）。
  #700 の時点では使われない helper を作って番号を消費していたため、注釈を付けると後ろの group の
  helper 名（`R0Group0Parser` → `R0Group1Parser`）がずれ、CST に出る parser class が変わっていた。
  手で書いた `COMMIT(参照)` / `LABEL(L, 参照)` の生成物もこの helper の分だけ変わる（#700 は未リリース）。
  Rust の生成物は group helper を持たないので変わらない。
- message は付けない（既定の `unexpected input`）。引数付き規則の instance は `Tok_dd2f…` の
  ような書けない名前なので、名前から message を作ると利用者に読めない。
- 挿入の後、注釈 `@autoCommit` は規則から取り除く。したがって書き換えた構文木は、同じ位置に
  手で `COMMIT` を書いた文法の構文木と等しい（Java の record 比較、Rust の canonical 比較）。
  validator、portability、generator、LSP は `@autoCommit` を知らない。
- 注釈は規則単位だけで、文法全体の設定は設けない。文法全体に効かせたいときは各規則に付ける。
  候補が無い規則、文法が解析の対象外のとき（下記）は、注釈を取り除くだけで何も挿入しない。
  解析を当てられない注釈は lint の警告 `W-AUTO-COMMIT-SKIPPED` で分かる（下記）。
- 調べるのは注釈を付けた規則の選択肢だけで、FIRST は節点ごとに memo する。注釈が 1 個なら、
  規則数に比例する時間で済む（1000 規則の文法で、Rust release の frontend は注釈なし 0.02 s、注釈 1 個で 0.03 s）。

### 展開の後に挿入する

引数付き規則（[#701](parameterized-rules.md)、[#704](call-by-value-arguments.md)）の単相化の
**後**に挿入する。理由:

1. FIRST 集合は具体的な本体にしか決まらない。`Tok(p) ::= p` の FIRST は実引数ごとに違い、
   展開前の定義には決まった FIRST が無い。
2. 文脈の検査（下記）は規則の参照位置をたどる。展開後は instance の参照が普通の規則参照になり、
   参照位置がすべて具体的になる。
3. #710 の検査（`E-RAISING-REPETITION` など）と既存の validator も展開後の文法に当たる。
   挿入した結果を同じ構文木で検査できる。
4. 引数付き規則の定義には注釈を付けられない（`E-PARAM-ANNOTATION`）ので、instance の本体には
   挿入しない。`@autoCommit` を付けた規則の中の呼び出しは、展開後の参照として包まれる
   （`Tok('=')` → `COMMIT(Tok_…)`）。手で書いた `COMMIT(Tok('='))` も同じ instance を共有するので
   構文木は一致する。

両 frontend とも構文解析 → 展開 → `@autoCommit` の順で、Java は `UBNFMapper.parse` /
`parseWithSource`、Rust は `unlaxer_ubnf::parse` / `parse_with_source` の中で行う。
新しく作る節点（外側の要素、`COMMIT`、その本体と列）の位置は包んだ要素の位置で、Java の origin は
`SYNTHETIC`。作り直した列・規則などは元の位置を引き継ぐ。

## lint

| | Java | Rust |
| --- | --- | --- |
| 原文を解析 | `AutoCommit.lint(String source)` | `auto_commit::lint(source)` |
| 書き換え前の構文木 | `AutoCommit.parseBeforeAutoCommit(source)`（= `UBNFMapper.parseWithSourceBeforeAutoCommit`） | `auto_commit::parse_before_auto_commit(source)` |
| 展開済みの文法 | `AutoCommit.findings(grammar, snapshot)` | `auto_commit::findings(&grammar)` |
| 警告 | `AutoCommit.lintWarnings(source)` / `warnings(grammar, snapshot)` | `auto_commit::lint_warnings(source)` / `warnings(&grammar)` |

`findings` と `warnings` には**書き換え前の構文木**を渡す。通常の `UBNFMapper.parse` /
`parseWithSource` / `unlaxer_ubnf::parse` が返すのは書き換え後の構文木で、注釈は取り除かれ、挿入した
`COMMIT` は unknown なので、その選択肢は報告されない。

結果は `Finding` の列で、規則の順、その中は原文の順に並ぶ。

| 項目 | 内容 |
| --- | --- |
| code / severity | `I-AUTO-COMMIT` / `info` |
| rule | 規則名（展開後の名前） |
| span | 選択肢（列）の符号位置の半開区間 |
| cut | `e1` の直後の符号位置（cut の位置） |
| commits | `@autoCommit` が包む参照の名前と要素の位置 |
| inserted | 規則に `@autoCommit` が付いているか（frontend が挿入したか） |

lint は注釈の有無と関係なく、挿入できる位置をすべて報告する。`@autoCommit` による書き換えの前の
原文を解析する。lint は既存の portability check や validator の診断には混ぜない。それらは
severity が error だけで、混ぜると既存の文法の `check` の出力が変わるためである。
CLI は設けていない（下の制限事項）。

### 警告 `W-AUTO-COMMIT-SKIPPED`

`@autoCommit` を付けたのに解析を当てられないとき、注釈ごとに 1 件の警告（severity `warning`、
span は注釈 `@autoCommit` の位置）を返す。`reason` は次のどれかで、両 host で同じ値と message を使う。

| reason | 条件 |
| --- | --- |
| `grammar` | 文法が解析の対象外（下の「文法全体の前提」：`@comment`・宣言的 token の空白・規則の空白、設定、root が 1 個でない、左再帰） |
| `opaque` | 規則が不透明（下の「不透明な規則」） |
| `unreachable` | root からたどれない |

解析できたが候補が無い規則は警告しない（なぜ無いかは lint の位置で確かめる）。frontend の構文解析は
警告を出さずに注釈を取り除くので、警告は lint の API から得る。

## 条件

FIRST 集合は runtime の `FirstSet` と同じ表現で持つ。ASCII（0〜127）は正確に、それより上は
「非 ASCII のどれか」の 1 bit にまとめる。ほかに「空で成功し得る（nullable）」と
「模型化していない（unknown）」を持つ。unknown を含む判定はすべて「挿入しない」側に倒す。

### FIRST と nullable

| 構文 | FIRST / nullable |
| --- | --- |
| `'w'` | `w` の最初の符号位置。`''` は nullable |
| 規則参照 | 規則表の値（下記の最小不動点） |
| token 参照 | 宣言的 token は式から計算（`'lit'`、`CHAR_RANGE`、`ANY` / `NEGATION` / XID 識別子は全体、列・選択・反復、同じ文法の宣言的 token への参照）。`CHAR_RANGE` token は範囲、`ANY` / `NEGATION` は全体、`EMPTY` は nullable。ほか（Java の parser class、adapter、`UNTIL`、`LOOKAHEAD`、`NEGATIVE_LOOKAHEAD`、`EOF`、`CI`）は unknown |
| `( … )` | 本体と同じ |
| `[ … ]` `{ … }` | 本体に nullable を足す |
| `e+` `e{n,m}` | `e`。`n = 0` なら nullable |
| `e % sep` | `e` |
| 列 | 先頭から nullable の間だけ和を取り、nullable は全要素が nullable のとき |
| 選択 | 和 |
| `COMMIT` `LABEL` `CATCH` `CAPTURE` `CAPTURE_SCOPE` `SAME_AS` `ERROR` | unknown（runtime の FIRST 集合による候補除外と同じ扱い） |

`@whitespace: javaStyle` の文法では、上の表で求めた FIRST が空白・コメントの開始（下の T₀）と交わる
終端記号と token 参照を unknown にする（下の「空白のある文法」）。

規則表は全規則を「空集合・nullable でない」から始め、変化が無くなるまで本体を計算し直す
（最小不動点）。名前空間付きの参照、規則と token の名前が重なる参照は unknown。

### 文法全体の前提

次のどれかに当たる文法は解析しない（lint は空、挿入しない）。

- 設定が `@package` `@ubnf` `@memoSafeToken` `@feature` `@whitespace: none` `@whitespace: javaStyle`
  以外を含む。`@comment`（Java と Rust の意味は #712 で揃ったが、解析への組み込みは #745）、
  宣言的 token を空白にする `@whitespace: 名前`、トークン列の文法（`@tokenStream`）、埋め込み、token adapter / contract は
  トークン境界の位置を変えるので対象外。javaStyle の綴りは、両端の ASCII 空白（空白・tab・改行・
  `\v`・`\f`）を除いて ASCII の大文字小文字を区別せずに比べる。両 generator はこれより広く trim し、
  大文字小文字を区別せずに比べるので、ここで javaStyle と判定した文法は両 host とも javaStyle で生成する。
- 規則に `@whitespace` `@interleave` `@lexicalContext`（文脈依存の字句）が付いている。
- `@root` がちょうど 1 個でない。
- 左再帰がある（消費せずに自分を呼べる規則がある）。この検査は書いたとおりの入力に対する FIRST で行う。
  javaStyle の文法で unknown にする `'/'`・`ANY`・XID 識別子なども、成功すれば必ず消費するので
  「消費する」と扱う（下の「空白のある文法」の読み替えは使わない）。左再帰は parser が同じ位置で同じ
  規則を呼ぶかどうかの性質で、generator の `E-LEFT-RECURSION`（[left-recursion.md](left-recursion.md)）
  も同じ意味である。

### 不透明な規則

注釈が `@root` `@mapping` `@doc` `@skip` `@autoCommit` 以外を含む規則は不透明とする。FIRST は
unknown、中には挿入せず、文脈の検査がその規則の本体に達したら「挿入しない」にする。
`@recovery`（確定エラーも回復するので受理が変わり得る）、`@longestChoice` /
`@uniqueLongestChoice`（内部で最長一致を選ぶ）、`@predictiveChoice`、名前述語（意味述語）、
`@backref`、`@leftAssoc` / `@rightAssoc` / `@precedence`、`@eval`、`@enum` などがこれに当たる。

### 候補

root から参照でたどれる不透明でない規則の、`COMMIT` / `LABEL` / `CATCH` / `CAPTURE` /
`CAPTURE_SCOPE` の外にある選択肢 `A_i = e1 e2 … en` で、

1. n ≥ 2、
2. `FIRST(e1)` が unknown でなく、`e1` が nullable でない、
3. `e2 … en` に包める参照が 1 個以上ある、
4. 下の文脈の検査を通る（初期状態は「`A_i` が、`FIRST(e1)` の符号位置で通常失敗した」）。

### 文脈の検査

状態は「節点 X が、文字集合 S のどれかがある位置から始めて通常失敗した（FAIL）」か
「X が成功し、次の符号位置が S のどれかである（NEXT）」の 2 種類である。到達できる状態を
すべて調べ、次の表の「不可」に 1 つでも達すれば挿入しない。状態が 100000 個を超えても挿入しない。
状態の集合は調べる順序によらないので、両 host は同じ判定になる。

| 状態 | 囲む構文 | 遷移 |
| --- | --- | --- |
| FAIL(選択肢 i, S) | 選択 | 後ろの選択肢がすべて「unknown でなく、nullable でなく、FIRST が S と交わらない」でなければ不可。そうなら FAIL(選択, S) |
| NEXT(選択肢, S) | 選択 | NEXT(選択, S) |
| FAIL(要素 k, S) | 列 | 前置 `e1 … e(k-1)` の FIRST を P として FAIL(列, P ∪ (P が nullable なら S)) |
| NEXT(要素 k, S) | 列 | 後続の要素を順に見て、unknown か FIRST が S と交わるものがあれば不可。最初の nullable でない要素で止める。後続があれば FAIL(列, `e1 … ek` の FIRST ∪ (nullable なら S))。後続がすべて nullable（または無い）なら NEXT(列, S) も |
| 本体 | `( … )` | 同じ種類で group へ |
| 本体 | `[ … ]` | NEXT(optional, S) |
| FAIL(本体) | `{ … }` | NEXT(repeat, S) |
| NEXT(本体) | `{ … }` | 本体が S で必ず失敗する（unknown でなく nullable でなく交わらない）でなければ不可。そうなら NEXT(repeat, S) |
| FAIL(要素) | `e+` `e{n,m}` | NEXT(反復, S)。下限が 1 以上なら FAIL(反復, FIRST(e) ∪ S) も |
| NEXT(要素) | `e+` `e{n,m}` | `e` が S で必ず失敗するのでなければ不可。NEXT(反復, S)。下限が 2 以上なら FAIL も |
| FAIL(項目) | `e % sep` | FAIL(list, S) と NEXT(list, FIRST(sep) ∪ (sep が nullable なら S)) |
| FAIL(区切り) | `e % sep` | NEXT(list, S) |
| NEXT(項目) | `e % sep` | `sep` が S で必ず失敗するのでなければ不可。NEXT(list, S) |
| NEXT(区切り) | `e % sep` | `e` が S で必ず失敗するのでなければ不可。NEXT(list, FIRST(sep) ∪ …) |
| 本体 | `COMMIT` `LABEL` `CATCH` `CAPTURE` `CAPTURE_SCOPE` | 不可 |
| 規則の本体 | 規則 | 不透明なら不可。root からたどれる規則にある参照位置すべてへ、同じ状態で |

root 規則の本体に達した状態は、それ以上たどる先が無ければそこで終わる（root の失敗は構文解析の
失敗、root が入力の途中で成功した NEXT は入力全体の解析では失敗）。

## 意味が変わらないことの根拠

PEG の順序付き選択 `A / B` は、`A` が成功すればその結果、`A` が失敗したときだけ同じ位置から
`B` を試す。FIRST は「消費して成功する一致が始まり得る符号位置」の上からの近似で、
nullable でない式が成功すれば必ず入力位置の符号位置 c を消費し、c ∈ FIRST である。

**選択肢の中だけの条件（yapp の条件）**: 選択肢 `A_i = e1 …` の `e1` が位置 p で成功したとすると
（nullable でないので c = input[p] ∈ FIRST(e1)）、後ろの選択肢 `A_j` が nullable でなく
`FIRST(A_j) ∩ FIRST(e1) = ∅` なら、`A_j` は c から始まる一致を持たず、空で成功もしないので p で
必ず失敗する。したがって `A_i` が `e1` の後で失敗した時点で、この選択全体の失敗が確定する。
そこで後ろの選択肢を試さずに止めても、**この選択の結果は変わらない**。前の選択肢はすでに
p で失敗しているので関係しない。

**それだけでは足りない理由**: yapp と Prolog の cut は直近の選択だけを止め、選択全体の失敗は
通常の失敗として外の選択が受け止める。#700 の `COMMIT` は scomb の `commit` と同じ確定エラーで、
外側の選択・optional・反復も越えて recovery 境界か最上位まで伝わる。このため、外側の構文が
同じ位置からやり直して成功する文法では、受理が変わる。

```ubnf
  Root ::= Item 'x' | 'a' 'y' ;
  Item ::= 'a' B ;        // 選択肢は 1 個だけなので、選択の中だけの条件は満たす
```

`ay` は、`Item` が `a` の後で失敗し、`Root` の 2 番目の選択肢が `a y` を受理する。
`'a' COMMIT(B)` にすると確定エラーで拒否される。`( 'a' B )+ 'a' 'c'` の `abac` も同じで、
反復を終えてから続きが同じ `a` を消費する。文脈の検査はこれらを「不可」にする（corpus の
`enclosing-choice`、`one-or-more`、実行の `not-inserted`）。

**入力全体の解析での同値性**: 挿入した `COMMIT(ek)` が働くのは、`e1 … e(k-1)` が成功した後に
`ek` が通常失敗したときだけで、そのとき `A_i` は FIRST(e1) の符号位置で通常失敗している。文脈の
検査の各遷移は、その失敗の後に構文解析が再開し得る場所（後ろの選択肢、optional・反復の終わり、
separated list の終わり、列の続き、規則の参照位置）を、PEG の意味どおりにすべて列挙している。
したがって検査を通れば、元の文法で「`A_i` がその位置で失敗した」ことが起きた構文解析は、
入力全体の解析として必ず失敗する。

- 元の文法で受理される入力では、この事象は一度も起きない。挿入した `COMMIT` の子は一度も
  通常失敗しない。`COMMIT` は成功を変えず、CST・capture・状態・memo にも何も足さない
  （#700 の契約）ので、構文解析は元と 1 段ずつ同じに進み、同じ結果で受理される。
- 元の文法で拒否される入力では、最初に `COMMIT` の子が通常失敗するまでは元と同じに進む。
  そこで確定エラーになり、通る経路の構文（検査で列挙した祖先）には recovery 規則も
  `CATCH` / `LABEL` も無い（どれも「不可」）ので、確定エラーのまま最上位に達して拒否される。
  `COMMIT` の子が一度も失敗しなければ元と同じに拒否される。

複数の `COMMIT` を同時に挿入しても同じである。どの `COMMIT` についても、根拠は元の文法の
構文解析だけを使っている。

前提は**入力全体の解析**（Java の `diagnose` / `parse`、Rust の `parse_tree*`、生成された LSP）
である。Java の `getRootParser().parse(context)` や Rust の `parse_context` のように入力の途中で
止まってよい解析では、root が入力の途中で成功する経路（root での NEXT）が残るので、結果が
変わり得る。別の文法から shared grammar call（Java `SharedGrammarCalls`、Rust `shared_calls`）で
この文法を呼ぶ場合も、呼び出しは入力の途中で止まるので対象外である。

## 空白のある文法（#728）

### 空白が入る位置

`@whitespace: javaStyle` の文法では、生成された parser が列の境界で空白とコメント（以下 trivia、
T と書く）を読み飛ばす。どこに T が入るかは、Java の ParserGenerator、Rust の native generator、
Java host の `RustBackend` の生成物で確かめた。3 つとも同じ形である。

| 構文 | Java | Rust |
| --- | --- | --- |
| 本体が列 1 個の規則（要素 1 個でも） | `{G}LazyChain`：`T e1 T e2 T … en T` | `Expr::Sequence`：同じ |
| 要素 2 個以上の選択肢、本体が列 1 個の group `( … )`（要素 1 個でも） | 匿名の `{G}LazyChain` / group helper：同じ | `Expr::Sequence`：同じ |
| `e % sep` | `elem` と `ZeroOrMore(sep elem)` の 2 つの chain | 同じ |
| 要素 1 個の選択肢、`[ ]` `{ }` `+` の本体が要素 1 個のとき | 素のまま（T なし） | 素のまま |
| 選択肢が 2 個以上の規則の本体 | `LazyChoice`（T なし） | `Expr::Choice`（T なし） |

T は javaStyle の trivia で、`(空白 | // … 行末 | /* … */)*` である。空白は Java の `SpaceParser` と
Rust の runtime の `skip` で同じ 6 文字（` ` `\t` `\n` `\v` `\f` `\r`）。閉じていない `/*` は trivia
ではない。`COMMIT(ek)` は chain の 1 要素なので、T は `COMMIT` の外（前と後）に入り、`COMMIT` の子は
T の後から始まる。確定エラーの位置は子が始まった位置、つまり空白を読み飛ばした後である（corpus の
`whitespace-statements`：`let x` は 4、`let /* c */ x` は 12）。

### 空白を読み飛ばした後の位置で比べる

位置 p から T を当てた終わりを N(p) と書く。根拠に使う T の性質は次の 3 つだけである。

1. T は必ず成功し、同じ位置からは同じ終わりになる（文脈に依らない）。
2. N(N(p)) = N(p)。T は繰り返せるだけ繰り返すので、終わった位置ではもう何も読まない。
3. p の符号位置が T₀ = {空白 6 文字, `/`} に入らなければ N(p) = p。

FIRST と文脈の検査の文字集合は、**N(p) の符号位置**（空白を読み飛ばした後の最初の符号位置）を表すと
読み替える。nullable は「N(q) = N(p) で成功し得る」（空白しか読まずに成功し得る）と読み替える。
すると、次の命題が上の表の規則のままで成り立つ。

> 構文 e を位置 p で試し、q で成功し、N(q) ≠ N(p) なら、N(p) の符号位置は FIRST(e) に入る。
> e が nullable でなければ、N(q) = N(p) で成功することはない。

- 終端記号・token（FIRST が T₀ と交わらないもの）：成功すれば p の符号位置は FIRST に入り T₀ の外
  なので、性質 3 から N(p) = p。消費すれば N(q) ≥ q > p = N(p)。
- T：N(q) = N(N(p)) = N(p) で成功するので、nullable で FIRST は空と同じ。列の FIRST の規則
  （nullable の間だけ和を取る）では T は消える。
- 列・選択・反復・optional・separated list：上の 2 つから帰納法で、#702 と同じ合成になる。

この議論は **T が列のどの境界に入っても入らなくても**成り立つ。上の表の T の位置（Java と Rust で
同じ）には依らないので、片方の generator が T の位置を変えても根拠は崩れない。

FIRST が T₀ と交わる終端記号・token は unknown にする。素の終端記号（要素 1 個の選択肢など）は T の後
とは限らない位置で試されるので、次のことが起きる。

- `'/'` はコメントの最初の `/` を読める。その後の N(p) の符号位置はコメントの後ろの何でもあり得る。
- `' '` などの空白の終端記号は、T が読み飛ばすはずの空白を 1 文字だけ読み、N(q) = N(p) で成功する。
- `ANY` `NEGATION` の token も空白や `/` を読める（#702 でも全体を FIRST にしていたので判定は変わらない）。

`spec-corpus/auto-commit/cases.json` の `whitespace-raw-slash` はこの反例である。

```ubnf
  Root ::= { Item } ;
  Item ::= DIGIT | 'k' N | '/' | '*' '*' '/' 'k' 'x' ;
```

`1/**/kx` は、`DIGIT` が `1` を読んだ後の位置 1 で、`'k' N` の T がコメント `/**/` を読み飛ばして
`k` の後で失敗し、続く `'/'` が位置 1 の `/` を読んで、`* * / k x` で受理される。`'/'` を FIRST `{/}`
として扱うと `'k' COMMIT(N)` を挿入してしまい、`x` で確定エラーになる。`'/'` を unknown にすると
`'k' N` は候補にならない。

### 候補・文脈の検査・空の入力と末尾の空白

候補の初期状態は #702 と同じ「`A_i` が FIRST(e1) の符号位置で通常失敗した」である。`A_i` を p で
試し、`e1` が p₁（T の後なら N(p)、素なら p）から消費して成功したので、命題から N(p₁) = N(p) の
符号位置は FIRST(e1) に入る。遷移の表もそのまま使える。各遷移の「次に試す構文はその符号位置で必ず
失敗する」は、試す位置が T の前でも後でも、上の命題（N(p) の符号位置で判定してよい）で成り立つ。

- **空の入力・空白だけの入力**：`COMMIT(ek)` の子が試されるのは、nullable でない `e1` が成功した後
  だけである。命題から、そのとき N(p) には trivia でない符号位置がある。空の入力や trivia だけの入力
  では N(p) は常に入力の終わりなので、どの `COMMIT` の子も試されず、結果は元の文法と同じである
  （corpus の `""`、`"   "`、`"// only a comment"`）。状態の集合も常に「N(p) に符号位置がある」
  ことを表す。
- **末尾の空白**：root での NEXT は「root が成功した後、N(q) に trivia でない符号位置が残る」ことを
  表す。入力全体の解析は、残りを trivia として読み飛ばすかどうかに関係なく、その符号位置で失敗する。
  入力の終わりの trivia だけが残る場合は N(q) が入力の終わりなので、この状態にはならない
  （`let n = v ;  ` は受理、`let   ` は位置 6 の確定エラー）。

### まだ対象にしないもの

- `@comment`：読み飛ばす範囲は #712 で Java と Rust の両生成経路で揃った（[trivia 契約](rule-trivia.md#global-comment712)）。
  ただし T₀ と命題への組み込みはまだで、`W-AUTO-COMMIT-SKIPPED`（reason=grammar）のまま。#745 で扱う。
- `@whitespace: 名前`（宣言的 token の空白）：T₀ を token ごとに求める必要がある。`@whitespace`
  の値として組み込みの javaStyle だけを対象にした。
- 規則の `@whitespace` / `@interleave`、`@lexicalContext`、`@tokenStream`、埋め込み、token adapter /
  contract：これまでどおり対象外。

## runtime の FirstSets（#292）を流用しない理由

runtime の `FirstSets`（Java #292、Rust #300 の `first.rs`）は、生成された parser の combinator
graph（Java の `Parser`、Rust の `Expr`）の上で、候補を飛ばしてよいかを判定する。今回の解析は
frontend で UBNF の構文木に対して行うので、次の理由で計算そのものは流用しない。

1. 生成前の frontend には combinator graph が無い。Java の `FirstSet` は unlaxer-common に
   あるが、計算は `Parser` の木をたどる。Rust の `FirstSet` は `unlaxer-runtime` の crate 内部で、
   `unlaxer-ubnf` は依存を持たない（Java の frontend 比較テストが `rustc` 単体でこの crate を
   compile する）。
2. lint は原文の位置を返す。必要なのは構文木の節点ごとの FIRST と、親・兄弟・参照位置を
   たどる文脈の検査で、runtime の表は規則ごとの値しか持たない。
3. runtime は空白（trivia）を呼び出し側の方針で動的に解く。frontend は文法全体の javaStyle だけを
   扱い、FIRST を「空白を読み飛ばした後の符号位置」と読み替える（上の「空白のある文法」）。規則ごとの
   空白は対象外にした方が単純で安全である。

表現（ASCII は正確、非 ASCII は 1 bit）と、模型化しない構文を unknown にして判定から外す方針は
runtime と同じにした。`COMMIT` / `LABEL` / `CATCH` を unknown とする扱いも runtime と同じである。

## #710 の検査との関係

挿入した `COMMIT` は、常に 2 個以上の要素を持つ列の 1 要素で、包むのは参照 1 個だけである。
したがって「本体全体が `COMMIT` か `LABEL` だけの無界の反復」（`E-RAISING-REPETITION`）には
ならない。#722 で検査が参照をたどるようになったあとも、規則が「通常の失敗をしない」と
みなされるのは、本体が 1 要素だけか、先頭が常に raise する要素の場合だけなので、挿入した
`COMMIT` を含む規則はこれに当たらない。テストは書き換えた文法すべてに
`RaisingOperators.problems` を当て、空であることを確かめる。

## 検証

[`spec-corpus/auto-commit`](../spec-corpus/auto-commit) を両 host が読む。期待値は手書きである。

- `lint.json`: 33 文法、31 件の報告。報告する位置（選択肢と `e1` の原文、包む参照）と、
  報告しない例（重なる FIRST、外側の選択・optional・`+`・separated list での再開、
  `COMMIT` / `LABEL` / `CATCH` / `CAPTURE`、不透明な規則、到達できない規則、`@recovery`、
  模型化しない token、左再帰、root 2 個、非 ASCII の 1 bit）。javaStyle の文法（#728）は
  `whitespace`、`whitespace-case`（綴りの大文字小文字）、`whitespace-trivia-start`（`'/'`・`' '`・
  `ANY` が unknown になる）と、同じ選択肢を空白なしで解析する `whitespace-trivia-start-none`、
  左再帰の検査で `'/'`・`ANY`・XID 識別子を消費するものとして扱う `whitespace-left-recursion-check`、
  本当の左再帰がある `whitespace-left-recursion`、まだ対象外の `rule-whitespace`、`comment`、
  `declarative-whitespace`、javaStyle と規則の `@whitespace(none)` / `@interleave` / `@lexicalContext`
  の組み合わせ（`whitespace-rule-none`、`whitespace-interleave`、`whitespace-lexical-context`）。
- `cases.json`: 10 文法 108 入力。自動挿入の文法、同じ位置に手で `COMMIT` を書いた文法、
  それぞれの入力の結果（受理・拒否、確定エラーの位置と message）。`group-after-commit` は
  包んだ参照の後ろに group が続き、Java の group helper の番号が変わらないことを CST で確かめる。
  javaStyle の 4 文法（`whitespace-statements`、`whitespace-expression`、`whitespace-slash`、
  `whitespace-raw-slash`）は、空白・tab・CRLF・行コメント・ブロックコメント・閉じていないコメント・
  単独の `/`・末尾の空白・空白だけの入力を含む。
- `warnings.json`: 11 文法。`W-AUTO-COMMIT-SKIPPED` の規則・reason・位置（`grammar` 6 例（`@comment`、
  宣言的 token の空白、javaStyle と規則の `@whitespace(none)` / `@interleave` / `@lexicalContext`、
  root 2 個）、`opaque`、`unreachable`、警告しない例 3 つ（javaStyle の 2 つを含む））。

`AutoCommitConformanceTest`（Java）は次を確かめる。

- lint の位置と警告が corpus と一致する。`findings` は書き換え前の構文木で `lint` と一致する。
- 書き換えた構文木が手書きの構文木と等しく（`parse` と `parseWithSource`）、Java の Parser /
  AST / Mapper / LSP 生成物と Java host の Rust 生成物が byte 単位で一致する。
  書き換えた文法に `RaisingOperators` の問題が無い。注釈の無い文法は同じ instance のまま。
- 新しく作った節点の位置と origin（`SYNTHETIC`）、capture の位置。
- Java の生成 parser を memo 2 × diagnostics 3 の 6 設定で実行し、自動挿入・手書きの結果が
  oracle と一致する。注釈を外した文法は同じ入力を受理し、受理した入力の CST
  （`TokenPrinter`）が一致する。
- `-DrustConformance=true` のとき、native generator と Java host の Rust 生成物が byte 単位で
  一致し、手書きの文法の生成物とも一致する。両 host の生成物を 6 設定で実行し、同じ比較を
  CST（`Tree` の節点列）まで行う。

`AutoCommitWhitespaceFuzzTest`（Java、#728）は差分 fuzz である。property-based の `GrammarGenerator`
（#705）の文法に `@whitespace: javaStyle` を付け、全規則に `@autoCommit` を付けた文法と付けない文法を
比べる。`COMMIT` が 1 個以上入った文法だけを使う。生成器の literal は trivia を始めないので、3 分の 2 の
seed では `y` を `/`、`,` を `*`、`x` を空白に置き換える（文法と入力の両方）。入力は生成器の導出と変異に、
空白・改行・CRLF・コメント・単独の `/`・`/*`・`*/` を符号位置の間に差し込んだものを加える。

- Java の生成 parser で、6 設定のすべての入力について、受理・拒否が一致し、受理した入力の CST と
  AST が一致する。
- `-DrustConformance=true` のとき、native generator と Java host の Rust 生成物が byte 単位で一致し、
  native Rust の parser で同じ比較を行う。両 frontend の lint と警告（位置・件数・inserted）も
  全文法で比べる。
- 既定（CI）は 24 文法。大きく回すときは `-Dacfuzz.seed=1000 -Dacfuzz.grammars=200 -Dacfuzz.variants=6`。

`rust/unlaxer-ubnf/tests/auto_commit.rs` は同じ `lint.json` と `warnings.json`、書き換えた構文木と手書きの構文木の
canonical 比較を native frontend で確かめる。`rust/unlaxer-ubnf/tests/fixtures/positive/auto-commit.ubnf`
は既存の frontend 比較（`RustUbnfFrontendConformanceTest` の構文木、`RustUbnfSourceConformanceTest`
の位置）にも入る。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=AutoCommitConformanceTest,AutoCommitWhitespaceFuzzTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
RUSTUP_TOOLCHAIN=1.85.0 cargo test --manifest-path rust/Cargo.toml -p unlaxer-ubnf --test auto_commit
```

## 制限事項

- 空白を自動で挟む文法のうち、解析するのは文法全体の `@whitespace: javaStyle` だけである。`@comment`
  （#745）、宣言的 token の空白（`@whitespace: 名前`）、規則の `@whitespace` /
  `@interleave` は解析しない。
- javaStyle の文法では、空白か `/` で始まり得る終端記号・token（`'/'`、`' '`、`ANY`、`NEGATION`、
  XID 識別子（`XID_IDENTIFIER`。FIRST を全体で近似しているため）、空白を含む `CHAR_RANGE` など）は
  unknown になる。割り算の `'/'` のような演算子があると、その位置を
  通る文脈の検査は「不可」になり、挿入されない（corpus の `whitespace-slash`）。`'/='` のように
  2 文字目が `/` でも `*` でもない終端記号はコメントの開始と区別できるが、まだ区別していない。
- 包むのは規則参照と token 参照だけで、終端記号は包まない（Java の CST が変わるため）。
- 確定エラーの message は既定の `unexpected input` だけである。
- 同値性は root 規則から始める入力全体の解析についてだけ保証する。途中で止まってよい解析、
  別の文法からの shared grammar call、root 以外の規則（Java の各規則の parser class など）を入口に
  する解析は対象外。
- 旧 API の `UBNFMapper.parseWithImports` は、取り込まれる文法も 1 個ずつ `UBNFMapper.parse` で
  読んでから規則を合わせる。したがって取り込まれる文法の `@autoCommit` は、**その文法自身の `@root`** を入口とした解析で
  判定・挿入される。取り込む側から見た参照の文脈は考慮しない。
- 非 ASCII の符号位置は 1 bit にまとめるので、非 ASCII で始まる選択肢どうしは常に重なると扱う。
- 判定は保守的で、意味を変えない位置でも報告しないことがある（例: corpus の
  `separated-trailing`）。
- 解析の対象外の文法（`@comment`・宣言的 token の空白などのある文法）、不透明な規則、到達できない規則では、frontend は注釈を
  警告なしに取り除く。警告 `W-AUTO-COMMIT-SKIPPED` は lint の API（`lintWarnings` /
  `lint_warnings`）から得る。解析できたが候補が無い規則は警告しない。
- lint（すべての規則を調べる）は、候補ごとに文脈を root までたどるので、大きな文法では規則数の
  2 乗に近い時間がかかり得る。frontend の書き換えは注釈を付けた規則だけを調べる。
- lint の CLI は無い。Java / Rust の API から呼ぶ。
- `COMMIT` を含む文法は #700 のとおり snapshot 全体の incremental cache の対象外なので、
  `@autoCommit` で `COMMIT` が入った文法も対象外になる。
