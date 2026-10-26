# Java の安全な memoization

Java 生成 parser は、曖昧な選択肢が同じ `(rule, position)` を再試行する際の指数的な
バックトラックを抑えるため、parse session 単位の memoization を提供する。
既定値は従来どおり `OFF` であり、明示的に有効化した session だけが対象になる。

```java
ParseOptions options = ParseOptions.withMemoization(Memoization.SAFE_FAILURES);
try (ParseContext context = ParseContext.withOptions(source, options)) {
    Parsed parsed = GeneratedParsers.getRootParser().parse(context);
}

// 生成 Mapper も同じ option を受け取る。
Ast.Root root = GeneratedMapper.parseWithOptions(text, options);
```

## 契約

- `ParseOptions` は immutable。`ParseOptions.DEFAULT` と既存 Mapper overload は `OFF`。
- `SAFE_FAILURES` は安全な失敗に加え、`SafeSuccessMemoizable` を直接 implements する exact class の
  安全な成功も自動で保存・再生する。enum 名・既定値は変えず、新しい enum 値は追加しない。
- キーは parser identity、consumed/matched position、`TokenKind`、invert flag、
  parser-visible state version、matched cursor reset policy (`resetMatchedWithConsumed`)。
  [policy切替時のkeyと互換constructor](memo-reset-policy.md)も参照。
- failure/success hit は最初の rule call が生成した診断を再生するため、OFF/ON で farthest offset と
  expected hints が変わらない。
- cache は 1 個の `ParseContext` に閉じ、session をまたいで共有しない。
- parser graph（rule instance、その children、parse 結果へ影響する設定）は session 中 immutable
  であることを前提とする。graph を変更する可能性がある callback/state facility は下記の
  fail-closed 条件に従う。

診断 cache は global snapshot ではなく、対象 rule の開始時にだけ作る local accumulator を
保存する。cursor、failure、expected hint の更新は入れ子になった全 accumulator に伝播し、
失敗時と安全な成功時に保存する。成功した rule 内の失敗も診断に寄与するため、success entry の
frame は捨てない。hit 時は global 診断と外側の active accumulator の双方へ
merge するため、先行 sibling の farther failure を誤って cache しない。成功した negative
lookahead 内の診断も speculation 境界で破棄される。stack は rule 開始深度以降の suffix だけを
保存し、hit 時の現在 stack へ rebase するので、以前の呼出元を診断へ復活させない。`OFF` と
unsafe rule には frame overhead がない。

次の session 状態では、既存 entry を含め lookup/store を停止し、通常の parser lifecycle を
必ず実行する。状態が後から追加・切替された場合も同じである。

- `ParserListener`、または通常の `TransactionListener` が 1 個以上登録されている
- persistent commit action が 1 個以上登録されている
- trial recording が有効である

これにより callback、action、trial record が cache hit によって欠落しない。
これらの要因を一度でも追加または有効化した session は、その後 remove/clear/stop されても
memoization を再開しない。unsafe 中の parser 変化より前の stale entry が復活するのを防ぐ。

純粋な観測だけを行い、memo hit で省略された処理の callback が呼ばれなくてもよい listener は
`ParseContext.addMemoizationTransparentTransactionListener(...)` で登録できる。典型例は
wall-clock deadline の監視で、
cache hit 自体は監視対象の parser work を行わない。状態、token、diagnostic、application data を変更する
listener にこの marker を付けてはならない。

`TransactionalState` は例外であり、登録されていても安全規則の failure memoization を継続する。
entry は対象 safe parser 自身の transaction begin/commit/rollback 列を保存し、hit 時に
token/cursor を変更せず state の checkpoint/restore hook だけを同じ順序で再生する。子 parser の
本体と lifecycle は cache hit では実行されない。これは listener callback ではなく、memoized parser
呼出しの rollback 境界で owner の状態を保つための再生である。したがって `@scopeTree` のように
context 全体へ state owner を登録する機能があっても memoization を継続できる。

parse の結果に影響し得る `ScopeStore` mutation（scope の enter / leave、`declare`、diagnostics の clear）は
`ParseContext.markMemoizationStateChanged()` で単調な epoch を払い出す。`addReference` と `addDiagnostic` は
払い出さない: 参照と semantic diagnostic は parse 中には読まれず（LSP や evaluator が parse 後に読む）、記録しても
memoized rule の結果は変わらないため。以前は `$var` の参照ごとに version が進み、以降の parse が memo table に
一切 hit しなくなっていた（#269。tinyexpression の 5 段ネスト if 式で 4〜6 秒 → 約 0.1 秒）。
transaction は開始時の current version を保存し、rollback 時に state と version をともに復元する。
払い出し元の epoch は rollback しないため、別の backtracking branch が異なる state に同じ version を
再利用することはない。これにより `@scopeTree`、`@declares`、`@backref` の結果を version 付き key で
区別できる。任意 user state はこの契約の対象ではない。

