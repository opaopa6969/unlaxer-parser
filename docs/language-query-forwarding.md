# Cursor and project-aware language query forwarding

Java `org.unlaxer.source.LanguageQueries` / Rust `language_queries` connects a
host cursor, nested language region, virtual snapshot and project context to a
query provider. `ProjectQueryProvider` / `semantic_queries::ProjectQueryProvider`
implements completion, hover and definition using the existing immutable
`ProjectSymbolIndex`. No compiler, language server or user program is launched.
This is a vertical runtime slice of #369 and a protocol prerequisite for #378.

## Request and snapshot contract

A Project contains an ID, version, URI-keyed immutable document snapshots and
configuration strings (such as source level/classpath/tool version). URI keys must
match the corresponding snapshots. The current host must appear exactly in that
registry. Query execution checks the complete current host and Project context,
including document contents and configuration, against the bound context.

The innermost half-open region body is selected. Its language/package/version/
grammar/entry identity chooses the provider. Missing providers return UNAVAILABLE;
missing operation capabilities or an unmappable cursor return UNSUPPORTED.
Delimiters remain owned by the containing language, and EOF has no owner.

A request gives the provider the region, operation, canonical Unicode scalar
cursor in the virtual document, complete project context and operation parameters.
The semantic adapter accepts `prefix`/`expectedType` for completion and a parsed
token `name` for hover/definition. It does not infer token boundaries by scanning
arbitrary host text. The semantic module's URI/version/text must exactly match the
virtual document; the semantic project ID/version must match the request.

`SegmentSourceMap.cursor` / `SourceMap::cursor` reverses exact COPY segments,
composing piecewise runs instead of scanning every character. It requires a
unique virtual position with an unambiguous exact original boundary. Deleted
text, transformed interiors, duplicated original text and ambiguous segment
boundaries return no cursor. Generated wrappers do not claim original positions;
an adjacent exact copied boundary can still be forwarded.

## Responses and document ownership

Responses repeat the exact virtual snapshot and project ID/version. Old responses
are rejected. Response state describes the query operation; the independent parse
state remains on the request region. Partial-AST adapters can explicitly return
PARTIAL while preserving usable candidates. Every location/edit names its own full snapshot and scalar span.
The mapper follows these ownership rules:

1. A location owned by the exact queried virtual snapshot is mapped through that
   region's source map, preserving multiple origins and exact/inexact precision.
2. An already-original host location stays in host coordinates.
3. A different file's location stays in its own coordinates, after its exact
   snapshot is verified against the Project registry. It never receives the
   current embedded region's offset or source map.
4. Unknown/stale documents are rejected, including a stale virtual document
   returned under the same URI. The registry requires one current snapshot per
   URI; ambiguous dependency URIs must be canonicalized by the project adapter.

A completion item holds its own alternative edit batch. Alternatives are checked
independently, so two suggestions replacing the same prefix do not falsely
conflict. Edits map only through unique COPY inverses and must end in the current
host's body. Already-host edits are checked against that same body. Cross-document
workspace edits are explicitly unsupported here, even when the target file is a
known project document. Apply one selected item's edits through the existing
snapshot-bound edit application API. FAILED/TIMEOUT/UNSUPPORTED responses cannot
contain edits; provider errors propagate instead of becoming empty success.

## Semantic adapter

The adapter resolves visible project/import symbols using the existing index.
Completion transports type compatibility/reason and the index's prefix edit.
Hover supplies the symbol's type; definition supplies its declared location.
Unresolved lookup returns FAILED, ambiguous candidate lookup returns PARTIAL,
and capabilities advertise only these implemented operations. This adapter uses
already supplied semantic models; it is not a Java/TypeScript/Rust compiler and
does not claim those language services' type/macro/flow/ownership analysis.

Foreign definitions are located by their full symbol identity (dependency/module)
and owning semantic snapshot. A library definition remains in that library even
when the query originated in a third-level embedded region. Project context must
include any foreign document whose position will be exposed.

## Parity and remaining integration

Both Java and Rust consume `docs/fixtures/language-queries/queries.tsv`, with seven
independent expected status/label/location/edit outcomes. The fixture covers
three nested snapshots, Unicode/CRLF in the host, local and imported definitions,
hover, two competing completion edits, unresolved lookup, unsupported formatting
and host delimiter dispatch. Negative cases cover stale host/project/provider/
foreign-document snapshots and foreign edits; cursor tests cover deletion,
transformation and duplicated origins. Runtime modules are included by both
Playground generators and checked by their generated-module tests.

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test -Dtest=LanguageQueriesTest,PlaygroundCommandTest -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test language_queries
cargo test --manifest-path rust/Cargo.toml -p unlaxer-generator --test playground
```

| Capability | Java | Rust |
|---|---|---|
| Host cursor -> nested virtual request and project context | Implemented | Implemented |
| Semantic completion/hover/definition -> owning original document | Implemented | Implemented |
| Snapshot/capability checks and independent completion alternatives | Implemented | Implemented |
| UBNF grammar calls and automatic region discovery | Pending #369 | Pending #369 |
| LSP/Playground request wiring and capability display | Pending #369/#382 | Pending #369/#382 |
| External Java/TypeScript/Rust analyzers | Pending #378 | Pending #378 |
| Workspace-wide edits | Pending #382 | Pending #382 |
