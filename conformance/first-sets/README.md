# 公開 FIRST 照会の共通 oracle (#853 first-predictors)

基準は `c8495067`（#1012 と #1006 を含む）。Java の製品コードは変更しない。Rust は内部の
`first::FirstSet` / `of` を使う公開照会 `FirstSet` / `FirstSets` を足した。照会は、`compute` と同じ不動点に
資源上限（下記）を付けたもので解く。parser が使う内部の表（`first::compute` / `first::table` とその cache）、
候補除外の分岐、`ParseContext` の field は変えていない。

## API の対応

| Java | Rust (`unlaxer_runtime`) |
|---|---|
| `FirstSets.of(Parser)` | `FirstSets::of(&grammar)` の `.rule(id)`（rule 単位）/ `.expression(&expr)`（inline 式）/ `.rules()` |
| `FirstSet.EMPTY` / `EPSILON` / `UNKNOWN` | `FirstSet::EMPTY` / `EPSILON` / `UNKNOWN` |
| `FirstSet.ofCodePoints(int...)` | `FirstSet::of_code_points(impl IntoIterator<Item = char>)` |
| `asNullable` / `withNonAscii` / `union` / `unionStarts` | `as_nullable` / `with_non_ascii` / `union` / `union_starts` |
| `isUnknown` / `isNullable` | `is_unknown` / `is_nullable` |
| `mayStartWith(int)`（負数は入力末尾） | `may_start_with(Option<char>, whitespace)`（`None` は入力末尾）、parser 自身の判定は `may_start(&str, whitespace)` |
| `equals` / `hashCode` | `PartialEq` / `Eq` / `Hash`（Rust だけの trivia も比べる。下記） |
| `toString` | `Display`（同じ書式。trivia は表示しない） |
| なし | `skips_trivia(whitespace)`（Rust の sequence は先頭で trivia を読み飛ばせる） |
| `Serializable` | なし（`Copy` の値。言語固有） |

例:

```rust
use unlaxer_runtime::{share_grammar, Expr, FirstSet, FirstSets, Rule};

let grammar = share_grammar(vec![Rule {
    name: "value",
    expression: Expr::choice([Expr::literal("a"), Expr::literal("b").optional_java()]),
}]);
let sets = FirstSets::of(&grammar);
let value = sets.rule(0).unwrap();
assert_eq!(value.to_string(), "FirstSet[nullable ab]");
assert!(value.is_nullable() && value.may_start_with(None, false));
assert_eq!(sets.rule(1), None);
assert!(value.union(FirstSet::UNKNOWN).is_unknown() && !sets.rule(0).unwrap().is_unknown());
```

## 共通契約

- **保守的な過大近似**。`may_start*` が `false` なら、その位置からの成功（消費する一致）はない。`true` は成功を保証しない。
  `unknown` または `nullable` の集合は、入力末尾を含むすべての位置で `true` を返す。
- ASCII（0..=127）は正確に持ち、それ以外は 1 つの「非 ASCII で始まり得る」flag にまとめる。非 BMP（U+1F600 など）も
  非 ASCII として同じ flag で答える。Java は先頭 code point、Rust は先頭 `char` で判定する。
- `unknown` を含む `union` / `unionStarts` は `UNKNOWN` になる（両言語とも `unknown` の集合は `UNKNOWN` と等しい）。
- 照会は parse も callback も実行しない。Java は独自 parser（`parse` を持つ型）を解析せず unknown とする。
  Rust は `Expr::Custom` / `CustomWith` の関数も `Expr::Character` の predicate も呼ばない。
  ただし Java は `SingleCharacterParser.isMatch` を純粋な述語として ASCII 範囲で呼ぶ（診断と同じ既存契約）。
  呼出し回数は契約に含めない。
- **異常な graph**。再帰・相互再帰は最小不動点で解き、停止する。Rust の `FirstSets::rule(id)` は範囲外を `None` で返し、
  grammar の中の範囲外 `Expr::Rule(id)` は parser と同じく `UNKNOWN` とする。どちらも panic しない。
