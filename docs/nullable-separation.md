# Nullable separated lists in Classic parsers

General rule expression `item % separator` requires one successful item, then
repeats the pair `separator item`. Java generation and both Rust generation hosts
use the Classic Occurs termination rule for that repeated tail: count a successful
tail first, then stop if its active cursor did not advance. A nullable first item
is still evaluated. Two nullable components can therefore succeed once as the
mandatory item and once more as the repeated tail's item. No artificial input
progress is added.

For example, with `token E = EOF`, `E % E` succeeds on empty input and retains two
empty item tokens. With `['a'] % [',']`, absent optional items are successful
parser iterations, but absence does not create a captured text field. This
preserves the existing Java mapper's distinction between an empty terminal value
and an absent optional value. The generated-consumer corpus fixes both cases.

The active cursor is consumed for consumed/virtual-consumed kinds and matched for
match-only/virtual-match-only kinds. A preadvanced matched cursor, the parent's
reset policy and inversion follow the same Classic rules as ordinary chains and
repetition. Java Chain sets reset policy to false before starting its local
transaction, including when its first element fails. Rust Classic Sequence and
chain-based builtins retain that policy on ordinary syntax failure, until an
outer transaction rolls it back. A fatal resource abort retains full rollback.

A failed separator or failed tail item rolls back the complete separator/item
pair. It terminates the tail without discarding the already successful mandatory
item. Failure of the mandatory item fails the list. Registered state and owned
capture definitions obey these transactions; capture scopes discard local
bindings while preserving successful ordinary state changes. An enclosing
rollback restores all of them. Controlled resource failures in the first item,
separator or tail item stay fatal and sticky; they cannot become list success.
Zero-maximum and minimum-count wrappers keep their own existing rules.

Handwritten Rust parsers can use `Expr::separated_by_java`. The existing
`Expr::separated_by` remains the pure PEG operation and still rejects a successful
unbounded no-progress tail. Generated Classic code now calls the new method.
Lexical nullable restrictions and source left-recursion checks remain separate
and unchanged. Ordinary nullable repetition is specified in
[nullable-repetition.md](nullable-repetition.md).

Handwritten Java `NamedCapture` / `ReplayParser` and Rust `capture_owned` /
`Expr::Replay` compose with lists: an empty bound replay is a successful empty
item or separator, an unbound mandatory replay fails, and a failed unbound
separator ends the tail. UBNF owned-capture syntax and its generated replay tests
are tracked in [#591](https://github.com/opaopa6969/unlaxer-parser/issues/591).
This list change does not introduce that syntax.

`spec-corpus/nullable-separation/cases.json` gives handwritten inputs and expected
prefix success, code-point cursors and mapped items for real Java/Rust consumers.
`NullableSeparationConformanceTest` compiles those consumers and compares the
five Rust source files emitted by both generation hosts byte for byte.
`conformance/nullable-separation/state.tsv` fixes runtime outcomes, both cursors,
reset policy, state, capture shadowing, child invocation counts, tail cardinality,
original/retained CST and diagnostic frontier/expected presence across all four
token kinds, inversion, memo modes and diagnostic modes. The runtime tests also
cover outer bounds, rollback/retry, capture scope and empty/nonempty/unbound replay
composition, plus resource failure at each of the three list positions.

This is a development change after 3.3.0, tracked in
[#616](https://github.com/opaopa6969/unlaxer-parser/issues/616).
The broader generated/runtime parity work remains tracked separately.
