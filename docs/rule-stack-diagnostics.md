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
context.getUnconsumedInputRuleStack();  // a successful parse that left input unconsumed
```

```rust
let stack: Option<RuleStack> = context.rule_stack();
context.unconsumed_input_rule_stack();  // a successful parse that left input unconsumed
let failure = parse_detailed_shared_with_rule_stack(&grammar, root, whitespace, input, options)
    .unwrap_err();                       // RuleStackDiagnostic { diagnostic, rule_stack }
```

## Contract

- **Rules.** A rule is a Rust `Rule` invoked through `Expr::Rule` (every UBNF rule), and in
  Java a parser marked `org.unlaxer.parser.GrammarRule`. The Java generator marks every rule
  class (`<Rule>Parser`) and no helper class, so both versions see the same invocations, and
  overrides `grammarRuleName()` with the rule name literal, so subclasses (anonymous ones
  included) keep it. Without an override the name is the class name without its `Parser`
  suffix; a `null` or blank name falls back to that as well (`GrammarRule.nameOf`). Hand-written
  Java parsers without the marker contribute no frames.
- **Names of expanded rules.** Parameterized and call-by-value rules (#701, #704) expand before
  generation into instance rules named `Name_<16 hex digits>` (see
  [parameterized-rules.md](parameterized-rules.md)), and stacks report that instance name. Both
  frontends compute the same name from the canonical arguments, so the two versions agree; the
  part before the last `_` is the defining rule. Restoring `Name(args)` would need the call text
  carried from both frontends through both generators, which the expansion intentionally drops.
- **Failed parses only.** `getRuleStack()` / `rule_stack()` report a stack only when the last
  outermost parse failed. A successful parse reports none, even though it recorded speculative
  failures: the two versions record different speculative failures inside composite tokens
  (property-based testing found `R0 ::= R1 | ID` on `a` reporting `R0>R1` in Rust only). For a
  successful parse that left input unconsumed, `getUnconsumedInputRuleStack()` /
  `unconsumed_input_rule_stack()` report what a full-input parse reports for the trailing input:
  the farthest failed rule, unless the cursor is past the farthest failure (the unconsumed input
  is then itself the farthest failure, outside every rule).
- **Detailed only.** Stacks are recorded only with Java `ParseOptions.Diagnostics.DETAILED` and
  Rust `Diagnostics::Detailed`. `AUTO` resolves to them for direct contexts, which is the Java
  default: `new ParseContext(source)` and the generated LSP server parse with `DETAILED`. With
  `DETAILED_ON_FAILURE` / `DetailedOnFailure` a direct context reports no stack.
- **Full-input entries.** Rust `parse_detailed_shared_with_rule_stack` returns the stack with the
  diagnostic, trailing input included, and under `DetailedOnFailure` retries every failure in a
  detailed pass, a committed or labeled one included (the existing `parse_detailed*` still return
  a raised failure without a retry). Java's generated `parse` / `diagnose` return no stack (their
  `ParseDiagnostic` and exception messages are unchanged); a Java caller that wants one parses
  with a `DETAILED` `ParseContext` and reads `getRuleStack()` / `getUnconsumedInputRuleStack()`,
  as the generated LSP server does.
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

A CRLF is one line terminator, so a frame that starts at the LF of a CRLF starts at its CR, as
the diagnostic's own start does (`data.ruleStack` carries the adjusted offset). A frame that
still cannot be located is skipped; it never costs the diagnostic or the other frames. A
successful parse that left input unconsumed gets the unconsumed-input stack with its
trailing-input diagnostic.

`relatedInformation` was chosen over the message so the existing message, which clients and
tests match, stays unchanged and every frame has a navigable position. The Java server reads
`ParseFailureDiagnostics.getRuleStack()` (or `getUnconsumedInputRuleStack()` after a successful
parse) from its DETAILED parse; the Rust server
(`unlaxer-lsp`) calls `parse_detailed_shared_with_rule_stack` and builds `relatedInformation`
from `data.ruleStack`, so custom backends can supply the same field.

## Cost

Only the detailed policy does any of this work, and the deferred first pass is unchanged. Note
that DETAILED is the Java default for a direct `ParseContext` and for the generated LSP server.

- Rust: one push/pop per rule invocation; a failed invocation compares its reach and, when it
  wins, copies the active frames into a reused buffer. An assertion saves the selection only if
  it replaces it. Memo frames store shared snapshot nodes.
- Java: `ParseFrame` gains one `int` (inside its existing padding). The context keeps the rule
  depth of each open frame in an `int` array and the depth of every selected candidate, so
  comparing a candidate is O(1) whatever the nesting; whether a parser class is a rule is decided
  once per class. Memo frames gain one reference.

Measured against `origin/master` 0a976016, median of 5 interleaved runs (Java: generated
TinyCalc, best of 600 parses, 60 for the flat input; Rust: the same arithmetic grammar built with
`Expr`, best of 800). The flat input is 400 × `(1+2*3-(4/5+6))*7+8` followed by `+(`; nested-N
is `(`×N `1` `)`×(N−1) `]`. Rust nesting stops at 80 (rule nesting below 256).

| Input | Java DETAILED | Java DETAILED_ON_FAILURE | Rust Detailed | Rust DetailedOnFailure |
|---|---|---|---|---|
| flat | +3.7 % time, +0.2 % bytes | −1.2 %, +8 B | +4.2 % | +2.0 % |
| nested-20 | +4.5 %, +0.5 % bytes | −0.7 % | +5.3 % | +7.7 % (13 → 14 µs) |
| nested-60 | +4.2 %, +0.4 % bytes | +0.3 % | +7.4 % | 0 % |
| nested-120 (Rust 80) | +5.4 %, +0.4 % bytes | −2.8 % | +10.0 % | 0 % |

Rust allocations (flat input): `Detailed` +5 per parse (+0.02 %), `DetailedOnFailure` identical,
`Detailed` with `SafeFailures` +7,206 (+10 %, the shared memo nodes) and +6–9 % time. The
allocation audit's first detailed failure grows from 9 to 11 allocations (the frame stack and the
selection buffer). The first version of this change walked the parse stack on every tie and grew
superlinearly in Java (+36–43 % at nested-120); the depth array removed that.

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
- [`conformance/rule-stack/lsp-tinycalc.json`](../conformance/rule-stack/lsp-tinycalc.json),
  [`lsp-raising.json`](../conformance/rule-stack/lsp-raising.json) and
  [`lsp-crlf.json`](../conformance/rule-stack/lsp-crlf.json): 14 documents opened in both
  generated servers over real stdio (`RuleStackLspConformanceTest`, Rust with
  `-DrustConformance=true`), comparing `data.kind`, `data.ruleStack`, `ruleStackOmitted` and every
  `relatedInformation` message and multi-line range. The 9 TinyCalc documents include trailing
  input, 37 nested rules with 5 omitted, a comment holding a supplementary character before the
  error (both versions start `Expression` after the comment) and a declaration on a CRLF line
  before it. The 3 documents of a grammar with UBNF `COMMIT` / `LABEL` (#710) cover a committed
  error, a labeled failure and an ordinary failure after committed statements; the 2 CRLF
  documents a frame and a failure that start at the LF of a CRLF.
- Successful parses: `successfulParsesReportNoStackButUnconsumedInputDoes` /
  `successful_parses_report_no_stack_but_unconsumed_input_does` (and, in Rust, the full-input
  entry), and `PropertyBasedParityTest`, which observes the context-level stack of every
  generated case in both versions and requires it to be absent with `DETAILED_ON_FAILURE` and
  identical across memoization, `AUTO` / `DETAILED` and the cursor reset.

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