- **照会の資源上限**。上限を超えた照会は、stack を使い切ったり時間が際限なく伸びたりする代わりに、保守的な `UNKNOWN`（何も除外しない）を返す。
  - Java `FirstSets.of`: 再帰しない worklist で解く。到達する parser が 100,000 個を超えると、その root は `UNKNOWN`（cache しない）。
    不動点が 1,000 round を超えると、解析した全 parser が `UNKNOWN`。深さの上限はない。
  - Rust `FirstSets::of(&grammar)`: 解析の前に、再帰しない走査で次の 2 つを数える。
    - 入れ子の深さ。上限は 1,024 で、parser 自身の実行上限（`expression nesting below 1024`）と同じ。
    - grammar 全体の式 node 数。上限は Java と同じ 100,000。

    どちらかを超えるか、不動点が 1,000 round を超えると、**全 rule** を `UNKNOWN` にする。
    `rules().len()` は rule 数のままで、範囲外の `rule(id)` は `None` のまま。
    `.expression(&expr)` は、式の深さか node 数が上限を超えると `UNKNOWN`。
  - 上限の値は揃えたが、数え方は言語ごとに違う（Java は共有 instance を 1 個と数え、round は root からの幅優先順。
    Rust は式の木の node を数え、round は rule id 順）。そのため境界の位置は一致しない。どちらも超えた側は `UNKNOWN` で健全。
    Rust は深い入れ子を `UNKNOWN` にするが、Java は正確に答える。これは精度だけの差。
  - 上限の内側では、結果は上限のない parser 用の表（`first::compute`）と同じ。
  - parser の候補除外が使う内部の表（`first::table`）には、この上限を掛けていない。
    深い `Expr` を通常の parse に渡すと、parse 自身や `Expr` の drop が stack を使い切ることがあり得る。これは base からある別の問題で、照会の上限では直らない。
- **等価性**。Java は trivia を持たないので `equals` と `toString` が一致する。Rust の `Eq` / `Hash` は trivia も比べるので、
  先頭で trivia を読み飛ばせる sequence と同じ開始集合の `of_code_points` は、表示が同じでも等しくない
  （不動点の収束判定が trivia を含めて比べるため）。共通の照合は表示・flag・`may_start` で行う。
- **snapshot**。Rust の `FirstSets` は呼出し側が所有する値で、parser はこれを読まず cache もしない
  （`FirstSets::of` を呼んだときだけ不動点を計算する）。`FirstSet` は `Copy` の値なので、変えても parser に届かない。
  Java の `FirstSet` は immutable で、`FirstSets` は parser instance ごとに結果を保持する。

## 対にする primitive

各行の Java parser と Rust 式は、生成器が同じ UBNF 構成から出す組か、同じ意味の公開 API の組にする。
FIRST の値が違うとき、別の parser を比べた差を精度差に見せないため、`accepts.tsv` の 21 id（集合演算・独自 parser・
再帰以外の全行）で、両 consumer が root での parse の受理と消費 code point 数を 8 入力（空・`a`・`b`・`ab`・`abc`・U+1F600・
` a`・U+00E9）について実測し、同じ表に照合する。

- `eof`: Java `EndOfSourceParser` は zero-match check の `LazyZero`（`{0,0}`）で、WildCard が一致しない入力末尾でだけ
  消費 0 で成功する EOF assertion。Rust `Expr::Eof` と同じ（生成器も `EndOfSourceParser` を `Eof` に写す）。
  残りをすべて消費する反復は別 primitive で、`zero-or-more-any`（Java `ZeroOrMore(WildCardCharacterParser)` /
  Rust `Expr::Any.repeat_java(0, None)`）として分けた。
- `not-lookahead`: Java Classic `Not(WordParser)`（UBNF の否定先読みの生成形）と Rust `Expr::JavaLookahead { positive: false }`。
  `peg-not-lookahead`: Java `PegLookahead(WordParser, false)` と Rust `not_ahead()`。
- `empty-word`: Java `WordParser("")`（常に失敗）と Rust `Expr::JavaEmptyWord`。PEG `Expr::literal("")` と
  `Expr::JavaEmptyLiteral`（幅 0 で成功）は別 primitive で、Java の対は unlaxer-dsl の `Lexing.LiteralParser("")` なので
  この表には入れていない（下記「対象外」）。
- `char-range`: Java は生成器が UBNF の文字範囲に出す形（`SingleCharacterParser` の `isMatch(char)` で範囲判定）、Rust `CharRange`。
- `case-insensitive`: Java `UnicodeIgnoreCaseWordParser`（生成形）と Rust `CaseInsensitive`。

## 表の読み方（`cases.tsv`）

列: `id host ascii non_ascii nullable unknown trivia display note`。

