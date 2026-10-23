# 文法と入力のランダム生成による property-based 差分テスト

[#705](https://github.com/opaopa6969/unlaxer-parser/issues/705) の検証手段。人が書いた
corpus（`spec-corpus/`、`conformance/`）は人が思いつく組み合わせしか覆わないため、seed から
決定的に文法と入力を生成し、Java と native Rust の一致、設定間の一致、契約上の不変条件を
機械的に確かめる。証明ではなく、docs に書いた契約を実行して確かめるための道具である。

実装は `unlaxer-dsl/src/test/java/org/unlaxer/dsl/codegen/propertybased/`。新しい依存は無い
（JUnit 4、Gson、既存の `RustBackend`・`unlaxer generate`・`rustc` だけを使う）。

## 何を比べるか

1 つの文法・入力について、次の 12 設定それぞれで観測値を作る。

- memo: `OFF` / `SAFE_FAILURES`（失敗 memo と、静的に pure な rule の成功 memo の両方。
  成功 memo だけの独立した切替えは無い。[pure-success-memo.md](pure-success-memo.md)）
- 診断の詳細度: `AUTO` / `DETAILED` / `DETAILED_ON_FAILURE`
- matched cursor の reset 方針: `setResetMatchedWithConsumed(false/true)`

観測値（両 host で同じ JSON の形にそろえる）:

| key | 内容 | Java と Rust | 設定間 |
|---|---|---|---|
| `ok` / `pos` / `matched` | `ParseContext` での受理と consumed / matched の 2 つの cursor（CP） | 一致 | `matched` は同じ reset 方針の中で一致、他は全設定で一致 |
| `cst` | CST を rule node（`R<i>`）だけに射影した木と各 span（CP） | 一致 | 一致 |
| `owned` | 解析後に見える `CAPTURE` の束縛（Java `captured`、Rust `captured_text`） | 一致 | 一致 |
| `diag` | 全入力の解析（Java `Mapper.diagnose`、Rust `parse_tree_detailed_with_options`）の失敗種別と CP 位置 | 一致 | 一致 |
| `ast` | 受理時の `@mapping` AST（型、span、field）の正規化 JSON | 一致 | 一致 |
| `hint` | 最遠失敗位置と期待した候補（整列済み） | 比べない（下記） | 一致 |

期待した候補の文字列は host ごとに表現が違う（Java は `'a'` や parser class 名、Rust は `a` や
`identifier`。生成 Mapper の Javadoc にある "Native syntax hints are backend-specific"）。
また最遠失敗位置も、末尾入力の期待（`end of input`）を最遠失敗に含めるかが host で異なる。
したがって Java と Rust の間では失敗種別と CP 位置（`diag`）だけを比べ、`hint` は各 host の中で
設定に依存しないことを確かめる。期待候補の host 間一致は既存の個別 corpus の範囲に留まる。

さらに次を比べる。

- **Java host の Rust 生成と native Rust 生成**: 5 つの生成 file が byte 一致する。
- **frontend の受理**: Java（`GrammarValidator` と全生成器）と native `unlaxer generate` が、
  同じ文法を共に受理するか共に拒否する。
- **左再帰の拒否**: 3 seed に 1 つ、生成した文法の root から leftmost に戻る参照を入れた
  変種を作り、両 frontend が `E-LEFT-RECURSION` で拒否することを確かめる
  （[left-recursion.md](left-recursion.md)）。通常の生成文法は構成上この形を作らない。

## 不変条件（各 host で検査）

- **rollback**: 解析が失敗した context に、token / CST node、2 つの cursor の前進、`CAPTURE`
  の束縛、AST field capture（Rust `capture_spans`）、scope 宣言が残らない。登録した user state
  （Java `registerTransactionalState`、Rust `set_state`）が変わらない。同じ context で再解析しても
  失敗し cursor は 0 のまま。生成文法の構文は user state を書かないので、user state は
  「変わらないこと」だけを確かめる。部分式の rollback は、上の終了状態と、memo の再生を含む
  設定間一致として観測する。
- **span**: rule node の span が入力の範囲内に収まり、親が子を包み、兄弟が重ならない。
- **決定性**: 新しい context で同じ入力を 2 回解析すると、受理・cursor・CST・束縛が一致する。
- **原文の再構成**: 受理時、root の text が入力の消費済み prefix と一致する。Java では CST の
  葉 token の text を順に連結すると root の text になる（Rust の CST は literal に node を作らない
  ため、Rust 側は root span の text で確かめる）。
- memo の実行経路を通ったことの確認として、run 全体で両 host の成功 memo と失敗 memo の hit が
  それぞれ 1 以上あることを要求する（`regressions.json` の `memo-replay` が必ず両方を起こす）。
- 受理経路を通ったことの確認として、corpus を除く生成入力だけで受理入力を持つ seed が 60% 以上
  あることを要求する（CI seed 集合で 20 / 24、seed `1000..1149` で 124 / 150）。
- 生成文法の受理率・左再帰の拒否数・受理入力の割合は seed 数が 12 以上のときだけ検査する。
  1 seed を手で再現するときは finding の有無だけで判定し、finding の検査はこれらより先に行う。

## 生成対象

文法（`GrammarGenerator`、上限は `Limits.CI`: rule 4、入れ子 3、sequence 3 要素、選択肢 3）:

- literal（補助文字 `😀`、合成済みの `é`（U+00E9）、結合文字列 `é`（e + U+0301）、`界` を含む）、rule 参照、sequence、
  choice、optional `[ ]`、repeat `{ }` / `+` / `{n,m}`、separated `%`
- token: `IdentifierParser`、`NumberParser`、`LOOKAHEAD`、`NEGATIVE_LOOKAHEAD`、`CHAR_RANGE`、
  `ANY`、`NEGATION`、`CI`、`EOF`
- `CAPTURE(name, ...)` / `SAME_AS(name)`、AST field capture `@f`、`@mapping` の基本形
- `@whitespace: none` と既定の whitespace
- 一部の seed では非消費の repeat / separated 本体（両版が Java Occurs 規則で停止する形、
  [nullable-repetition.md](nullable-repetition.md)）

左再帰は構成で作らない（自分以前の rule への参照は、同じ sequence の非 nullable 要素の後に
だけ置く）。参照されない rule は作らない。`SAME_AS` は同じ sequence の先行 `CAPTURE` の後にだけ置く。

入力: 文法からの導出（32 文字で打ち切る。受理されるとは限らない）と、それを壊した入力（挿入、削除、置換、補助文字の
挿入、結合文字 U+0301 の挿入、CRLF の挿入、途中での切断）、および空入力。導出は `LOOKAHEAD` /
`NEGATIVE_LOOKAHEAD` / `EOF` の制約を見ないので、予備の導出 12 個（各 2 個の破壊入力付き）も作り、
Java parser が全体を受理する予備を最大 2 組だけ入力に加える（`ParityHarness.RESERVE_GROUPS`）。
Java parser による選別は決定的で、選んだ入力は両 host で同じに比べる。

[#700](https://github.com/opaopa6969/unlaxer-parser/issues/700) の cut / label、
[#701](https://github.com/opaopa6969/unlaxer-parser/issues/701) の引数付き規則は、merge 後に
`GrammarModel` の式と `GrammarGenerator.element` に追加する。

## 実行のしくみ

Rust の compile が重いので、文法ごとに crate を作らない。1 batch の文法を

1. Java frontend で Java parser / AST / mapper と Java host の Rust source を生成し、
2. native `unlaxer generate` でも Rust source を生成して byte 比較し、
3. Java は 1 回の `javax.tools` compile、Rust は全文法を別 module として持つ 1 つの probe crate を
   1 回の `rustc` で compile し（生成 module は `super::` だけで互いを参照するので同居できる）、
4. 全入力 × 12 設定を両方で実行して比べる。

Rust runtime は文法を `Expr` の表として解釈するので、生成 module は小さく、24 文法でも compile は
数秒で済む。compile に失敗した場合は文法ごとに compile し直して原因の文法を特定する。

## 縮小と corpus

不一致や不変条件違反が見つかると、`Shrinker` が入力 → 文法 → 入力の順に貪欲に縮小する。
各段の候補はまとめて 1 batch（compile 1 回ずつ）で評価し、同じ種類の finding を再現する最小の
候補を残す。結果は `unlaxer-dsl/target/property-based-parity.json` に、そのまま
`spec-corpus/property-based/regressions.json` に追加できる形（`id`、`kind`、`grammar`、`inputs`）
で書かれる。縮小前の finding は `original` に残る。

縮小は CI の job timeout を超えないように制限する。finding は縮小の前に
`target/property-based-parity-raw.json` へ書き出す。縮小するのは種別の異なる代表から最大
`pbt.shrinkLimit` 件（既定 3）、合計 `pbt.shrinkSeconds` 秒（既定 300）までで、残りは
`"shrunk": false` のまま `property-based-parity.json` に入る。時間切れで途中までの縮小結果は
`"budgetExhausted": true` になる。CI はこれらの file を test の成否にかかわらず artifact
`property-based-parity` として upload する。

- `spec-corpus/property-based/regressions.json`: 過去の run で見つけて固定した縮小例。毎回
  ランダム文法の前に再生する。`expectedRejection` を持つ entry は、両 frontend がその code で
  拒否することを確かめる。
- `spec-corpus/property-based/known-differences.json`: 既知差。各 entry は `issue` と `reason` を
  必須とし、`kind`（finding 種別の正規表現）と、任意で `grammar` / `detail` の正規表現、および
  正規表現で書けない数値条件 `condition` で対象を限定する。`condition` には
  `java-syntax-offset-after-rust`（両方とも `syntax` 失敗で、Java の offset が Rust より大きい）と
  `java-stack-starts-after-rust`（`KnownDifferences.javaStackStartsAfterRust`）がある。どちらも
  #706 のために作ったもので、#706 の修正後はどの entry も使っていない。現在の既知差は 0 件
  （file は `[]`）。
  除外した件数は毎回 `[pbt] ... excludedKnown=` に出る。1 件も除外しなかった entry があると
  テストは失敗する（直った差や、範囲がずれた entry を残さないため）。黙って除外しない。

## CI と手での実行

CI（`.github/workflows/maven.yml` の "Rust backend and Java conformance"）は固定 seed `1..24`
を 1 batch で回す。予算は 2 分以内（手元の負荷の高い共有機で約 15 秒。生成器の決定性・
妥当性を 1,000 seed で確かめる Java だけのテストが別に約 6 秒）。テストコードは Java 17 で
compile できる書き方にしてあり、`unlaxer-dsl-jdk17` の `--release 17` build でも同じテストが走る
（Rust を要するテストは `-DrustConformance=true` のときだけ）。

```sh
# CI と同じ
mvn -B -pl unlaxer-common,unlaxer-dsl -am test -Dtest=PropertyBasedParityTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false

# 予算を大きくして手で回す（seed 1000 から 300 文法、50 文法ずつ compile）
mvn -B -pl unlaxer-common,unlaxer-dsl -am test -Dtest=PropertyBasedParityTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpbt.seed=1000 -Dpbt.grammars=300 -Dpbt.batch=50

# 1 つの seed を再現する（縮小を省略するなら -Dpbt.noShrink=true）
mvn -B -pl unlaxer-common,unlaxer-dsl -am test -Dtest=PropertyBasedParityTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dpbt.seed=137 -Dpbt.grammars=1
```

| property | 既定 | 意味 |
|---|---|---|
| `pbt.seed` | `1` | 最初の seed |
| `pbt.grammars` | `24` | seed の数（文法の数） |
| `pbt.batch` | `24` | 1 回の compile にまとめる文法の数 |
| `pbt.noShrink` | `false` | 縮小を省略する |
| `pbt.shrinkLimit` | `3` | 縮小する finding の最大数（種別の異なる代表を優先） |
| `pbt.shrinkSeconds` | `300` | 縮小に使う合計時間の上限（秒） |
| `pbt.ignoreKnown` | `false` | `known-differences.json` を使わず、既知差も finding として出す |
| `pbt.injectFault` | `false` | 縮小器の自己確認用。補助文字を含み prefix が受理される入力すべてに合成 finding を出す（テストは失敗する） |

`-Dpbt.injectFault=true -Dpbt.seed=3 -Dpbt.grammars=1` では、元の 3 rule・約 40 node の文法と
16 文字の入力が、`R0 ::= NOTAHEAD ;` と入力 `😀` まで縮む（手元で約 1 分）。

出力:

- `target/property-based-parity-raw.json`: 縮小前の finding（corpus 形式）。縮小の前に書く。
- `target/property-based-parity.json`: finding（縮小済み、または `"shrunk": false`）。空なら成功。
- `target/property-based-parity-observations.jsonl`: 入力ごとの既定設定での Java / Rust 観測値。
- `target/property-based-parity-stats.txt`: 文法数、入力数、受理数、memo hit、生成した構文の内訳。

seed は `java.util.Random` で展開する（JDK 仕様で列が固定）。同じ seed・同じ revision なら同じ
文法と入力になる。生成器を変えると同じ seed でも別の文法になるので、再現例は seed ではなく
縮小した文法と入力を `regressions.json` に固定する。

## これまでの結果

開発時（2026-10-19、生成器の現 revision）に次を回した。

| 範囲 | 文法 | 入力 | 観測（host あたり） | finding |
|---|---|---|---|---|
| CI seed `1..24` + corpus | 35（左再帰変種 8 を含む） | 370 | 4,440 | 0（#706 の既知差のみ） |
| seed `1000..1149`（予備入力の導入後） | 203（左再帰変種 50 を含む） | 2,113 | 25,356 | 0（#706 の既知差のみ） |
| seed `1000..1399`（予備入力の導入前） | 536（左再帰変種 133 を含む） | 4,099 | 49,188 | 0（#706 の既知差のみ） |

生成器の旧 revision での seed `100..399` の run で、次を見つけた。

- [#706](https://github.com/opaopa6969/unlaxer-parser/issues/706): 正の `LOOKAHEAD` token が matched
  cursor を進めた後の失敗で、`diagnose` の CP 位置が Java（matched 到達）と Rust（consumed）で
  異なっていた。受理・cursor・CST・AST は一致していた。#723 の rule stack（seed 211 / 284）でも
  同じ原因の差が出た。Rust の grammar 呼び出しで失敗と rule frame の開始を max(consumed, matched)
  で測るようにして揃え（[契約](prior-matched-diagnostics.md#grammar-呼び出しの-frontier706)）、
  `known-differences.json` の 2 entry を削除した。縮小例 `lookahead-diagnostic-offset`、
  CRLF と補助文字を含む `lookahead-diagnostic-unicode-offset`、rule stack の 3 例（Classic token
  mode の `positive-lookahead-rule-stack-classic-mode` を含む）は `regressions.json` に残り、
  除外なしで一致する。上表の「#706 の既知差のみ」は修正前の結果で、その除外（文法全体に正の
  `LOOKAHEAD` があり、Java の offset の方が大きい `syntax` の差）は別の原因の差も隠していた。
  除外を外すと、seed 27 と seed `1000..1399` で `NumberParser` の不完全な指数の差（`R0 ::= NUM 'a'`
  に `2e` で Java CP2 / Rust CP1）が見えるようになった。上表の seed `1000..1399` などの
  「finding 0」は、この差を #706 の除外に含めて数えた値である。
- [#717](https://github.com/opaopa6969/unlaxer-parser/issues/717): 上の `NumberParser` の差は、
  Rust の Number token が不完全な指数（`e` / `E` と符号）を読んだ位置で失敗を記録するようにして
  Java に揃えた（受理と消費は仮数部までのまま）。`known-differences.json` の
  `number-incomplete-exponent` と判定 `KnownDifferences.numberIncompleteExponent` は削除した。
  `regressions.json` の `number-incomplete-exponent` と、trivia・Classic token mode・数値 field の
  変種 3 例は除外なしで一致する。低水準の共通 oracle は `conformance/number-token/cases.tsv`
  （Java `NumberTokenOracleTest`、Rust `tests/number_token.rs`）。
- seed 142 の受理の差（[#729](https://github.com/opaopa6969/unlaxer-parser/issues/729)）は、Rust の
  非 Classic 経路の CAPTURE でも Java と同じ子の root token の原文を保存するよう修正し、
  `positive-lookahead-capture-replay` として固定した。match-only の原文を保存する
  [capture の契約](owned-capture-replay.md)に両入口を揃え、除外は追加していない。
  全入力入口を Classic に切り替えた PR #736 の旧実装の AST 退行（seed 29 / 35 / 85 /
  135 / 209 / 281）は、入口を従来のままにする新実装で解消し、
  `owned-capture-negative-lookahead-empty-ast-field` に縮小例を固定した。
- seed 59 の生成物の bytes の差（[#730](https://github.com/opaopa6969/unlaxer-parser/issues/730)）は、
  #706 の作業で seed `1..320` を回したときに見つかった既存の差で、除外していない
  （CI の seed には出ない）。
- 数値 field（`NumberParser` の capture）の AST を Java 側の oracle が正規化できなかった。これは
  テスト側の不具合で、修正後に `numeric-field-mapping` として固定した。
- 両版とも同じ理由で拒否した文法（入れ子 container への field capture、#583 の範囲）は不一致
  ではない。生成器はこの形を低い確率でだけ作り、拒否の一致を確かめる。

memo の hit 数は host ごとに大きく異なる（seed `1000..1399` で Java 成功 56,590 / 失敗 222,004、
Rust 成功 630 / 失敗 1,816）。memo の対象範囲の実装差で、観測値には現れない。性能は範囲外なので
issue にはしていない。Rust の debug build では backtrack の多い文法の入力 1 つに数十秒かかることが
あり、大きな手動 run の時間はそれで決まる（CI の seed 集合では最長 1 秒未満）。

## 範囲外

性能の測定（[#197](https://github.com/opaopa6969/unlaxer-parser/issues/197) の凍結に従う）。
実行時間は process の timeout 検出にだけ使う。生成対象外の構文（recovery、scope 注釈、
`@typeof`、nested capture、associative、preferred AST、token adapter、LSP / DAP 等）は既存の
個別 corpus が扱う。
