# Bounded REGEX core

Issue [#578](https://github.com/opaopa6969/unlaxer-parser/issues/578) introduces a
standalone, JVM-free matcher in `unlaxer_runtime::regex`. It is an independent
implementation using the Java 21 public Pattern contract and real Java oracle
runs. No Java implementation source or external regex engine is included, and the
runtime still builds directly with `rustc`, with no Cargo dependencies.

This is one stage of [#544](https://github.com/opaopa6969/unlaxer-parser/issues/544),
which remains open. The complete machine-readable contract is
[`capabilities.tsv`](../spec-corpus/regex-core/capabilities.tsv). Unicode features
are tracked by [#585](https://github.com/opaopa6969/unlaxer-parser/issues/585), and
advanced constructs/capture semantics and class-operator edge cases by
[#586](https://github.com/opaopa6969/unlaxer-parser/issues/586). These items must be
implemented and verified before claiming full Java Pattern parity.

```rust
use unlaxer_runtime::regex::Regex;

let regex = Regex::compile(r"(?i)[a-z]+(?:_[0-9]+)?").unwrap();
let found = regex.looking_at("Ab_12!").unwrap().unwrap();
assert_eq!(found.end_cp, 5);
assert_eq!(found.end_utf16, 5);
assert_eq!(found.end_utf8, 5);
```

`Regex` is immutable, `Send + Sync`, and reusable after mismatch or resource error.
Every call owns its matching state. Coordinates are relative to the supplied
remaining-input slice, whose start is the beginning for anchors. This matches the
existing Java adapter's `Pattern.matcher(remaining).lookingAt()` invocation; it is
not a search over the complete source or a mutable `Matcher.find()` session.

`looking_at` returns `Result<Option<Match>, MatchError>` and permits an empty
prefix. `token_prefix(input, invert)` applies the existing Java `RegexTokenParser`
boundary separately: EOF never produces a token; successful empty matches never
produce a token; inversion succeeds with exactly one scalar only when the original
pattern has no prefix match. A successful empty prefix is not inverted failure.
Neither API changes a parser cursor or commits a CST. Connecting these operations
to all token modes, sticky parser resource failures and both UBNF generation hosts
is the next unit, [#587](https://github.com/opaopa6969/unlaxer-parser/issues/587).
Java's existing Pattern adapter is preserved.

The core includes scalar literals/escapes, ASCII predefined/POSIX sets, scalar
class ranges, nested union/negation/nonempty intersection/subtraction, ordered
alternation and concatenation, greedy/reluctant/possessive repetition, ordinary and
noncapturing matching groups, atomic groups, basic input/line anchors, and scoped
or unscoped ASCII flags `i/d/m/s/x`. Quantities are represented by counters, not by
expanding a pattern into billions of instructions. Plain parentheses do not imply
a public capture-query API. Unicode property/case/word-boundary constructs,
lookaround, backreferences, named groups and other unsupported features produce a
typed compile error. In particular, `\b`/`\B` are not approximated by ASCII
adjacency: Java 21 inherits word status for some nonspacing marks, even without
`U` (for example `e\b` fails on `e` plus U+0301).

A pattern is parsed with bounded nesting, compiled to a program and executed by an
explicit backtracking machine. Alternation order is preserved. Atomic barriers
remove pending alternatives created inside their boundary, while successful
possessive repetition uses the same barrier mechanism. Empty repetition can
satisfy its minimum but cannot loop without progress indefinitely. Character sets
are normalized to disjoint scalar intervals at compilation, so matching never
walks a recursive character-class expression.

Default compilation limits are 16,384 pattern scalars, depth 64, 8,192 syntax/operand
slots, 32,768 instructions and 1,000,000 cumulative character-set interval work.
The depth ceiling remains 64 even when a caller requests more. Default matching
limits are 1,000,000 input scalars, 1,000,000 VM dispatches and 4,096 saved
backtracking states. A saved state includes bounded repetition/atomic state.
A VM step has bounded class lookup and bounded-state-copy costs; no linear-time
matching claim is made. Large nullable minima and exponential backtracking are
stopped by the same budget. Limits may reject an otherwise valid Java pattern or
match; this is a resource policy, not a syntax mismatch. The entire supplied input
counts toward the input limit even if only a short prefix would be consumed.

`CompileLimits` and `MatchLimits` configure these policies. Compile failures expose
`CompileErrorKind::{Syntax,Unsupported,ResourceLimit}`, a scalar pattern offset and
a reason. Match failures expose `MatchError::{StepLimit,BacktrackingLimit,InputLimit}`.
`looking_at_with_limits` and `token_prefix_with_limits` return errors without
publishing a partial match. A compile error's offset identifies this implementation's
rejection site; exact Java `PatternSyntaxException` wording/index is not promised.
Valid UTF-8 scalars are preserved without normalization. Lone surrogate patterns
are explicitly unsupported, and no lossy substitution is performed.

The handwritten corpus fixes prefix text, CP/UTF16/UTF8 end positions, token
progress and compile rejection. `RegexCoreConformanceTest` checks it against actual
Java 21 `Pattern` and `RegexTokenParser`, then builds the complete runtime and probe
with standalone Rust 1.85 `rustc`. A second oracle systematically compares short
patterns/inputs directly with Java, including nullable nested repetitions and
atomic/possessive order. Resource tests use a 2 MiB thread, large quantities,
wide/deep patterns, hostile backtracking, bounded set work and concurrent reuse.
Rust resource limits are not claimed as limits imposed on Java's standard engine.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RegexCoreConformanceTest,RegexTokenParserTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime --test regex_core
```

Reports: `unlaxer-dsl/target/regex-core-java.tsv` and
`unlaxer-dsl/target/regex-core-conformance.tsv`. Opt-in Rust execution must not be
counted as verified when skipped.

Public language reference:
[Java 21 Pattern](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html).