- `host` は `both`、または `java` / `rust` の 2 行組。2 行組は両方とも健全で、精度だけが違う。`note` に理由を必ず書く。
- `ascii` は開始集合の ASCII（`none` / `all` / 16 進の code point と範囲の列）。`display` は Java `toString` の実測値で、
  nullable の下に残る開始集合もここで固定する。
- 各 consumer は列から `may_start` の期待値を独立に導き、全 ASCII・入力末尾・U+00E9 / U+3042 / U+1F600 で照合する。
- `trivia` は Rust だけの観測（`none` / `policy` / `always`）。Java には暗黙の trivia がないので、共通の照合は
  `whitespace=false` で行う。Rust は `whitespace=true` で、trivia の空白と `/`（`may_start_with`）、`//` と `/*`（`may_start`）が
  加わることを別に確かめる。

## 意図した差（host 別の 10 行組）

どの組も同じ primitive（`accepts.tsv` で受理・消費が一致）で、FIRST の細かさだけが違う。健全性の根拠は「`may_start` が
`false` になる位置では、その primitive が実際に成功しない」こと。

| id | Java | Rust | 理由（健全性） |
|---|---|---|---|
| `eof` | nullable + 全 code point | `EPSILON` | 両方 nullable なので `may_start` は全位置で同じ。違うのは表示だけ（Java は zero-match check の body 集合を残す） |
| `eof-then-literal` | 全 code point | `{a}` | Java は EOF assertion の body 集合が後続へ流れる。EOF の後の `a` はどちらも全入力で失敗するので、Rust の除外も健全 |
| `empty-word` | `EPSILON` | `EMPTY` | どちらも常に失敗する。Java は空の語を nullable とみなす過大近似、Rust は正確 |
| `char-range` | `{abc}` + 非 ASCII | `{abc}` | Java は非 ASCII を常に可能とする。Rust `CharRange` は上限 `c` が ASCII なので非 ASCII で成功しない |
| `character-predicate` | `{a}` + 非 ASCII | unknown | Java は `isMatch` を純粋な述語として読む。Rust の predicate は外部状態を読み得るので呼ばない（unknown は除外しない） |
| `not-lookahead` | unknown | `EPSILON` | Classic 否定先読み。どちらも全位置で `true`。Java `Not` は解析しない |
| `peg-not-lookahead` | unknown | `EPSILON` | PEG 否定先読み。どちらも全位置で `true`。Java `PegLookahead` は解析しない |
| `case-insensitive` | unknown | 全 code point（非 nullable） | Rust が `false` にするのは入力末尾だけで、空でない語は末尾で必ず失敗する |
| `empty-chain` | unknown | `EPSILON` | どちらも全位置で `true`。空 Chain / 空 `Sequence` は消費せず成功する |
| `empty-choice` | unknown | `EMPTY` | 空 Choice は両方とも必ず失敗するので、Rust の全除外も健全 |

## 対象外・未検証

- 受理・cursor・CST・診断・memo mode は照会の観測にない（照会は parse しない）。候補除外が parse 結果を変えないことは、
  既存の `FirstSetExclusionTest`、`rust/unlaxer-runtime/tests/candidate_exclusion.rs`（Audit mode）、`conformance/choice-cursor` が担う。
- 幅 0 で成功する空 literal（Rust PEG `Expr::literal("")`・`Expr::JavaEmptyLiteral`、どちらも `EPSILON`）の Java の対は
  unlaxer-dsl の `Lexing.LiteralParser("")` で、unlaxer-common の `FirstSetParityTest` からは使えないので共通行がない。
  Rust の値は `tests/first_sets.rs` で固定した。Java `Lexing.LiteralParser("")` / `Lexing.EofParser` を `FirstSets.of` で読むと
  unknown になることは review の probe で一度確かめただけで、テストにはしていない。
- `EmptyParser`（Rust `JavaEmpty`）は `empty-eof` 判定の対象で、この表に入れていない（probe では Java unknown / Rust `EPSILON`、
  受理・消費は一致）。Java の旧来の `WordParser(word, true)`（生成器は使わない）も比べていない。
- 表にない Java parser 型（`PropagatableSource` 系の反転、`MatchOnly`、recovery など）は Java では unknown になる。
  Rust の分類は `first.rs` の `of` に従う。1 型ずつの共通行はまだない。
- `#853` の他の判定 ID（`legacy-not` / `core-combinators` / `empty-eof` / `graph-utilities`）はこの表の対象外で、未完了のまま。

## 実行

```sh
mvn -pl unlaxer-common test -Dtest=FirstSetParityTest,FirstSetExclusionTest
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test first_sets
```
