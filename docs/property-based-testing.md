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
挿入、結合文字 U+0301 の挿入、CRLF の挿入、途中での切断）、および空入力。

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

- `spec-corpus/property-based/regressions.json`: 過去の run で見つけて固定した縮小例。毎回
  ランダム文法の前に再生する。`expectedRejection` を持つ entry は、両 frontend がその code で
  拒否することを確かめる。
- `spec-corpus/property-based/known-differences.json`: 既知差。各 entry は `issue` と `reason` を
  必須とし、`kind`（finding 種別の正規表現）と、任意で `grammar` / `detail` の正規表現で対象を
  限定する。除外した件数は毎回 `[pbt] ... excludedKnown=` に出る。黙って除外しない。

## CI と手での実行

CI（`.github/workflows/maven.yml` の "Rust backend and Java conformance"）は固定 seed `1..24`
を 1 batch で回す。予算は 2 分以内（手元の負荷の高い共有機で約 15 秒。生成器の決定性・
妥当性を 1,000 seed で確かめる Java だけのテストが別に約 6 秒）。

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
| `pbt.ignoreKnown` | `false` | `known-differences.json` を使わず、既知差も finding として出す |
| `pbt.injectFault` | `false` | 縮小器の自己確認用。補助文字を含み prefix が受理される入力すべてに合成 finding を出す（テストは失敗する） |

`-Dpbt.injectFault=true -Dpbt.seed=3 -Dpbt.grammars=1` では、元の 3 rule・約 40 node の文法と
16 文字の入力が、`R0 ::= NOTAHEAD ;` と入力 `😀` まで縮む（手元で約 1 分）。

出力:

- `target/property-based-parity.json`: finding（縮小済み、corpus 形式）。空なら成功。
- `target/property-based-parity-observations.jsonl`: 入力ごとの既定設定での Java / Rust 観測値。
- `target/property-based-parity-stats.txt`: 文法数、入力数、受理数、memo hit、生成した構文の内訳。

seed は `java.util.Random` で展開する（JDK 仕様で列が固定）。同じ seed・同じ revision なら同じ
文法と入力になる。生成器を変えると同じ seed でも別の文法になるので、再現例は seed ではなく
縮小した文法と入力を `regressions.json` に固定する。

## これまでの結果

開発時（2026-10-19、生成器の現 revision）に次を回した。

| 範囲 | 文法 | 入力 | 観測（host あたり） | finding |
|---|---|---|---|---|
| CI seed `1..24` + corpus | 35（左再帰変種 8 を含む） | 272 | 3,264 | 0（#706 の既知差のみ） |
| seed `1000..1399` | 536（左再帰変種 133 を含む） | 4,099 | 49,188 | 0（#706 の既知差のみ） |

生成器の旧 revision での seed `100..399` の run で、次を見つけた。

- [#706](https://github.com/opaopa6969/unlaxer-parser/issues/706): 正の `LOOKAHEAD` token が matched
  cursor を進めた後の失敗で、`diagnose` の CP 位置が Java（matched 到達）と Rust（consumed）で
  異なる。受理・cursor・CST・AST は一致する。縮小例 `R0 ::= 'a' AHEAD 'é'`（入力 `ax`）を
  `regressions.json` の `lookahead-diagnostic-offset` に固定し、`known-differences.json` で
  同じ失敗種別の offset 差だけを、正の `LOOKAHEAD` を含む文法に限って除外している。
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
