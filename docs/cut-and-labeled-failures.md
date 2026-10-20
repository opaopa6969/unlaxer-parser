# Cut and labeled failures in Java and Rust

The runtime slice of [#700](https://github.com/opaopa6969/unlaxer-parser/issues/700)
adds three operators modelled on kmizu/scomb's `commit` / `withErrorMessage`
(cut) and `labeled` / `|~` (labeled failures), and on LPegLabel. They stop an
error from being reported at the farthest speculative failure and stop
backtracking that cannot succeed. UBNF exposes them as builtin calls, generated
by the Java generator and by both Rust generator hosts (see [UBNF](#ubnf)).

```java
Parser cut      = new Commit(child, "expected ')'");   // or new Commit(child)
Parser labeled  = new Label("missingParen", child);
Parser caught   = new CatchLabels(child, alternative, "missingParen", "other");
```

```rust
let cut = child.commit_with("expected ')'"); // or child.commit()
let labeled = child.label("missingParen");
let caught = child.catch_labels(alternative, ["missingParen", "other"]);
```

## Contract

Every parse outcome is one of four kinds.

| Outcome | Produced by | Caught by |
| --- | --- | --- |
| success | as before | — |
| ordinary failure | as before | choice, optional, repetition, lookahead, not, recovery (as before) |
| labeled failure `L` | `Label(L, child)` when `child` fails ordinarily | only `CatchLabels(…, L, …)` |
| committed error | `Commit(child[, message])` when `child` fails ordinarily | only a recovery boundary or the outermost parse |

- `Commit` and `Label` convert only an *ordinary* child failure. A labeled or
  committed failure from the child passes through unchanged, so the innermost
  raising operator determines kind, label, message and position.
- The raised position is the code point of the active cursor where the raising
  operator started (the matched cursor under a Classic match-only parent). The
  message of a committed error is its declared message, `unexpected input` by
  default; the message of a labeled failure is its label.
- `CatchLabels(child, alternative, labels…)` evaluates `child`. If it fails with
  a labeled failure whose label is listed, the starting state is restored and
  `alternative` is evaluated from the same position; its outcome is the result.
  An ordinary failure, a committed error or another label propagates without
  trying `alternative`. At least one label is required and duplicates are rejected.
- Choice (ordered, predictive, longest, unique-longest), non-ordered selection,
  optional and every repetition stop at a raised failure instead of trying the
  next candidate or ending normally. Positive and negative lookahead (Java
  `PegLookahead` and Classic `Not`, Rust `ahead` / `not_ahead`) fail and keep
  the raised failure, whatever their polarity. Classic `MatchOnly` and every
  wrapper propagate it.
- Java `SyncPointRecoveryParser` and Rust `Expr::Recovery` catch committed errors
  as well as ordinary failures and recover in the same way. A labeled failure
  propagates through a recovery boundary. If recovery itself fails (for example
  no sync token remains), the committed error keeps propagating.
- Longest and unique-longest choice evaluate every alternative; a raised failure
  from a later alternative aborts the choice even when an earlier alternative
  already succeeded, because the choice cannot complete without evaluating it.
- A `Label` directly inside a repetition always ends that repetition with the
  labeled failure, as in LPegLabel: the iteration that finds no further item
  raises instead of failing ordinarily. Put the label after the token that
  commits to an item (`{ ',' LABEL(item, Item) }`), not around the whole item.
- A shared grammar call (Java `SharedGrammarCalls`, Rust `shared_calls`) is a
  boundary: it restores the caller's state and returns its failure as data, so a
  cut or label inside the called grammar is an ordinary failure for the caller.
- Every non-success restores cursors, CST, captures, scopes and registered user
  state exactly as an ordinary failure does. The operators add no CST node of
  their own; a successful child is observationally unchanged.

## Memoization and diagnostics

The raised failure is carried on the parse context while the failure
propagates (`ParseContext#raisedFailure()` in Java, a private field in Rust), so
the result type of every parser is unchanged and the ordinary path pays only a
field store when a new attempt starts. A failure memo entry records whether the
failure was ordinary or raised, together with its kind, label, message and
offset, and a memo hit re-raises it. Rust rules containing the operators remain
memo-safe; Java memoizes exactly the generated classes it did before.

The raised failure is structural: it does not depend on memoization or on the
diagnostic policy, including Java `DETAILED_ON_FAILURE` and Rust
`Diagnostics::DetailedOnFailure`. FIRST-set candidate exclusion treats the
three operators as unknown, so it never skips a candidate that would raise.

The final error prefers a raised failure over the farthest failure:

- Java `ParseContext#getRaisedFailure()` and
  `ParseFailureDiagnostics#getRaisedFailure()` expose it; while one is present
  `getFarthestOffset()` is its offset and `getExpectedTokens()` holds only its message.
- Rust `ParseContext::raised_failure()` exposes `RaisedFailure { kind, label,
  message, offset }` and `ParseContext::failure()` reports its offset and message.
  The full-input `parse_detailed*` functions return kind `commit` or `label`
  with that offset and message, without a detailed retry. Resource limits and
  name-resolution failures still take precedence.

Precedence of the final error, highest first: a resource limit (Java
`ParseLimitException`, Rust `resource_limit`), a name-resolution failure, a raised
failure, then the farthest failure. The full-input entries agree: Rust
`parse_detailed*` and the generated Java `diagnose` check name resolution before the
raised failure. Java `getParseFailureDiagnostics()` has never reported name
failures (they stay on `NameResolution.failure`), so it starts at the raised failure.

A raised failure is cleared whenever a new attempt starts: every Java parser entry
(`startParse`, terminals included), a transaction begin or commit, a FIRST-set
element skip and every memo replay (an ordinary failure entry clears it); in Rust
every `expression` entry, a memo replay and a successful custom callback. A caller
that ignores a raised failure therefore never turns a later, unrelated ordinary
failure into a cut, and the next parse on a reused context starts clean. Custom
parsers that catch failures themselves can use `clearRaisedFailure()` (Java).

## UBNF

```ubnf
grammar Statements {
  @root @mapping(Doc)
  Root ::= { Statement } ;
  @recovery(sync=';')
  Statement ::= 'let' COMMIT(Name, 'expected a name') ';'
              | CATCH('(' Expr LABEL(close, ')'), '(' Expr ']', close) ;
  ...
}
```

- `COMMIT(body)` and `COMMIT(body, 'message')` are `Commit` / `commit()` /
  `commit_with(message)`; the message is a string literal with the usual escapes.
- `LABEL(label, body)` is `Label` / `label(label)`; the label is an identifier.
- `CATCH(body, alternative, label {, label})` is `CatchLabels` / `catch_labels`.
- As with `CAPTURE(`, only the spelling immediately followed by `(` is a builtin
  call. `COMMIT ('a')` with trivia is still a rule reference followed by a group,
  and `COMMIT`, `LABEL` and `CATCH` remain usable rule names.
- The three calls are CST-transparent like a group: mapping, AST shape and
  captures see their body, and for `CATCH` the choice of both operands.
- Rules using them stay memoizable in both generators; FIRST-based predictive
  choice treats them as unknown, so a predictor never skips a raising alternative.

The validator rejects, with the code-point span of the offending call, in Java
`GrammarValidator` / `RaisingOperators`, in the native generator (`at CP s..e`)
and in both portability checks:

| Code | Rejected |
| --- | --- |
| `E-CATCH-UNKNOWN-LABEL` | a `CATCH` label that no `LABEL(...)` in the grammar raises |
| `E-CATCH-EMPTY` | a `CATCH` without labels (the syntax admits it so the error is positioned) |
| `E-CATCH-DUPLICATE-LABEL` | a label listed twice in one `CATCH` |
| `E-COMMIT-EMPTY-MESSAGE` | `COMMIT(body, '')` |
| `E-RAISING-REPETITION` | an unbounded repetition (`{ X }`, `X*`, `X+`, `X{n,}`) whose whole body is one `COMMIT` or `LABEL`: the attempt that finds no further item raises, so it can never succeed |

The unknown-label check is grammar-wide: a label counts as declared when any
`LABEL(...)` in the grammar raises it, even in an unreachable rule or only in the
`CATCH` alternative. It does not prove that the label can reach that `CATCH`.
A bounded repetition (`LABEL(L, 'a'){2,3}`) is not rejected: it succeeds when it
stops at its maximum, and fails with the label otherwise.
`E-RAISING-REPETITION` is also skipped where an enclosing construct can catch the
raised failure: inside a `CATCH` operand (`CATCH({ LABEL(L, 'a') }, 'b', L)` accepts
`b`) and in a `@recovery` rule (a committed error there is recovered, so
`Root ::= Statement 'x'` accepts `aa;x`; a recovery rule repeated up to the end of
input still fails there, because recovery needs a sync token). The check is
syntactic and identical on both hosts; it runs after parameterized rules are expanded
and does not follow rule references, so it rejects only the evident cases.

The three calls take part in parameterized rules like any group: parameters and calls
are substituted inside their bodies (both `CATCH` operands included), argument keys
spell them as `m(...)` / `n(message,...)`, `l(label,...)` and `h(g(..),g(..),labels)`,
and `COMMIT`, `LABEL` and `CATCH` are reserved names (`E-PARAM-RESERVED`).

The generated Java `diagnose` and the Rust `parse_tree_detailed*` functions
return kind `commit` or `label` with the raised offset and message. Both generated
language servers publish exactly that message at that position, with `data.kind`
`commit` or `label` (code `ULX-PARSE-001` as for every parse diagnostic). Generated
code of grammars without the operators is unchanged.

Preferred AST snapshots count `CATCH` as adding no CST level: each operand is its own
group, as in the Java runtime CST, so candidate depths agree on all three paths
(`spec-corpus/preferred-ast` fixture `CatchOperandDepth`). The native AST stores the
two operands once (`ElementKind::Catch { choice, operands }`, `operands.split` marks
the boundary), so nested `CATCH` stays linear in size on both frontends.

## API names

`commit`, `label` and `catchLabels` are the provisional names of #700, kept
because they are the names in the literature (cut / commit, labeled failures
and catch) and in scomb. Java uses the classes `Commit`, `Label` and
`CatchLabels` next to `MatchOnly` and `PegLookahead`; Rust uses the builder
methods `commit`, `commit_with`, `label` and `catch_labels` with matching `Expr`
variants. `RaisedFailure` names the propagating failure in both languages so as
not to collide with the existing `ParseError`, `ParseDiagnostic` and Java `Error`
expressions.

## Differences from scomb

- scomb's `commit` / `withErrorMessage` also converts a labeled failure into an
  error, and `labeled` relabels any failure. Here both convert only ordinary
  failures, as #700 specifies, so an inner label or cut wins.
- scomb's `not` turns a labeled failure into success. Here lookahead and not
  propagate labeled failures, as in LPegLabel, so that only an explicit catch
  can recover from a label.
- scomb has no error recovery. Here a sync-point recovery boundary catches a
  committed error, so a cut inside a statement still lets the next statement
  parse.
- scomb reports the failure position of the innermost parser. The raised offset
  here is where the raising operator started, which does not depend on the
  diagnostic policy; wrap exactly the expected element to point at it.
- scomb exposes the default label `fail`; ordinary failures cannot be named in
  `CatchLabels`.

## Verification

- [`cases.tsv`](../conformance/cut-labeled-failure/cases.tsv): 45 inputs over 27
  fixtures, covering each catcher and propagator, nested and innermost labels,
  recovery with and without a sync token, supplementary scalars and CRLF,
  Classic match-only and a memoized rule re-evaluated after a catch.
- [`state.tsv`](../conformance/cut-labeled-failure/state.tsv): user state and
  owned captures set before a raised failure are rolled back, also when the
  failure is caught.

Both oracles are handwritten, independent of either implementation, and run in
Java and Rust under both memo policies and all three diagnostic policies,
comparing acceptance, consumed and matched cursors, outcome kind, label, message
and code-point offset. Both tests also check that a later parse on the same
context is unaffected, that a raised failure ignored by a custom parser or raised
inside a shared grammar call does not reach the caller, and, as an observation, that a committed error stops a
16-alternative choice from evaluating any further alternative (16 evaluations
without the cut, 0 with it).

```sh
mvn -pl unlaxer-common -am test -Dtest=CutLabeledFailureTest \
  -Dsurefire.failIfNoSpecifiedTests=false
RUSTUP_TOOLCHAIN=1.85.0 cargo test --manifest-path rust/Cargo.toml \
  -p unlaxer-runtime --test cut_labeled_failure
```

The UBNF slice uses [`spec-corpus/cut-labeled-failure`](../spec-corpus/cut-labeled-failure):
`cases.json` has 9 grammars and 25 inputs covering choice, optional, repetition,
nested catch, a memoized rule, recovery and supplementary scalars with CRLF;
`invalid.json` has the 4 positioned rejections. `RaisingOperatorConformanceTest`
compiles and runs the Java consumer, generates Rust from both hosts, checks the
files byte for byte, and builds and runs both Rust consumers under all six
memo × diagnostics settings, plus both language servers over JSON-RPC.
The frontend fixtures `rust/unlaxer-ubnf/tests/fixtures/positive/cut-labeled-failure.ubnf`
and `negative.tsv` compare both UBNF frontends.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=RaisingOperatorConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

Inferring cuts automatically is [#702](auto-commit.md): a FIRST-set lint of cut-safe
alternatives and the opt-in `@autoCommit` rule annotation, which inserts these `COMMIT`s.

Out of scope: whole-snapshot
incremental caching of grammars that use these operators, which stays disabled
until reviewed.
