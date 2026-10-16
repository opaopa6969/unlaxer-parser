# Pure PEG assertions in Java and Rust

The first runtime slice of [#545](https://github.com/opaopa6969/unlaxer-parser/issues/545)
adds an explicit Java counterpart to the existing Rust PEG assertion. It does
not replace Classic `MatchOnly` or `Not`, change UBNF `LOOKAHEAD`, or complete the
remaining consume/invert propagation and capture/replay work in that issue.

```java
Parser positive = new PegLookahead(child);
Parser negative = new PegLookahead(child, false);
```

```rust
let positive = child.clone().ahead();
let negative = child.not_ahead();
```

Both assertions evaluate the child from the consumed cursor, using normal child
recognition. Java's assertion polarity is explicit: an enclosing Classic
`TokenKind` or leaf `invertMatch` argument does not change it. An inner
`MatchOnly` remains a Classic match-only operation; its effects are speculative
within the enclosing PEG assertion.

On either success or failure, both assertions restore both cursors, CST nodes,
capture results, portable token metadata, registered transactional user state, scopes,
semantic diagnostics and recovery markers. Children in the same assertion can
observe earlier successful child effects. No speculative token or capture is
published by the assertion itself. Rust's bounded token trace also discards
speculative leaves. Java parser/transaction listeners and external I/O are
callbacks, not reversible state; applications must register their owned state
before mutation. Java restores open child transaction frames if a callback
throws, then propagates that exception. Rust panic recovery is not added.

A successful positive or negative assertion discards its child's speculative
syntax diagnostics. A failed positive assertion retains its child's failures;
a failed negative assertion retains those failures and records the assertion's
own failure from the restored state. Resource exhaustion remains
fatal, including under negative assertions, and leaves no committed speculative
state. A Java context that hits its parser-depth limit and a Rust context that
hits its resource budget cannot be reused to obtain a successful parse.

The existing diagnostic representations remain distinct. Java retains its
ordinary successful-parse reached offset; Rust's `failure()` reports zero when
there has been no syntax failure. After an earlier Classic match-only operation,
a Java assertion or parent combinator's failure frontier can include that pre-existing
matched position. Rust's explicit `parse_with_token_mode` entry selects this Classic
frontier for the same assertion, while native `context.parse` retains its source-failure
frontier. The [prior-matched diagnostic contract](prior-matched-diagnostics.md)
verifies both preparation APIs, matching transaction boundaries and the practical
Java-compatible entry. The historical offsets in this native-PEG comparison remain
separately handwritten; assertion acceptance, cursor restoration and published
state use common expectations. A reached offset with no expected tokens is not
an error to publish to an IDE.

Verification uses independent input and state oracles:

- [`cases.tsv`](../conformance/peg-assertion/cases.tsv): 22 inputs, including
  supplementary scalars, CRLF, prior matched-cursor movement, nested assertions,
  child failure and discarded/retained syntax hints.
- [`state.tsv`](../conformance/peg-assertion/state.tsv): four successful/failed,
  positive/negative operation traces, repeated in the same context. Children
  observe their speculative user state, scope and capture; none escapes.

Both run under both memo policies and all three diagnostic policies. Java tests
also cover callback exceptions, raw cursor changes, recovery, explicit polarity
and the parser-depth limit. Rust tests cover recovery, speculative token trace
and resource failure under a negative assertion on a 2 MiB thread stack.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PegLookaheadTest,PegLookaheadStateTest \
  -Dsurefire.failIfNoSpecifiedTests=false
RUSTUP_TOOLCHAIN=1.85.0 cargo test --manifest-path rust/Cargo.toml \
  -p unlaxer-runtime --test peg_assertion
```

The older [assertion contract audit](positive-assertion-contracts.md) records why
`MatchOnly(child)` and `child.ahead()` must remain different APIs. Its original
fixtures continue to verify the existing contracts.
