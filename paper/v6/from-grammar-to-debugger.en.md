# From Grammar to Debugger: Generating a Consistent DSL Toolchain with Typed ASTs and Source-Preserving Evaluation

Tool Paper draft v6; not a submitted or accepted publication. [Japanese](from-grammar-to-debugger.ja.md) / [Artifact and design](artifact.md) / [Errata](errata.md). The historical review dialogues are simulated reviews for design improvement, not independent conference reviews.

## Abstract

Changes to a domain-specific language can leave its parser, AST, mapper, evaluator, editor services, and debugger inconsistent. unlaxer-parser combines a Java 21 parser-combinator runtime with a UBNF generator to derive the structural parts of these components from an annotated grammar. Its central design generates sealed AST types, mapping code, exhaustive evaluator dispatch, and abstract evaluation methods from `@mapping`, while keeping handwritten semantics in a separate subclass. Source positions are associated with AST identities and can be consulted during evaluation. Generated LSP and DAP infrastructure shares the parser.

A bounded language-evolution experiment adds an operator, a negation node, and a conditional node. At each stage, eight generated Java files compile and run. Stale dispatch and missing concrete evaluation methods are rejected when new AST types are introduced; a missing string-valued operator implementation is not. Each change modifies two handwritten files, adding two to six lines and removing one. These are fixture-specific change counts, not comparative productivity measurements. We also implement result-owned source-map snapshots that survive subsequent parses. The contribution is a reproducible account of structural consistency checks and their limits in a generated toolchain, rather than a new formal-language result or a performance superiority claim.

## 1. Problem and questions

A parser may accept newly introduced syntax while an evaluator still handles the old language. AST constructors, mapping rules, completion candidates, and debugger positions can also diverge. Language workbenches already address this problem broadly; unified generation alone is not claimed as novel. We study a compact maintenance workflow using Java compilation and regeneration.

Our claim is that an annotated grammar can serve as a structural specification from which some inconsistencies involving new AST types become compiler errors, while the generated parser is shared with editor and debugger infrastructure. The grammar does not supply all application semantics.

- RQ1: Which parts are generated, and which remain handwritten?
- RQ2: What changes and inconsistencies arise during language evolution, and which are detected?
- RQ3: Can an artifact reproduce the integration of parser, mapper, evaluator, LSP, and DAP? What evidence remains necessary for practical performance and deployment claims?

## 2. Architecture and guarantees

`unlaxer-common` provides Token, Source, ParseContext, and combinators. `unlaxer-dsl` parses UBNF and invokes the generators. The experiment uses `ParserGenerator`, `ASTGenerator`, `MapperGenerator`, `EvaluatorGenerator`, `LSPGenerator`, `DAPGenerator`, and the two protocol launcher generators: six major artifact kinds, eight Java source files in total.

| Artifact | Generated structure | Handwritten or outside the guarantee |
|---|---|---|
| Parser | Rules, choices, captures, token-parser references | External token parsers and language-specific constraints |
| AST | Sealed interface and records for `@mapping` | Some heterogeneous choices produce `Object` fields requiring casts |
| Mapper | Token-to-record mapping and node source spans | Compilation for arbitrary UBNF; spans for all synthetic nodes |
| Evaluator | Sealed switch, `evalXxx` contracts, visit events | Semantics, value representation, environment, exception policy |
| LSP | Parser-based editor infrastructure, including keyword completion | Language-specific type checks, semantic diagnostics, hooks |
| DAP | Token/AST traversal, steps, stack frames, source display | Connection to semantic execution and its environment |

The Generation Gap Pattern separates a generated evaluator base from a handwritten concrete subclass. Regeneration does not overwrite the subclass. Adding `@mapping(Negation, ...)` exposes two different failures:

1. A new AST compiled with the old evaluator base makes its sealed switch non-exhaustive.
2. Regenerating both AST and evaluator updates the switch, but the old concrete subclass lacks the new abstract `evalNegation` method.

These are structural checks when the affected sources are recompiled together. They do not establish semantic correctness, validate casts from `Object`, protect mixed stale binaries, or detect new string-valued operators. An `@eval` annotation generating a concrete implementation can also remove the obligation to implement an abstract method.

The current annotation syntax includes `@eval(kind='conditional', strategy='default')`. Implemented kinds are `binary_arithmetic`, `variable_ref`, `conditional`, `passthrough`, and `literal`. They rely on expected field shapes and value types; they do not synthesize arbitrary semantics. The experiment deliberately uses handwritten evaluation methods.

### Source-preserving evaluation

Tokens retain source information, but generated AST records do not contain position fields. Previously the mapper kept an identity-indexed static span table, cleared by the next mapping operation. Parsing document B could therefore invalidate position lookup for an AST retained from document A.

