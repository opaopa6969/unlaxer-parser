# Positive assertion と match-only の契約

追跡: [#490](https://github.com/opaopa6969/unlaxer-parser/issues/490)。
基準 revision: `875144631d932d7fe31e66a3b68045ecaf77928e`。
Java 21.0.9 / Rust 1.85.0 で同じ原文・独立期待値を使って現行 API を監査した。
追加した両言語 contract / retry テストは Java 17 JVM でも skip なしで検証している。
この変更は仕様・fixture・CI の追加であり、runtime、既存 API、Classic の性能凍結を変更しない。

新しい明示 API `PegLookahead` と Rust PEG assertion の共通契約は
[Pure PEG assertions](peg-assertions.md) を参照。この文書の監査基準における
既存 `MatchOnly` の観測と fixture は維持する。

## 使い分け

Java `MatchOnly(child)` は **消費せずに matched cursor と match-only token を公開する**。
Rust `child.ahead()` は **元の位置で child が成功するかを検査し、両 cursor と結果を戻す**。
名前が似ていても、任意の子 parser を機械的に相互変換できる API ではない。

| 観測 | Java `MatchOnly(child)` | Rust PEG `child.ahead()` |
|---|---|---|
| 開始位置 | match-only cursor | consumed cursor |
| 成功時 consumed | 元の位置 | 元の位置 |
| 成功時 matched | 子が到達した位置 | 元の位置 |
| assertion が返す token / capture | `TokenKind.matchOnly` の原文 token、capture-site metadata を保持 | 空 fragment。CST node、capture 結果を公開しない |
| 親が consumed token を集めた結果 | 先読み token は consumed tree に含めない | 先読み結果は存在しない |
| 成功時 syntax diagnostics | 子の失敗ヒント・最遠位置を保持 | 子の試行前へ戻す |
| 子自身が失敗したとき | token/state/cursor を戻し、syntax failure は保持 | 同左。expected の文字列表現は backend 固有 |
| scope / semantic diagnostics | 登録済み transactional owner を復元 | checkpoint 内の scope store を復元 |
| user state | `TransactionalState` で登録した所有状態を復元 | `set_state` の clone 可能な状態を復元 |
| 外部 I/O / 未登録の共有状態 | rollback の対象外 | rollback の対象外 |

Java capture は生成 parser の immutable な capture-site metadata と token に属する。
Rust の `capture_spans` / `captured` は parse-local store も持つ。Java に同じキー付き
capture store が存在するとは扱わない。fixture の Java `CaptureSite` は生成される
単一子 Chain の capture-site 形状を観測し、Rust は実際の `Expr::capture` を使う。
assertion 内で作った scope 宣言と user state は、後続の子には見え、assertion 外には残らない。
成功した assertion の戻り値を観測することと、失敗した親の結果として公開することは別である。

## 同じ原文から差を切り分ける

原文 `😀P:ab;` は 6 code points。prefix `😀P:` の末尾は **CP3**、`ab` の末尾は **CP5**。
UTF-16 unit / UTF-8 byte の offset ではない。

| child / 親 suffix | Java | Rust PEG | 差の理由 |
|---|---|---|---|
| `ab` / なし | consumed 3, matched 5 | consumed 3, matched 3 | token / matched 公開 |
| `ab` / `Z` | 失敗、farthest 5、expected `Z` | 失敗、farthest 3、expected `Z` | 子内の失敗がなくても、Java は matched 到達位置を failure frame に含める |
| `a b Optional(!)` / なし | 成功、farthest 5、expected `!` | 成功、farthest 0、expected 空 | syntax diagnostic の所有範囲 |
| `a b Optional(!)` / `Z` | 失敗、farthest 5、expected `!`, `Z` | 失敗、farthest 3、expected `Z` | 上の二つの差が同時に現れる |
| `a b !` / なし | 失敗、farthest 5、expected `!` | 同じ位置・literal expectation | 子自身の失敗証拠は両方保持 |
| `ahead(a) b` / なし | 成功 | 失敗、farthest 3、expected `b` | Java の内側 match-only は matched を進める。PEG は `b` を元の `a` の位置で試す |

親が失敗した行では、最終 consumed / matched は両方 CP0。
CP5 と CP3 は各 API の契約から生じるため、片方を正解として期待値を揃えない。
成功した Java の `farthest` は到達情報も含む。expected が空なら、CP5 を構文エラーとして
IDE に公開してはならない。Rust の成功時 offset 0・空 expected もエラーの存在を表さない。
fixture は Java native expected の引用符も保持し、位置差と表示文字列の差を混同しない。

CRLF は二つの code points として数える。`😀\r\nab;` の prefix 末尾も CP3 である。
`P:😀\r\n界;` の先読み token は `[2,6)`、原文は `😀\r\n界`。
source span は半開区間で、Java の source-local index をそのまま原文 offset として使わない。

## API の対応範囲と選択規則

| 利用したい意味 | Java | Rust | 対応範囲 |
|---|---|---|---|
| literal を match-only cursor で確認し、matched を進める | `MatchOnly(new WordParser(text))` | `Expr::JavaLookahead { pattern: text, positive: true }` | 非空 literal の受理・consumed/matched は共通。token 形状と native diagnostic は同一契約ではない |
| UBNF token の `LOOKAHEAD('literal')` | 上の Java 形式へ生成 | `JavaLookahead` へ生成 | `TokenAdapterConformanceTest` が生成後の既存契約を検証。PEG `ahead()` へ置換しない |
| 任意 child の Java match-only token / matched 公開を完全に保存 | `MatchOnly(child)` | 一般 wrapper は未提供 | literal adapter を一般 child に拡張したとは扱わない |
| 任意 child の PEG positive assertion | `new PegLookahead(child)` | `child.ahead()` | 新しい共通契約は [PEG assertions](peg-assertions.md)。`Not(Not(child))` へ置換しない |
| predicate の受理結果と意味状態の rollback だけを利用 | `MatchOnly(child)` | `child.ahead()` | 両 cursor が一致して始まり、child が先読み cursor に依存しない場合の共通部分。入れ子・続く match-only を含む任意 child には拡張しない |

この監査時点では新しい wrapper API は追加しなかった。後続 #545 で Java の
明示 opt-in `PegLookahead` と共通 fixture を追加した。Rust の一般 `JavaMatchOnly`
相当と consume/invert 伝播・replay は同 issue の未完条件として残る。
既存 API を黙って同一化すると token を利用する Java caller や、非消費を前提とする
Rust PEG 文法を壊す。呼出し側では必要な意味を表から選んで明示的に opt-in する。

[shared language call](shared-language-calls.md) の成功 child は、子自身が作った
構文診断を caller へ漏らさない別の境界を持つ。この一般 assertion の差を理由に
shared-call の診断隔離を緩めない。既存の shared-call conformance も同じ CI で走らせる。

## 診断 policy と検証

`cases.json` は 18 入力。各入力を Memo OFF / SAFE_FAILURES と
Detailed / Deferred / Auto の 6 通りで両 backend に入力する（108 組）。
受理、両 cursor、原文 token の CP span / text / kind、capture-site、assertion 直後と
親終了後の結果、scope depth・宣言・参照・意味診断・登録 user state を固定する。
空の期待値を backend から生成する snapshot 更新機構は持たず、期待値は fixture に明記する。

低水準 ParseContext の Auto は両側とも Detailed に解決し、自動再試行しない。
明示 Deferred は syntax farthest 0 / expected 空で、token/state/cursor の結果は変えない。
この fixture は memo 方針間の契約を検証するが、すべての行で memo hit が発生するとは主張しない。
既存 `PackratMemoizationTest` / `MemoDiagnosticFrameTest` と Rust runtime の memo テストも実行する。

`retries.json` は成功・親 suffix 失敗・child 失敗の 3 入力（18 組）。
Java は実際に生成した `RetryMapper.diagnose`、Rust は `parse_detailed_with_options` を使う。
再実行安全性を宣言した observer で、Detailed は 1 回、Auto / Deferred は成功 1 回・失敗 2 回と
なることを数える。Java は異なる context と Deferred → Detailed も確認する。
最終診断は各 backend の独立期待値に合うことを検証し、CP5 / CP3 の差を retry で隠さない。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=AssertionContractConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

必須 CI `.github/workflows/assertion-contracts.yml` は Rust 1.85.0 を固定し、既存の
lookahead / capture / scope / diagnostic / frontend / shared-call conformance を skip なしで検証する。
`target/assertion-contracts.tsv` と `target/assertion-retries.tsv` は実観測と oracle の比較証拠。
`-DrustConformance=true` を省いた通常の Maven test で、この cross-language 検証を
実施済みとは扱わない。