## 安全規則の生成時解析

生成器は grammar の rule dependency graph を推移的に解析する。unsafe leaf だけでなく、
そこへ到達可能な全 ancestor を対象外にする。以下は fail-closed で unsafe となる。

- custom/unknown token parser（`token X = SomeParser`）
- import namespace 経由など、生成器が純粋性を証明できない参照

`@scopeTree`、`@declares`、`@backref` は生成 runtime が所有する versioned `ScopeStore` だけを使うため
失敗 memo の安全対象になる。これらの listener rule とそこへ到達可能な rule は成功を cache しないため、
commit 時の宣言・参照・semantic diagnostic は常に実行され、
失敗は transaction rollback 後の version で保存される。

状態を参照せず、失敗時に `ParseContext` への observable な副作用を残さない custom token
parser は、token alias ごとに明示的に許可できる。同じ設定を複数行書ける。

```ubnf
@memoSafeToken: IDENTIFIER
@memoSafeToken: NUMBER
token IDENTIFIER = example.IdentifierParser
token NUMBER = example.NumberParser
```

対象は `TokenDecl.Simple` の alias だけであり、設定の重複、未定義 alias、block 値、built-in
token 宣言への指定は validator error になる。この宣言は parser 実装の安全性を利用者が保証する
契約であり、設定した leaf を参照する rule と、安全な依存だけを持つ ancestor が memo 対象になる。
成功 memo も許可するため、この指定は成功時にも状態の読み書き・listener・独自 Token subclass を
持たず、通常の `Token.deepCopy()` で再現可能であることを保証する。`MatchedTokenParser` や任意の
user state に依存する token には指定できない。
未指定の custom token は従来どおり fail-closed で unsafe のままである。未知の global setting に
対する従来の validator 互換性は変更しない。

Rust runtime の built-in `Expr` は runtime が所有する既知の実装なので、既にfailureとpure successの
memoizationの安全対象である（詳細は[成功memoの共通契約](pure-success-memo.md)）。現在の Rust generator は任意の Java custom token class を Rust
実装へ接続する仕組みを持たないため、`@memoSafeToken` を Rust custom token の許可にはまだ使用
しない。将来 custom token 接続を追加するときは、同じ alias 単位の設定を安全性の明示契約として
尊重し、未指定 custom token とその ancestor を fail-closed で除外する。

capture、rollback、任意 user state、`MatchedTokenParser` のように結果が現在の context に依存し得る
処理は、明示的に安全と証明されない限り cache されない。scope/back-reference は versioned
`ScopeStore` を通る生成実装だけが例外である。

安全と証明された生成 class は `SafeFailureMemoizable` を直接 implements する。runtime は
`instanceof` ではなく exact class の直接 interface を検査するため、生成 class の subclass が
parse 動作を override しても安全性を暗黙に継承しない。

## 安全な成功の追加条件と再生

成功の解析は失敗の解析より厳しく、依存する rule・helper・token の推移閉包に生成
`TransactionListener`（`@scopeTree`、`@declares`、`@backref`、それらの scope capture 処理）が
あれば対象外にする。未指定の custom/unknown token と未知の参照も対象外であり、判定不能は
安全側に倒す。通常の `@value` capture は immutable な token metadata のため、listener を
生成しない限り対象にできる。helper は自分の body の依存閉包で判定し、任意の child を受け取る
汎用 `__CaptureSite` 自体には marker を付けない。空白 delimitor は既知の `SpaceParser`、
`CPPComment`、`BlockComment` だけから構成されるため対象になる。

runtime は `SafeSuccessMemoizable` の直接 implements を `ClassValue` で cache する。
継承した subclass は再証明なしには対象にしない。直接 listener を実装する class も除外する。
成功と失敗は同じ version 付き `PositionKey` を使う。

保存時には commit 前の transaction element の token 列を **deep copy** し、終了 consumed / matched
位置と完了した診断 frame を保持する。commit による parent の付け替えや呼出元による token の
変更から cache を隔離する。hit 時も token を deep copy して、診断 merge → `startParse` → `begin`
→ token 挿入 → `consume` / `matchOnly` → `commit` → `endParse` の順で再生し、`new Parsed(committed)`
を返す。元の Choice の通常実行は既存の child `Parsed` を返す動作を維持する。選択された child と
内部 Choice の選択情報も保存し、通常の transaction journal を通して rollback 可能に復元する。

成功 replay では frame の `transactionEvents` による checkpoint/restore の再実行は不要である。
対象の部分木には transactional state の変更がなく、実際の `begin` / `commit` が checkpoint と
外側 memo frame の balanced trace を生成する。保存した trace も再実行すると hook が二重に走る。
失敗 replay の既存の hook 契約は変更しない。

