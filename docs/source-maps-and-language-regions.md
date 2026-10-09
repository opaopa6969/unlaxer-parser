# Source snapshots, segment maps and language regions

Status: Java/Rust runtime foundation for #369, #380 and #382. This does **not**
complete embedded UBNF calls, a preprocessing scheduler, symbol-aware rename,
formatting, or external Java/TypeScript/Rust language-service adapters.

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
