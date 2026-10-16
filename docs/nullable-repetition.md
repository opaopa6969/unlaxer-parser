# Nullable repetition in generated Classic parsers

General rule expressions `{ body }`, `body*` and `body+` can repeat a body
that succeeds without advancing input. Java generation and both Rust generation
hosts use the existing Java Occurs rule: count a successful iteration first,
then stop if that iteration did not advance the active cursor. This is a safe
termination condition, rather than an instruction to skip the nullable body.
The iteration's successful CST and owned state remain observable.

The active cursor is the consumed cursor for consumed/virtual-consumed token
kinds, and the matched cursor for match-only/virtual-match-only kinds. A body
can therefore advance the matched cursor without advancing the consumed cursor.
The enclosing parser's reset policy and normal transaction boundaries still
apply. A no-progress success counts once: runtime `min=1` can succeed, while
`min=2` fails if the first successful iteration makes no progress. A syntax
failure is not a successful iteration. Failed child transactions and enclosing
rollback restore their state, captures and CST. Fatal resource errors remain
fatal and cannot be converted into a successful zero-iteration repeat.

For example, `{ [ 'x' ] } '😀'` accepts `😀` and `xxx😀`. Repeating `EOF`
on empty input terminates after one successful empty check. Repeating `EMPTY`
keeps its historical matched-cursor behavior; it is not a pure empty literal.
A zero maximum remains a separate operation: `body{0}` never runs its body,
as specified in [zero-repetition.md](zero-repetition.md).

Rust generated expressions use `repeat_java`, whose existing runtime implements
this termination rule. The source-generation hosts no longer reject every
nullable unbounded repeat before emission. The runtime's pure PEG `repeat`
continues to reject successful no-progress unbounded repetition; no PEG behavior
is changed. Lexical expression repetition retains its separate nullable-body
validation. Reachable source left recursion is still rejected before generation,
including cycles behind nullable repetition; see
[left-recursion.md](left-recursion.md).

Nullable separated-list tails use their own Classic operation, specified in
[nullable-separation.md](nullable-separation.md). The pure PEG separated-list API
keeps its separate no-progress failure behavior.

`spec-corpus/nullable-repeat/cases.json` contains independent generated-consumer
expectations. `NullableRepeatConformanceTest` checks Java parsing/mapping and
native/Java-host Rust byte parity plus real parsing/mapping under all memo and
diagnostic settings. `conformance/nullable-repeat/state.tsv` fixes the separate
opaque-child runtime expectations for success count, both cursors, reset policy,
owned bindings, original CST and enclosing rollback in all Classic token modes.
Java `NullableRepeatStateTest` and Rust `tests/nullable_repeat.rs` also verify
that zero iterations cannot absorb a controlled resource abort.
