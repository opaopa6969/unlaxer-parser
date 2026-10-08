# Source-preserving edits and conservative project rename

Java `org.unlaxer.source.SourceEdits` / Rust `source_edits` adds checked edit plans
to the snapshot/region/source-map foundation. Java DSL `ProjectRename` / Rust
`semantic_rename` connects those plans to `ProjectSymbolIndex` identities.
This is a bounded first implementation for #382, not complete language formatting
or LSP/Playground edit support.

## Original pieces and ownership

A source inventory partitions the complete original snapshot into nonempty,
ordered, uniquely identified pieces. TOKEN, WHITESPACE, COMMENT and UNPARSED are
separate kinds. Trivia optionally names an adjacent token as its owner; an empty
owner names the document. Ownership cannot cross another token or unparsed piece.
Whitespace pieces contain only space, tab, CR or LF in this initial policy.
Language-specific tokenization/classification is supplied by the caller.

`roundTrip` / `round_trip` concatenates original slices and must preserve bytes
when encoded as UTF-8. Quotes, Japanese text, emoji, comments and CRLF are never
regenerated from an AST. An incomplete parse can retain its remaining text as
UNPARSED; source preservation does not require a successful parse.

## Operations and plans

| Operation | Allowed replacements |
|---|---|
| RENAME | Complete TOKEN spans; semantic selection/identifier validity is a separate adapter contract |
| FORMAT | Existing WHITESPACE pieces/subspans, with whitespace-only replacement |
| CODE_ACTION | The explicitly supplied allowed source span, including UNPARSED text |

This FORMAT policy is whitespace editing, not a language-specific indentation or
pretty-print algorithm. It cannot insert whitespace where no whitespace piece
exists, change comments, normalize quotes, or rearrange tokens. A code action may
change its explicit target range; all text outside that range remains untouched.

A plan validates the full batch, body/allowed boundary, scalar positions and
conflicts before exposure. Apply requires the exact current snapshot and a strictly
higher version. Duplicate/overlapping edits and conflicting boundary insertions
are rejected. `applyAll` / `apply_all` computes all resulting snapshots before
returning, without mutating any originals; missing, duplicate or stale documents
abort the batch. It returns the modified documents, not the entire workspace.
Filesystem/application transaction commits remain the caller's responsibility.

A child plan can be mapped through a composed `SegmentSourceMap` into a host's
piece inventory. The output snapshot must match the plan, all reverse mappings
must be unique COPY spans, all mapped edits must stay in the host body, and the
host operation's token/trivia restrictions are checked again. Generated wrappers,
length-changing transforms, duplicate origins and stale maps are rejected.

## Symbol identity adapter

`ProjectRename.prepare` / `semantic_rename::prepare` requires:

- An immutable `ProjectSymbolIndex` and an exact target Definition (identity,
  source URI, document version and declaration span).
- A complete source/reference-token inventory for every project module. The
  parser/semantic adapter explicitly identifies reference tokens; this API never
  scans all matching strings and assumes that they are references.
- A language-supplied identifier validator. Missing validators and invalid names
  are unsupported/rejected; no universal identifier grammar is assumed.

The adapter resolves each supplied reference at its position and compares the
full project/dependency/version/module/symbol identity. It includes the matching
declaration and references, leaving shadowed names, same-named strings and
unrelated modules untouched. Token boundaries, source text and exact snapshots
are checked by the original-source inventory and plan layer.

Rename is deliberately conservative. It rejects incomplete inventories, stale
source/definition snapshots, ambiguous/unresolved references matching the target
name, read-only dependency targets, duplicate references, and possible capture by
an existing symbol/import/reference. Capture checks may reject safe renames in
disjoint scopes; that restriction can be relaxed with stronger scope proofs.

The current project import model does not retain separate source-name and alias
spans. If the target is imported, the adapter therefore rejects the operation
explicitly. It does not silently rename uses while leaving an import broken.
Cross-module/alias rename awaits that metadata. A complete inventory is a trusted
parser contract; this layer cannot discover omitted references from opaque ASTs.

## Capabilities, scope and verification

No provider is automatically registered. An LSP/Playground adapter must advertise
only operations it actually implements; existing `LanguageRegions` dispatch
returns UNSUPPORTED for absent capabilities and UNAVAILABLE for absent providers.
This change does not add a UI capability display or claim integration with a
particular language server.

| Capability | Java | Rust |
|---|---|---|
| Original token/trivia ownership and incomplete-source retention | Implemented | Implemented |
| Atomic checked plans, whitespace format edits, targeted actions | Implemented | Implemented |
| Identity rename with shadowing/string/module isolation | Implemented | Implemented |
| Child plan mapping and delimiter preservation | Implemented | Implemented |
| Import source-name/alias rename and language formatting policy | Pending #382 | Pending #382 |
| LSP/Playground display and request integration | Pending #382 | Pending #382 |

Both implementations consume `docs/fixtures/source-edits/`: the same piece
inventory, seven edit acceptance/expected-text cases, and two independently
specified shadowing rename results. Matching negative tests cover stale targets,
changed source under a reused version, missing validators, incomplete inventories,
imported targets, scope capture, overlapping edits, invalid ownership and body
escapes. Mapping tests preserve FormulaInfo/TinyExpression/Java-style delimiters
through three snapshot layers. Dedicated CI also checks Playground generation so
all new Rust runtime modules remain included in generated projects.

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test -Dtest=SourceEditsTest,ProjectRenameTest,PlaygroundCommandTest -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test source_edits
cargo test --manifest-path rust/Cargo.toml -p unlaxer-generator --test playground
```