共通 helper を `AbstractParser`、Chain / Choice / LongestChoice / PredictiveChoice から呼ぶ。
空白 delimitor の `LazyZeroOrMore` はそれらを通らないため、`Occurs` の入口も同じ helper を使う。

## 互換 API

`ParseContext.enableMemoize()` と `ParseContext.memoize()` は deprecated。互換性のため残るが、
`SAFE_FAILURES` と同じ安全規則限定の動作になる。旧 API でも、安全証明なしに
unknown/custom/state-dependent parser を cache することはない。

## 性能上の位置づけ

failure memoization は、複数の選択肢が同じ内側部分木を最後まで調べて失敗する文法で特に
有効になる。成功 memoization は純粋な成功部分木の再導出を抑える。entry 数は概ね
「安全 rule 数 × position / state version 数」で、成功 entry には token snapshot 分のメモリも必要になる。
listener を含む部分木の指数的な再導出までは省略しない。Rustの対応範囲・保存予算・未完境界は
[pure success memo](pure-success-memo.md)を参照。

## CAPTURE / SAME_AS を含む相互再帰の計算量（#756）

memo なしの PEG は、同じ位置から同じ rule を繰り返し試す文法では、入力長に対して
最悪で指数時間になる。Java の `ParseOptions.DEFAULT` と Rust の `ParseOptions::default()` は
ともに memo OFF である。`SAFE_FAILURES` / `Memoization::SafeFailures` も、安全と証明された
部分だけを memo する方針なので、任意の文法に線形時間を保証するものではない。

一般の owned binding を作る `CAPTURE(w, ...)` と、その値を読み直す `SAME_AS(w)` は、
AST field の `@field` capture とは別である。`SAME_AS(w)` の成否は呼出し元が作った束縛にも
依存し得るため、同じ rule・入力位置だけで失敗を再利用できない。現在の安全性解析は
`CAPTURE` / `SAME_AS` / `CAPTURE_SCOPE` を含む rule と、それらに到達するすべての rule を
Java / Rust とも保守的に memo 対象外にする。同じ rule 内に先行する `CAPTURE` があっても、
すべての再帰・分岐で外の束縛に依存しないことまでは証明しない。
state version が key にあるだけで、この安全性の証明が済むわけではない
（[stateful failure memo](stateful-failure-memo.md)）。安全な子 parser の memo は利用できても、
対象外の再帰 rule 全体の探索は省略できない。

### 縮小例と測定

