# Source-preserving edits and conservative project rename

Java `org.unlaxer.source.SourceEdits` / Rust `source_edits` adds checked edit plans
to the snapshot/region/source-map foundation. Java DSL `ProjectRename` / Rust
`semantic_rename` connects those plans to `ProjectSymbolIndex` identities.
This provides checked source edits and explicit query-provider policies for #382.
Language-specific formatting and LSP/Playground registration remain adapter responsibilities.

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

Import metadata is supplied separately as `ProjectRename.ImportSite` /
`semantic_rename::ImportSite`: module ID, index in the immutable module import
list, source-name token ID, and explicit alias token ID (empty for an unaliased
import). Both tokens must be complete tokens inside the import span, with the
exact source-name/alias text. Duplicate metadata, reused tokens and import tokens
misclassified as references are rejected. This is a trusted parser inventory,
not an inference from all matching text.

`prepare(..., importSites)` / `prepare_with_imports` updates the target
declaration, its resolved references and imported source-name tokens. Explicit
alias tokens and their uses remain unchanged, including `import foo as foo`.
Unaliased imports and their resolved uses change with the definition. Shadowed
local symbols, quoted strings and unrelated modules retain their original bytes.
An imported name only targets an exported root symbol: renaming a same-named
inner declaration never changes imports of the outer definition.

`prepareAlias` / `prepare_alias` instead renames one explicit local alias and
references resolved through that import, leaving the original definition and
imported source name untouched. The original definition may belong to a locked
dependency; changing that read-only definition remains forbidden. Alias
resolution follows scope and visibility before matching target identity.
Missing metadata, implicit-alias targets, unresolved/ambiguous imports and name
capture are rejected. Repeated imports with the same local name are conservatively
rejected even in disjoint scopes; no arbitrary import is selected. The old
`prepare` API remains available and rejects affected imports without metadata.
A complete inventory is a trusted parser contract; this layer cannot discover
omitted references from opaque ASTs.

## Explicit edit query providers

Java `SourceEdits.QueryProvider` takes a source inventory, exact language identity,
immutable `LanguageQueries.Project` and a map from `SourceEdits.Operation` to
`Function<LanguageQueries.Request, SourceEdits.Plan>`. Rust
`source_edits::QueryProvider::new` accepts the corresponding values and
`HashMap<Operation, EditPolicy>`, where `EditPolicy` is a boxed callback returning
a checked `Plan`. Only registered RENAME/FORMAT/CODE_ACTION operations are
advertised. An absent operation returns UNSUPPORTED; it is not an empty success.

Each request checks the complete project, package/language identity and virtual
snapshot. The policy's plan must match that snapshot and requested operation;
its edits are rechecked against the bound token/trivia inventory. COMPLETE and
PARTIAL regions retain their state, including untouched UNPARSED pieces. Failed
parses return FAILED without calling the editing policy. Policies must explicitly
choose valid target ranges and language identifier/formatting rules; this layer
does not invent a universal formatter.

The provider returns virtual CP edits in one item. `LanguageQueries` maps the
selected item through the source map and checks the current host body before
exposing edits. Apply still requires the exact snapshot and a newer version.
`singleDocument` / `single_document` rejects zero/multiple workspace plans at
this boundary: never take the first plan from a multi-document rename. Use
`prepare` plus `applyAll` / `apply_all` for a workspace batch instead. The query
API intentionally cannot present workspace-wide edits as a single-host success.

```java
var provider = new SourceEdits.QueryProvider(source, language, project,
    Map.of(SourceEdits.Operation.RENAME, request -> SourceEdits.singleDocument(
        ProjectRename.prepare(index, target, request.parameters().get("newName"),
            identifierValidator, inventories, importSites))));
```

Register this provider under its exact `Language` in `LanguageQueries`. An
application combining it with completion/definition providers must dispatch by
their actual capabilities; no provider is registered automatically.

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
| Import source-name rename / explicit local alias rename | Implemented with complete import metadata | Same |
| Snapshot-bound edit query policies and partial-state preservation | Implemented | Implemented |
| Language-specific formatting policy / multi-document UI | Caller-supplied / not provided | Same |
| LSP/Playground display and request integration | Pending #382 | Pending #382 |

Both implementations consume `docs/fixtures/source-edits/`: the same piece
inventory, seven edit acceptance/expected-text cases, and two independently
specified shadowing rename results. Matching negative tests cover stale targets,
changed source under a reused version, missing validators, incomplete inventories,
missing import metadata, scope capture, overlapping edits, invalid ownership and body
escapes. Mapping tests preserve FormulaInfo/TinyExpression/Java-style delimiters
through three snapshot layers. `import-rename.tsv` adds six independent expected
workspace results (definition versus alias, including same-name aliases, a shadowed definition and Unicode replacements), and
`provider.tsv` adds nested rename/format/action, partial retention, FAILED and
UNSUPPORTED outcomes with independent host text and CP spans. Both languages
reject foreign/stale/mismatched plans and multi-document query plans. Dedicated CI also checks Playground generation so
all new Rust runtime modules remain included in generated projects.

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test -Dtest=SourceEditsTest,ProjectRenameTest,PlaygroundCommandTest -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test source_edits
cargo test --manifest-path rust/Cargo.toml -p unlaxer-generator --test playground
```
