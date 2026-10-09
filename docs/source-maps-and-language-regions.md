# Source snapshots, segment maps and language regions

Status: Java/Rust runtime foundation for #369, #380 and #382. This page specifies
snapshot and mapping contracts. Higher-level calls use either
[bounded captured bodies](embedded-grammar-declarations.md) or
[same-context shared-input prefixes](shared-language-calls.md). Both discover
committed child boundaries before constructing a fresh snapshot-bound region tree;
retained call metadata alone must not be relabelled for a later source/version.
[Query forwarding and generated consumers](language-query-forwarding.md) describe
explicit provider registration and the generated LSP/Playground path.

Java APIs live in `org.unlaxer.source`; Rust APIs in `unlaxer_runtime::source`.
All source spans are half-open Unicode scalar offsets. Java strings must contain
paired surrogates; Rust strings already guarantee Unicode scalar validity.
`DocumentSnapshot` / `Snapshot` retains URI, version and complete original text.
Identity compares all three, so reusing a version with different contents cannot
make an old provider response current.

## Coordinate conversion

Snapshots convert scalar offsets to/from UTF-16 units, UTF-8 bytes and LSP
UTF-16 line/character positions. Splitting a UTF-8 character, a surrogate pair,
or a CRLF pair in an LSP position is an error. CR, LF and CRLF each end one line;
the position before CRLF is the preceding line's end. No newline normalization is
performed. This reference implementation prioritizes correctness; conversion is
linear, and reverse conversions currently scan boundaries.

## Source maps

A `SegmentSourceMap` / `SourceMap` partitions its output snapshot into nonempty,
ordered segments. Each segment names a source snapshot and span, or is generated
without a source. Independent segments may name different files or versions.

| Segment | Diagnostic origin | Editing |
|---|---|---|
| COPY | Exact intersection mapped by scalar offset; copied text is validated | Allowed only for a unique inverse |
| TRANSFORMED | Complete original segment, marked inexact | Rejected |
| GENERATED | Optional expansion/call-site anchor, marked inexact | Rejected |

For example CRLF normalization is a transformed segment; removed comment lines
are gaps between source spans; escaping is a transformed segment; a synthetic
class wrapper is generated. An unanchored generated segment has no original
location, so `diagnostics` returns no location for that portion. Providers retain
their failure/partial status independently of whether a source location exists.

`child.through(parent)` composes maps through the exact intermediate snapshot.
Composition retains disjoint and multiple-file origins rather than collapsing
them to one bounding range. Parent maps may themselves be composed. An unrelated
parent is rejected. Diagnostics can return multiple locations and their precision;
callers must preserve that multiplicity and must not relabel an inexact origin as
an exact character match.

Edits are deliberately conservative: one COPY segment, a unique original span
at every composition level, and no duplicate origin after composition. Cross-
segment edits and insertions exactly on a segment boundary are rejected even if
some could be safely optimized in a future release. This also prevents an edit
from crossing removed text. Empty output maps currently have no editable span.

## Region dispatch and edits

A `LanguageRegions` collection belongs to one host snapshot. Each region retains:

- Region/parent ID, language ID, package ID and version, grammar and entry rule.
- Full span including host delimiters, body span, independent parse state.
- A source map whose output is the bounded virtual document provided to tools.

The entry rule is unrestricted: Java `CompilationUnit` (including package/import/
class declarations) is as valid as a block entry. Region metadata is independent
of AST/CST projection. Nested regions use host-coordinate full/body spans and
composed maps; cycles, missing parents, overlapping siblings and origins outside
the region body are rejected. `at` chooses the deepest half-open body. Delimiters
remain owned by the enclosing body; EOF and empty bodies have no cursor owner.

Dispatch takes an exact language/package/version/grammar/entry provider registry.
Providers advertise operation capabilities. Missing providers yield UNAVAILABLE;
unadvertised operations yield UNSUPPORTED. COMPLETE, PARTIAL, FAILED and TIMEOUT
remain separate outcomes. The synchronous contract carries TIMEOUT from an
adapter; it does not start processes, enforce a deadline, or execute user code.
Provider exceptions/errors propagate rather than becoming an empty success.

A callback receives the full region and bounded virtual snapshot and may invoke
an existing parser at the declared entry. It returns that exact snapshot, status,
diagnostic spans and edits. Dispatch rejects stale host/provider snapshots,
unmappable edits, body escapes and edit conflicts before exposing any edits.
`apply` validates the whole batch and applies replacements in reverse source
order, retaining all untouched code units/UTF-8 bytes. Adjacent nonempty edits are
allowed; duplicate/overlapping edits and boundary-coincident insertions are
rejected. A new snapshot version must strictly increase. No recovery edits are
implicitly applied to partial input, and neighboring regions survive a child's
FAILED/PARTIAL result.

`apply` is a general host edit operation; callers applying provider edits should
pass the already checked edits from `dispatch`. It does not implement semantic
rename, token/trivia ownership, or formatting policy. No LSP/Playground integration
or external analyzer capability is claimed by this foundation.

## Parity and reproducible verification

