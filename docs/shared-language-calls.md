# Shared-input language entry calls

`SharedGrammarCalls.Registry` (Java) and `shared_calls::Registry` (Rust) call an
explicitly registered public entry on the caller's **same ParseContext and original
source**, starting at its current code-point cursor. A successful call consumes a
prefix; the caller can then parse its own suffix. This is separate from
`@embedded`, whose captured body is a bounded virtual document that the child must
consume completely. Neither API resolves packages or performs implicit I/O.

A registry key is the complete `(id, package, version, grammar, entry)` language
identity. Construct the registry from generated `embeddedGrammar()` /
`embedded_grammar()` adapters, including entries loaded from a locked package as
shown in [the package entry guide](language-profiles.md). An unknown identity is
`UNAVAILABLE`; a registration whose grammar or public entry does not exist is
rejected at construction. Changing a version does not select a nearby registration.

## Connect an existing ADAPTER

The executable example is [Parent.ubnf](fixtures/shared-language-calls/Parent.ubnf).
Its `A` token uses an ordinary UBNF `ADAPTER`, implemented by
[CallA.java](fixtures/shared-language-calls/java/CallA.java) and `call_a` in
[probe.rs](fixtures/shared-language-calls/probe.rs). There is no additional UBNF
syntax. The Java adapter subclasses `SharedGrammarCalls.Adapter` and supplies an
immutable registry and language. The Rust adapter function returns
`registry.parse(context, &language)` as an ordinary `ParseResult`.

For example, the parent receives `😀D:ab;`. At CP 3 it calls `ChildA.Root`, which
consumes `ab`, returns at CP 5, and allows the parent to consume `;` at CP 5..6.
`Call.span` is the consumed original-source CP range, including trivia consumed by
the entry. Child CST token spans retain the original source offsets. The parent
gets one opaque token with no foreign children. `SharedGrammarCalls.metadata(token)`
and `tree.shared_call(node)` expose the exact identity and independently owned
child CST. The child mapper consumes that CST; the parent mapper sees text at the
opaque boundary and never interprets the child's rule IDs as parent rules.

Java `Registry.probe` is a noncommitting typed operation: it returns `Result` with
`Call` or a `Failure(kind, offset, expected)`, restoring both caller cursors. The
adapter commits the returned consumed token. Rust `Registry.call` is the typed
committing operation; ordinary failure restores its caller cursor and private
state, and enclosing parser transactions undo successful calls. `Registry.parse`
converts its typed failure to the existing `ParseError`. `SYNTAX` failure offsets
are original-source code-point offsets from this child invocation, not a farther
failed caller alternative. Successful child calls discard their private speculative
diagnostics, including trivia probes; only failed children contribute diagnostics
to their caller. Registered child recovery is rejected as `RECOVERY`;
an empty successful entry is `LIMIT`. Deferred-diagnostic contexts return
`UNSUPPORTED` without a hidden detailed retry. Use the normal detailed mode.

## Transactions and limits

Child captures, standard scopes, named state, lexical context and trivia are
isolated from caller-owned values. On return the caller resumes its own grammar
and lexical policy. Rust's grammar session separates failure-memo rule IDs; Java
conservatively disables speculative memo/FIRST optimizations for the remainder of
the context. That persistent optimization-policy change is intentional and does
not change parsed values. Call metadata and its accounting follow successful
parent tokens: parent failure, ordered choice, longest/unique-longest choice,
and lookahead discard or restore the corresponding metadata with the transaction.
External effects of arbitrary custom adapters are not rolled back; existing
transactional-state registration and no-throw obligations still apply. A Rust
panic or a violated transactional-owner contract requires discarding the context.

Both hosts enforce at most 64 registrations, nonempty identity fields of at most
256 CP, a source of at most 1,048,576 CP, and at most 32 nested shared calls.
Committed metadata is limited to 4,194,304 retained-source CP units: each call
charges the **whole host source length**, including each nested retained call.
Rollback releases this accounting. This is a common observable accounting unit,
not an exact memory-byte guarantee: Rust retains source and byte-offset tables,
while Java shares source backing and copies tokens. The usual host parser limits
also apply. These bounds do not cover application-owned copies, arbitrary user
adapter allocations, or Java calls retained externally from repeated noncommitting
`probe` operations. Callers must bound their own retention of those results.

## Source maps and queries

`CstGrammar.parse(entry, snapshot)` discovers committed opaque boundaries as
language children. Run the existing `EmbeddedLanguages.parse` with the parent and
child registrations, then construct `LanguageQueries` from its region tree, the
exact current project snapshot, and explicit query providers. The child region's
COPY map maps virtual CP positions back to the original parent span. The example
[Checks.java](fixtures/shared-language-calls/java/Checks.java) and the Rust probe
exercise CP 4 in `😀D:ab;`, return a child location 0..1 as host 3..4, and reject
version 8 against the bound version-7 query context.

A `Call` is parse metadata, not a versioned document handle. Retained child CSTs
may be mapped after their parse context is closed, but must not be relabelled as
results for a later snapshot. Rebuild through `CstGrammar.parse` and
`EmbeddedLanguages.parse` for each current source/version. This API does not add
an editor registration automatically; use the existing explicit generated
LSP/Playground provider hooks. It does not implement whole-language semantics,
implicit recursive package discovery, or a general context-sensitive parser.

## Verification

`SharedLanguageCallConformanceTest` generates all fixture grammars through both
frontends, compares Java/native Rust generated files, executes the original-source
calls in both hosts, and compares to hand-written TSV oracles. The input cases run
with memoization OFF and SAFE_FAILURES. Controls check typed failure positions,
nullable/recovery rejection, invalid registration, deferred diagnostics, aggregate
and nested retention, budget rollback, retained mapping, and source-map/query
ownership. Rule zero is deliberately reused by the two child grammars. The corpus contains 15 input cases and 19 controls,
each exercised with memoization OFF and SAFE_FAILURES in both hosts.

`cases.tsv` columns are name, UTF-8 input hex, accepted, final consumed CP,
wrapper diagnostic CP, child grammar, child start/end CP, parent text value, and child AST end CP (the start is shared
with the call).
The diagnostic expectation is common to both hosts. A failed negative assertion
retains diagnostic failures explored inside its successful child; a successful
negative assertion discards speculative child failures. A pure literal negative assertion
therefore fails at its start, while a child with a failing optional/trivia probe
can report that farther failure. This narrowly changes Rust's existing failed
negative-assertion diagnostics to retain the child's speculative failure, matching
Java; cursor/state/CST rollback is unchanged. It does not claim parity for every
pre-existing positive-lookahead diagnostic policy. Direct shared-entry failures use their own local
invocation's diagnostics.

Run locally (plain Maven skips the opt-in cross-host test):

```sh
CARGO_INCREMENTAL=0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=SharedLanguageCallConformanceTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

The dedicated CI job also requires `target/shared-language-calls.tsv` to exist and
uploads it. A skipped opt-in test is not completion evidence.

The observation TSV records the 19 controls once per memo mode, followed by one
row per input after both modes agree (53 rows). These counts describe this bounded
fixture corpus, not complete language support or a new parsing engine.
