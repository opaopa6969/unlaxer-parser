# General owned captures in Classic UBNF

General rule bodies support three calls in both Java and Rust:

```ubnf
grammar Pair {
  @whitespace: none
  @root @mapping(Value, params=[first, again])
  Root ::= CAPTURE_SCOPE(CAPTURE(word, ('😀' | 'x')) @first ':' SAME_AS(word) @again);
}
```

`CAPTURE(name, body)` parses a nonempty rule body and binds its successful result
as an owned value. A body may match zero characters. `SAME_AS(name)` compares the
bound text exactly at the current input cursor; it performs no normalization or
case folding. An undefined name fails normally, including a forward use before a
later definition. An empty binding succeeds at zero width. Ordinary Java-compatible
repeat and separated-list progress guards limit zero-width iterations; bounded
maximum zero does not evaluate its child.

`CAPTURE_SCOPE(body)` can read outer bindings and shadow them locally. On every
exit it restores the previous general bindings. Successful input consumption,
CST and other registered transactional state keep their ordinary commit behavior.
Failed alternatives restore bindings, and a controlled resource abort restores the
outer active parse boundary and remains fatal. No speculative definition may leak
through choice, retry, memoization or a scope exit. Generated rules depending on
these binding operations conservatively disable memoization.

Only `CAPTURE(`, `SAME_AS(` and `CAPTURE_SCOPE(` with no trivia between the name and
`(` reserve a builtin call. A space, newline or comment preserves the existing
identifier followed by a group, for example `CAPTURE ('x')`. Prefix names such as
`CAPTURE_MORE` and qualified references retain their existing meaning. Malformed
adjacent calls are rejected rather than reinterpreted as a reference and group.
Capture names are identifiers; the body and every choice alternative must contain
at least one grammar element. Lexical token bodies retain their separate syntax.

These operations use the general `NamedCapture` / `ReplayParser` / `CaptureScope`
Java API and Rust `capture_owned` / replay / capture-scope expressions. They retain
actual owned text for Input, Projected and Generated results. Positions count
Unicode scalar values (code points), including every member of a combining or ZWJ
sequence. Input values retain their input extent, Projected values their logical
extent and generated values their anchor. A bare token reference is passed directly
to the owned boundary so a virtual Projected or Generated result is not discarded
by an intermediate consumed-token chain.

General names are independent of lexical `CAPTURE` / `SAME_AS` bindings and AST
`@field` captures. Equal names in those stores do not overwrite each other. Existing
AST field captures, capture equality and rule effects keep their original-input
boundary: accepting Projected or Generated text through the general API does not
make it legal to read unrelated input text as an AST field capture. Java checks
that child result before the capture-site Chain can filter a virtual token; Rust
keeps its existing input-only capture check. Non-input AST field capture fails and
rolls the parse back. This tightens Java's previous behavior that could silently
turn a virtual result into an empty original-input field. The allocation-free
source classifier does not copy field text just to enforce this boundary.

The shared corpus is `spec-corpus/owned-capture/cases.json`. Its independent expected
values cover both cursors, mapped AST values and code-point source ranges, binding
text/provenance, shadowing, scope lifetime, retries, empty replay, imports and fatal
resource rollback. `OwnedCaptureConformanceTest` compiles and runs a Java consumer,
then checks all five Rust files byte for byte between the Java and native Rust
hosts and runs the Rust consumer, in every memo/diagnostic mode. The native host
currently generates Rust, so this comparison does not imply a native-to-Java emitter.