| Contract | Java | Rust | Independent evidence |
|---|---|---|---|
| Unicode/CRLF coordinates | Implemented | Implemented | Shared positions.tsv |
| Transform/deletion/wrapper maps | Implemented | Implemented | Shared maps.tsv |
| Composition and multiple files | Implemented | Implemented | Matching assertions |
| Three-level regions and sibling isolation | Implemented | Implemented | Matching FormulaInfo/TinyExpression/Java fixtures |
| Capabilities, stale results, source-preserving edits | Implemented | Implemented | Matching positive and negative assertions |
| UBNF calls, parser adapters, AST/CST interchange | Pending #369 | Pending #369 | Not claimed |
| Analysis phases/dependency invalidation | Pending #380 | Pending #380 | Not claimed |
| Symbol rename/trivia ownership/LSP integration | Pending #382 | Pending #382 | Not claimed |
| External compiler/language-service adapters | Pending #378 | Pending #378 | Not claimed |

```sh
mvn -B -pl unlaxer-common -am test -Dtest=SourceMapsTest -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test source_maps
```

Both implementations read the checked-in, manually specified position/map
expectations in `docs/fixtures/source-maps/`. The dedicated `source-maps` CI job
runs both commands. This change does not alter UBNF frontend acceptance.


### 編集途中の EOF

通常の cursor 所有は半開区間 `[body.start, body.end)` であり、子言語の閉じ delimiter は
その子の補完先にしない。例外として `PARTIAL` な body の末尾が host EOF と一致するときは、
その EOF cursor を所有する候補に含める。最深の候補が一意でなければ
`ambiguous cursor ownership` として拒否する。`COMPLETE` / `FAILED` の末尾や、host EOF 以外の
body 境界をこの例外で受理しない。

`at()` で所有が決まっても、補完 edit には従来どおり一意な COPY 逆写像が必要。
空の virtual document で origin anchor を持たない map は、所有可能でも edit 対応を主張しない。
共有の `docs/fixtures/source-maps/partial-eof.tsv` が Unicode CP / UTF-16、入れ子、隣接、
閉じ delimiter、空領域と曖昧な EOF の期待値を固定する。

### 空本文の exact anchor

空の出力 snapshot は、segments が空の未対応 map に加え、単一の `COPY 0..0` segment で元 snapshot の一点を示せる。
この例外は空出力・COPY・単一 segment に限定し、COPY の本文一致検査と元 snapshot の位置検査は従来どおり行う。
複数 anchor、空の TRANSFORMED/GENERATED segment、非空出力に差し込む zero-length segment は拒否する。

埋め込み本文が空でも実在する opening delimiter が確認できた場合、registry は body の開始点を anchor として保存する。
diagnostics、cursor、edit は同じ host 点へ往復でき、親 map の合成でもその点を保持する。
親 map の削除境界などで二つの異なる元位置が候補になる場合は cursor/edit を拒否し、未指定の origin を推測しない。

`PARTIAL` empty region の host EOF が一意に所有される場合は、この map で virtual cursor 0 の補完と挿入を host へ戻せる。
子 grammar が FAILED/COMPLETE でも enclosure が未閉鎖な場合の所有判定は、parseState と delimiter 状態を分ける後続変更で扱う。
EOF 所有と source map の一意性は独立した条件であり、片方だけ満たしても query forwarding 完了とはしない。

### enclosure の開閉と parseState の分離

生成 registry は `Result.openEnds` / `Output.open_ends` に、未閉鎖の本文を持つ region ID を保存する。
`Child.openEnd` / `Child.open_end` は enclosing grammar の editor CST による証拠で、子 grammar の成功・失敗とは別の値である。
元の opening と body を持ち、binding node が EOF 修復の synthetic node で、`full.end == body.end == input.length` の場合だけ開いた境界とする。
strict parse の child は閉じた境界として扱い、synthetic body は従来どおり region にしない。
手書き provider の Java 3引数 Child constructor は `openEnd=false`。Rust Child literal は `open_end` を明示する。

`Result.tree()` / `Output.tree()` は明示境界を用いるため、未閉鎖の Java 本文が COMPLETE、FAILED、UNAVAILABLE でも host EOF の所有者になれる。
UNAVAILABLE に対応する provider が無ければ query は UNAVAILABLE を返し、別言語の provider へ代替しない。
closed delimiter は enclosing body に残り、同じ深さの複数 open region が一致する曖昧な EOF は拒否する。
canonical region JSON の `openEnd` で、Playground などの利用側もこの境界を参照できる。

既存の `LanguageRegions(host, regions)` / `LanguageRegions::new(host, regions)` は PARTIAL からの互換推定を維持する。
厳密な情報を持つ呼出し元は Java の3引数 constructor / Rust `with_open_ends` に open ID集合を渡す。
未知 ID と `full.end != body.end` の open指定は拒否する。空集合は PARTIAL region であっても EOF所有を明示的に無効にする。
共通 `embedded-grammars/ownership.tsv` が完成/失敗/空本文/閉じdelimiter/欠落providerと補完挿入位置を固定し、Java、native Rust、実WASMで検証する。