We add `parseWithSourceMap(String)` and `mapParsedTokenWithSourceMap(Token)`, returning `SourceMappedAst<T>`. Each result owns an immutable snapshot of node spans. Identity lookup distinguishes structurally equal records representing different occurrences. Arrays are copied when stored and retrieved. Public mapping operations and snapshot creation use the same class monitor, preventing another public mapping operation from intervening. Existing entry points and the legacy latest-mapping lookup remain available.

Spans are half-open intervals measured in Unicode code points. We correct the previous mixture of code-point starts and UTF-16 lengths. Conversion to Java string indices or LSP UTF-16 positions belongs at the API boundary. Synthetic nodes without a recorded origin return an empty optional. Snapshot storage is linear in the number of recorded nodes. Synchronization serializes calls to one generated mapper; it is not a claim that the entire parsing runtime is thread-safe.

Evaluator `DebugStrategy.onEnter/onExit` and `StepCounterStrategy` observe actual evaluation calls. A retained snapshot resolves their positions even after another parse. The generated DAP, however, traverses token or AST structure; it is not automatically connected to semantic execution. In particular, stepping through an AST is not evidence that the debugger skips the unselected branch of a conditional. Connecting semantic execution requires language-specific integration. The standard DAP still uses the legacy mapper lookup; concurrent multi-document debugging is outside this extension's guarantee.

## 3. Language-evolution experiment

All grammar and handwritten evaluator inputs are fixed under `unlaxer-dsl/src/test/resources/evolution/{0,1,2,3}/`. The baseline accepts numbers and binary addition. Stage 1 adds multiplication, stage 2 adds `neg(...)`, and stage 3 adds `if(condition,then,else)`, with zero interpreted as false. Binary operands are deliberately limited to numeric literals in this small experiment.

At each stage we generate and compile all eight source files, parse input with the generated mapper, and run the concrete evaluator. Inputs from earlier stages are rerun. We deliberately combine the new AST with stale dispatch, and regenerated sources with a stale concrete evaluator. Assertions inspect the diagnostic category and missing method name, so an unrelated Java error cannot count as evidence of exhaustiveness checking. The operator change includes a negative control that compiles successfully but throws at runtime.

| Stage | Handwritten files changed | Lines added/deleted | Generated files changed/total | Result with stale code |
|---|---:|---:|---:|---|
| 0: baseline | 2 | 25/0 | 8/8 | Compiles and evaluates |
| 1: `*` | 2 | 2/1 | 3/8 | Old semantics compiles, fails at runtime |
| 2: Negation | 2 | 6/1 | 5/8 | Non-exhaustive switch / missing `evalNegation` |
| 3: Conditional | 2 | 6/1 | 5/8 | Non-exhaustive switch / missing `evalConditional` |

Line counts use an LCS diff including blank lines; the baseline is counted against empty files. Generator implementation and common test-harness effort are excluded. Regenerated files need not change textually: LSP and DAP may acquire new syntax through recompilation against updated parser and mapper classes.

The [artifact guide](artifact.md) specifies the commands and output paths. `unlaxer-dsl/target/language-evolution.tsv` reports counts and machine elapsed time for generation, compilation, and checks. This is not human development time or a throughput benchmark. We do not compare it with the earlier draft's estimated eight-week baseline or claim a thirteenfold productivity improvement.

Four new JUnit methods implement the artifact: one for algebra, two for the copy language, and one executing all four evolution stages. LSP tests initialize the generated server, open a document, and request completion of introduced keywords. DAP tests initialize the generated adapter, launch a temporary program in AST mode, invoke configurationDone, inspect stackTrace, and call next. Entry and step events and the introduced AST type in the stack frame are checked. These are direct service API tests, not JSON-RPC transport or editor usability tests.

Source-map checks cover every evaluation-visited node in the fixture, retained lookup after another parse, distinct equal-valued nodes, defensive array copies, a span containing a non-BMP character, and mapping an existing Token. They establish integration for this fixture, not semantic correctness or span coverage for every grammar shape.

## 4. Related work