[#756](https://github.com/opaopa6969/unlaxer-parser/issues/756) の縮小例は
[`regressions.json`](../spec-corpus/property-based/regressions.json) の
`step-budget-capture-mutual-recursion` に固定している。

```ubnf
grammar Pbt {
  @package: pbt.corpus
  @root @mapping(N0)
  R0 ::= R1 ',' | R1 R1 | ',' ;
  R1 ::= CAPTURE(w0, 'x' R0) SAME_AS(w0) ;
}
```

入力を `x` の n 回繰り返しにすると、`R0` の第1・第2候補が同じ位置で `R1` を試し直す。
`R1` は `x` を消費してから `R0` に戻るため左再帰でも無限ループでもないが、失敗するまでの
探索が増幅する。両 rule が `CAPTURE` / `SAME_AS` に到達するので、SAFE_FAILURES でも
この再試行は memo で省略されない。以下はすべて拒否される入力である。

| n（入力のコードポイント数） | Java DEFAULT の transaction 数 | 出典・確認 |
|---|---:|---|
| 8 | 5,621 | #756 の値と再測定が一致 |
| 12 | 90,101 | #756 の値と再測定が一致 |
| 16 | 1,441,781 | #756 の値と再測定が一致 |
| 20 | 23,068,661 | #756 の報告値（約10秒）。今回の再測定対象外 |

再測定は 2026-10-26、revision `f70b0d3f`、JDK 21.0.9。生成した Java root parser を
新しい `ParseContext` と `ParseOptions.DEFAULT` で1回呼び、
`addMemoizationTransparentTransactionListener` の `onBegin` を数えた。
これは [PBT harness](../unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/propertybased/ParityHarness.java)
の `JavaApi.withinBudget` と同じ数え方で、生成・compile・Mapper の再解析は含めない。
4文字増えるごとに約16倍になる。時間は機械・JIT・負荷に依存し、transaction 数も
生成器・runtime の revision に依存する観測値で、公開 API の保証値ではない。
Rust でも同じ形の増加が確認されている
（[#739 の調査記録](https://github.com/opaopa6969/unlaxer-parser/issues/739#issuecomment-6370178979)）。
上表の transaction 数は Java の値であり、Rust と同じ手数だと主張するものではない。

### 利用者ができる対処

- 文法上可能なら、`CAPTURE` / `SAME_AS` を相互再帰の外へ移し、再帰部分を状態非依存にする。
  その後で SAFE_FAILURES を使えば安全な rule が memo 対象になる。ただし capture の範囲・
  束縛の寿命・replay の意味を保つ必要があり、単に別 rule に切り出すだけでは到達関係は変わらない。
- 共通の先頭部分を左因数分解し、同じ位置で同じ再帰 rule を試す回数を減らす。下記はこの方法の例。
- 他の候補に戻っても成功しないと確定した位置では、[COMMIT による cut](cut-and-labeled-failures.md)
  を検討する。`COMMIT(child)` は child の通常失敗を確定エラーに変えて後続候補を止めるため、
  置き方によって受理する入力や診断が変わる。[FIRST 集合による解析の条件](auto-commit.md)も確認し、
  受理・拒否の共通 fixture で確かめる。この縮小例への未検証の cut 挿入は推奨しない。

上の縮小例を左因数分解すると、先頭の `R1` を一度だけ試す形になる。

```ubnf
grammar Pbt {
  @package: pbt.corpus
  @root @mapping(N0)
  R0 ::= R1 (',' | R1) | ',' ;
  R1 ::= CAPTURE(w0, 'x' R0) SAME_AS(w0) ;
}
```

両方の文法から Java parser と Rust parser を実際に生成・compile して、次を確認した
（同 revision、Rust 1.85.0。各 probe process に120秒の timeout）。

- `x` / `,` の長さ0〜8の全511入力、回帰 corpus の32文字以外の入力、`x` の12・16回繰り返し、
  入れ子の成功入力 `xx,x,,xx,x,,,`、異なる文字・補助文字・空白を含む入力を合わせた520入力で、
  全入力の受理・拒否が書き換え前後、Java / Rust、memo OFF / SAFE_FAILURES で一致した。
  受理は4入力。Java は生成 Mapper の `diagnose`、Rust は生成 parser の
  `parse_tree_detailed_with_options` を使った。両 host が出す Rust 全5ファイルも文法ごとに byte 一致した。
- 書き換え後の Java DEFAULT は `x` の8・12・16回繰り返しで54・78・102 transaction、
  32回でも198 transaction で拒否した。Rust でも書き換え後の32文字を両 memo 方針で拒否して終了した。
  書き換え前の32文字は無制限には実行していない。

これは上記入力での受理の確認で、任意の stateful 文法への同値変換の証明ではない。
書き換えによる CST の形、AST field の収集、診断候補・rule stack まで同じとは限らない。
利用する文法の capture・mapping・診断の契約も別途検証する。

信頼できない入力には、解析前にアプリケーション側で入力長の上限を設け、文法と最悪ケースに
合わせて値を決める。既存の深さ上限も併用する。Java は
[`ULX-PARSE-DEPTH-LIMIT`](java-parser-limits.md) の `ParseLimitException` を受け取り、
Rust は [`resource_limit`](cut-and-labeled-failures.md#rule-cycles-through-catch) の診断を扱う。
これらは同時に入れ子になる parser / rule の深さを制限するもので、累積の手数や実時間の上限ではない。
深さが上限に達しないまま指数的に再試行する上の入力には、深さ上限だけでは対処できない。

[#739](https://github.com/opaopa6969/unlaxer-parser/issues/739) /
[PR #757](https://github.com/opaopa6969/unlaxer-parser/pull/757) の
[PBT harness の手数上限](property-based-testing.md#入力ごとの手数の上限739)は、既定100,000
transaction（`-Dpbt.stepBudget`）を超えた入力を**両 host の観測から除外して記録する試験用の予算**である。
回帰例の `x` 32個は `overBudget` に登録されている。除外は parser が拒否したことでも
Java / Rust の結果が一致したことでもなく、製品の `ParseOptions` に同じ手数上限があるわけでもない。

### 今回の判断と将来の候補

#756 は選択肢3（仕様上の計算量と対処法の文書化）を採る。既定値・runtime・公開 API を
変更せず、利用者が現在使える対処を示す、最小で取り消し可能な変更である。
次の2案は [#197 の性能作業凍結](https://github.com/opaopa6969/unlaxer-parser/issues/197#issuecomment-6111834921)
の解除後に判断する候補として残し、今回の完了に含めない。

1. capture を考慮した失敗 memo。外の束縛に依存しないことを再帰も含めて証明し、Java / Rust
   共通 fixture で受理・CST・診断の不変を確認する必要がある。既定の memo OFF はこれだけでは速くならない。
2. opt-in の手数上限。両言語の `ParseOptions` に新しい公開契約を追加し、上限超過を安定した code の
   `resource_limit` として sticky に扱う案。現行の harness 予算とは別の機能追加なので、
   凍結中に導入せず #756 に将来候補として残す。

関連: #40, #184, #192, #194, #269, #739, #756
