# Rule stacks of the final error in Java and Rust

[#703](https://github.com/opaopa6969/unlaxer-parser/issues/703) attaches the grammar rules
that were active at the final error to that error, as the `FailureInfo` of kmizu/macro_peg's
evaluator does with its rule stack. A stack lists rules outermost first; each frame is a rule
name and the code point where that invocation started.

```java
Optional<RuleStack> stack = context.getRuleStack();            // also on getParseFailureDiagnostics()
stack.get().frames();   // [Frame[rule=Root, start=0], Frame[rule=Item, start=1]]
stack.get().offset();   // the failure it explains, in code points
stack.get().omitted();  // outer frames dropped beyond RuleStack.LIMIT
```

```rust
let stack: Option<RuleStack> = context.rule_stack();
let failure = parse_detailed_shared_with_rule_stack(&grammar, root, whitespace, input, options)
    .unwrap_err();                       // RuleStackDiagnostic { diagnostic, rule_stack }
```

## Contract

- **Rules.** A rule is a Rust `Rule` invoked through `Expr::Rule` (every UBNF rule), and in
  Java a parser marked `org.unlaxer.parser.GrammarRule`. The Java generator marks every rule
  class (`<Rule>Parser`) and no helper class, so both versions see the same invocations.
  `GrammarRule.grammarRuleName()` defaults to the class name without its `Parser` suffix.
  Hand-written Java parsers without the marker contribute no frames.
- **Detailed only.** Stacks are recorded only with Java `ParseOptions.Diagnostics.DETAILED` and
  Rust `Diagnostics::Detailed` (`AUTO` resolves to it for direct contexts). With
  `DETAILED_ON_FAILURE` / `DetailedOnFailure` a direct context reports no stack. The full-input
  entries that retry failures in a fresh detailed context report the retry's stack: Java's
  generated `parse` / `diagnose` already retry every failure, and the new Rust
  `parse_detailed_shared_with_rule_stack` retries every failure, a committed or labeled one
  included (the existing `parse_detailed*` still return a raised failure without a retry).
- **Unchanged default output.** `ParseDiagnostic`, its canonical JSON, `ParseError`,
  `ParseFailureDiagnostics` getters, `RaisedFailure` and every existing LSP field are
  unchanged. The stack is a separate accessor, a separate Rust function and new LSP fields.
- **Committed and labeled failures (#700).** The stack is the rules active where the raising
  `Commit` / `Label` ran: the rules the raised failure propagates out of. A caught label (or a
  committed error caught by recovery) discards it with the failure.
- **Ordinary failures.** The stack explains the farthest failure and is chosen among *failed
  rule invocations*. A failed invocation is a candidate at its *reach*, the farthest failure
  recorded while it was active (in its own body or in any rule it called). The candidate with
  the farthest reach wins, then the one with more rule frames, then the earliest. The stack is
  reported only when the winning reach is the farthest failure the context reports (Java
  `getFarthestOffset()` with a failure candidate, Rust `failure().offset`); otherwise, for
  example when the farthest failure happened only inside rules that succeeded, there is none.
- **Limit and spans.** A stack keeps its innermost `RuleStack.LIMIT` / `RULE_STACK_LIMIT` = 32
  frames and reports the dropped outer frames in `omitted`. Starts are code points of the
  original input, never bytes or UTF-16 units, and are not renumbered when frames are dropped.
  A frame's span is `[start, offset]` (Java `RuleStack.end(frame)`, Rust `RuleStack::span`).
- **Precedence.** Resource limits and name-resolution failures have no stack (Rust returns
  `None`; Java's `getParseFailureDiagnostics()` never reported name failures). A raised failure
  reports its own stack; otherwise the farthest failure's.

### Why failed rule invocations, not individual failures

The first draft selected the deepest stack among all failures at the farthest position, and
TinyCalc showed the two versions disagree with it. Java builds tokens such as
`IdentifierParser` from smaller parsers and runs whitespace delimiters before and after every
chain element, and both record failures (for `(x`, the identifier's next character and
`' '` / `/*` / `//` at offset 2). Rust tokens and trivia are atomic and record no such
failures. Any rule that picks one failure is sensitive to those extra failures, while the
set of rule invocations and whether each fails is the same in both versions. Failures inside a
token or a delimiter only extend the reach of the rule that contains them, which the matching
Rust failure extends as well.

The tie-breaks are deterministic and memoization-independent: a deeper stack is the more
specific context, and the earliest of equals follows evaluation order. Alternatives considered
were the earliest candidate (loses the deeper context on ties), the shallowest (always the
start rule for some inputs) and the longest common prefix of all failures (again sensitive to
Java-only failures, which sit at different depths).

## Memoization

Memoization never changes a stack; both tests run every case with memoization off and on.

- A memo frame records the candidate selected inside it as an absolute snapshot together with
  the frame's base depth. A replay rebases it: the frames deeper than the base follow the rules
  active at the replay. Without the rebase, `lookahead-discards-and-memo-rebases` reports the
  caller that first evaluated the rule (`Root>Probe>M>N` instead of `Root>Body>M>N`); both
  tests fail with it removed.
- The replayed failure extends the reach of the active rule, as the original failure did.
- Java records the memoized parser's own candidate inside its memo frame, because its
  `endParse` runs before the frame closes; Rust evaluates the memoized rule's own candidate at
  the rule's exit, after the replay. Selection is an associative maximum, so both give the
  result of the unmemoized parse.
- A failure memo entry of a raised failure also keeps the rules the failure left inside the
  memoized rule (Java: including it; Rust: the rule adds itself at its exit), and a replay
  restores them, so a replayed label reports the replaying caller (`memo-replayed-label`).
- A successful assertion (Java `PegLookahead` / Classic `Not`, Rust `ahead` / `not_ahead` and
  the Classic negative literal) discards its candidates and the reach it added, together with
  its other diagnostics. A shared grammar call starts its own selection; a successful call
  discards it, a failed one keeps the farther (then deeper) of the caller's and its own.

## LSP

Both generated servers add, to the `ULX-PARSE-001` diagnostic, when the stack explains the
reported offset:

- `relatedInformation`: one entry per frame, outermost first, located from where the rule
  started to the diagnostic's start, with message `in rule <Rule>`. The first entry notes
  `(<n> outer rules omitted)` when frames were dropped.
- `data.ruleStack`: `[{"rule": "<Rule>", "offset": <UTF-16 offset>}, ...]` and
  `data.ruleStackOmitted`.

`relatedInformation` was chosen over the message so the existing message, which clients and
tests match, stays unchanged and every frame has a navigable position. The Java server reads
`ParseFailureDiagnostics.getRuleStack()` from its DETAILED parse; the Rust server
(`unlaxer-lsp`) calls `parse_detailed_shared_with_rule_stack` and builds `relatedInformation`
from `data.ruleStack`, so custom backends can supply the same field.

## Cost

Only the detailed policy does any of this work, and the deferred first pass is unchanged.

- Rust: one push/pop per rule invocation; a failed invocation compares its reach and, when it
  wins, copies the active frames into a reused buffer. An assertion saves the selection only if
  it replaces it. Memo frames store shared snapshot nodes. Measured on a 7.6 KB arithmetic input
  (400 × `(1+2*3-(4/5+6))*7+8`, best of 30): `Detailed` +5 allocations per parse (+0.02 %) and
  +2–3 % time; `DetailedOnFailure` identical allocations, time within noise; `Detailed` with
  `SafeFailures` +7,206 allocations (+10 %) and +6–9 % time. The allocation audit's first
  detailed failure grows from 9 to 11 allocations (the frame stack and the selection buffer).
- Java: `ParseFrame` gains one `int` (inside its existing padding), a failed rule frame
  compares its reach, and rule depths are counted only on ties. Memo frames gain one reference.
  Measured with generated TinyCalc on the same input (best of 60): `DETAILED` +0.2 % allocated
  bytes and +3–6 % time; `DETAILED_ON_FAILURE` +8 bytes per parse, time within noise.

## Verification

- [`conformance/rule-stack/cases.tsv`](../conformance/rule-stack/cases.tsv): 15 inputs over 14
  fixtures with handwritten stacks: nesting, a farther alternative, deeper and equal-depth ties,
  a discarded negative lookahead whose stack is only reachable through a rebased memo replay,
  commit and label at the raise, a replayed label, a caught label followed by an ordinary
  failure, 40 nested rules with 8 omitted frames (ordinary and committed), supplementary
  characters and a rule-free parse. Java `RuleStackTest` and Rust `tests/rule_stack.rs` run them
  under both memo policies and every diagnostics policy, check the offset, the raised kind and
  label, and that the memo cases really replay; Rust also runs the full-input entry under every
  policy and checks its diagnostic equals `parse_detailed_shared_with_options`.
- [`conformance/rule-stack/lsp-tinycalc.json`](../conformance/rule-stack/lsp-tinycalc.json) and
  [`lsp-raising.json`](../conformance/rule-stack/lsp-raising.json): 11 documents opened in both
  generated servers over real stdio (`RuleStackLspConformanceTest`, Rust with
  `-DrustConformance=true`), comparing `data.kind`, `data.ruleStack`, `ruleStackOmitted` and every
  `relatedInformation` message and range. The 8 TinyCalc documents include trailing input, 37
  nested rules with 5 omitted, and a comment holding a supplementary character before the error,
  where both versions start `Expression` after the comment. The 3 documents of a grammar with
  UBNF `COMMIT` / `LABEL` (#710) cover a committed error, a labeled failure and an ordinary
  failure after committed statements.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test -Dtest=RuleStackTest,RuleStackLspConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
RUSTUP_TOOLCHAIN=1.85.0 cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test rule_stack
```

## Limitations

- Shared grammar calls carry stacks with the called grammar's rule names; no shared corpus
  covers them yet.
- A Java grammar built by hand must mark its rule parsers with `GrammarRule` to get stacks.
- The stack of a successful parse with trailing input exists only when a failed rule reached the
  farthest failure; otherwise the LSP diagnostic has no `relatedInformation`, as before.