Xtext supplies infrastructure for parsing, linking, compilation or interpretation, and editor support, including LSP. Xbase supports Java integration, code generation, and debugging. These systems should not be described as lacking semantic or IDE facilities. [Xtext/Xbase documentation](https://eclipse.dev/Xtext/), [Xtext LSP documentation](https://eclipse.dev/Xtext/documentation/340_lsp_support.html).

Spoofax combines syntax definition with transformations and static semantics, and includes DynSem for dynamic semantics; semantic specification and generation are not unique to unlaxer. [Spoofax DynSem introduction](https://spoofax.dev/release/note/2.1.0/). Langium generates TypeScript AST definitions from a grammar and builds LSP-based language infrastructure. [Langium features](https://langium.org/docs/features/).

Our design point combines Java 21 sealed dispatch and abstract-method extension points with Token-derived source information and generated LSP/DAP infrastructure in one Java project. This is a comparison of architecture, not a ranking of functionality, performance, or effort. The generated/handwritten boundary is explicit in Section 2. Existing workbenches may achieve analogous consistency checks through their own type systems and validation mechanisms. The cited primary documentation was consulted for this revision; we make no unsupported claims about absent features in other tools.

## 5. Validity and limitations

For RQ1, six infrastructure kinds are generated, while application semantics and specialized IDE/debugging behavior remain handwritten. For RQ2, the artifact reproduces three fixture changes and two compile-time failure modes for new node types. String-valued operators do not receive the same protection. For RQ3, generated compilation, values, spans, completions, and DAP service calls are exercised. Practical performance requires additional JMH measurements, representative inputs, editing workloads, multi-document scenarios, and tests through transport and actual editors.

The experiment is small and author-built, with no competing implementation, independent participant, or controlled effort log. The fixture uses a `Digits ::= NUMBER` rule to capture numeric text and explicit casts for heterogeneous choices. Direct numeric-token capture and parser-name collisions have separate implementation constraints; the experiment does not establish correctness for those paths. Synchronization trades concurrency for consistent snapshots within one mapper.

The earlier draft reported tinyexpression deployment in an environment processing a billion transactions per month, five execution backends, and 445 tests. This artifact does not independently audit that deployment, establish backend parity, or demonstrate completion of the P4 migration. We do not reuse its combined historical test count as a current measurement. Earlier custom speedups and cache hit rates are also excluded from this evaluation.

DGE dialogues and gap inventories remain development records. Without controls for investigation time, independent gap classification, and a comparison process, finding over 201 gaps cannot establish a causal effect of DGE. The simulated review itself missed the palindrome and algebra errors, motivating independent enumeration and primary-source checking. Methodology effectiveness is outside the results of this Tool Paper.

## 6. Conclusion

unlaxer-parser links annotated grammar structure, generated Java types, and handwritten semantics so that some language-evolution inconsistencies become compiler errors. The v6 artifact demonstrates both the protection and its limits, and introduces source-map lifetime tied to a retained mapping result. These are reproducible implementation insights suitable for a Tool Paper. Larger productivity and performance studies remain future work.

## Appendix A. Palindromes and the copy language

`{ww^R | w∈{a,b}*}` contains even-length palindromes and has the CFG `S → aSa | bSb | ε`. Adding `S → a | b` admits odd lengths. The earlier examples `a` and `abcba` did not satisfy the even-length-only definition. [Columbia CFG lecture](https://www.cs.columbia.edu/~aho/cs3261/Lectures/L8-PDA.html).

The replacement example is `Lcopy={w#w | w∈{a,b}*}`. A nonempty `w` is captured and replayed in its original order with `MatchedTokenParser`; the empty case uses an explicit `#` alternative because the parser skips empty captures. Acceptance requires consuming the entire input. `CopyLanguageTest` checks every string over `{a,b,#}` of lengths zero through seven: 3,280 inputs against an independent equality oracle. Additional cases cover longer words, reversal, trailing input, and out-of-alphabet characters.

Finite tests do not prove non-context-freeness. Context-free languages are closed under homomorphism, while erasing `#` maps `Lcopy` to the non-context-free binary copy language `{ww}`; thus `Lcopy` is not context-free. [Copy-language non-context-freeness and closure properties](https://www.cs.columbia.edu/~aho/cs4115/Lectures/15-02-11.html). This is not a separation result against general PEGs. PEG expressiveness must not be conflated with context-free expressiveness. [Ford 2004](https://bford.info/pub/lang/peg/).

## Appendix B. Eight-element propagation-parameter model

On `S={M,C}×{F,T}`, define `Id=(t,b)`, `AllStop=(C,F)`, `DoConsume=(C,b)`, `StopInvert=(t,F)`, and `NotProp=(t,!b)`. Composition `f . g` applies g first, then f. The first coordinate has id or const C; the second has id, not, const F, or const T, giving at most eight maps. `ConsumeNot=DoConsume . NotProp`, `ForceInvert=NotProp . StopInvert`, and `AllTrue=DoConsume . ForceInvert` generate the remaining maps, establishing exactly eight.

`AllStop . X = AllStop` defines a left zero. Since `NotProp . AllStop = AllTrue`, it is not a right zero. `AllTrue` is also a left zero; there is no two-sided zero. The [complete generated table](propagation-table.md) lists all 64 products. `PropagationAlgebraTest` checks closure, identity, noncommutativity, and all 512 associativity instances.

This abstracts parameter transformations, not contextual equivalence of complete parsers with state, failure, rollback, or virtual tokens. Actual `TokenKind` has four values, including virtual-token modes. There is no `AllPropagationStopper` class in the inspected tree: `AllStop` names the composition of the consume and inversion-reset effects in this model.
