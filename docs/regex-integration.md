# REGEX tokens in generated Rust parsers

[#587](https://github.com/opaopa6969/unlaxer-parser/issues/587) connects the
[bounded core](regex-core.md) to the parser runtime and both UBNF generation hosts.
This is a stage of [#544](https://github.com/opaopa6969/unlaxer-parser/issues/544),
not complete Java Pattern compatibility. Unicode and advanced constructs remain
tracked by #585 and #586 and are never silently interpreted as another dialect.

```ubnf
grammar Example {
  token Name = REGEX('[a-zA-Z_][a-zA-Z0-9_]*')
  @root @mapping(Value, params=[text])
  Root ::= Name @text;
}
```

Java generation keeps the existing `RegexTokenParser` backed by Java `Pattern`,
including Java-valid constructs not yet supported in Rust. Rust generation validates
all declared patterns, including unused tokens, before emitting any files. The
Java-host validator and native-host compiler use the same supported/rejected corpus,
scalar rejection positions and bounded compile policies. Portability reports use
`P-REGEX-SYNTAX`, `P-REGEX-UNSUPPORTED` or `P-REGEX-LIMIT`, with a subject such as
`REGEX:Unsupported@2`. The offset in that subject is a scalar offset inside the
pattern; the diagnostic source span still identifies the UBNF token declaration.
Normalized Rust IR also validates `RegexToken` before emission.

`Expr::regex(pattern)` compiles once and returns a typed compile result. Its
`Expr::Regex` variant shares an immutable matcher through `Arc`; cloning expressions
or reusing generated `grammar()` does not recompile on every token. Generated
parsers construct matchers inside the existing grammar `OnceLock`. Callers can use
`Expr::regex_with_limits` to set an execution resource policy. The runtime remains
std-only and can be built directly with `rustc`; native codegen uses a workspace
path dependency on that same compiler, with no external regex dependency.

A parse context lazily caches an original-source suffix and passes scalar slices
to the matcher. The suffix is bounded by the execution input policy and can grow
geometrically when parsing retries at an earlier cursor; an already-consumed long
prefix is not allocated for a short tail. Repeated short tokens do not copy/scan
their entire remaining input on each call. Projected CST text is never used as matcher input. The entire remaining
source still counts toward the input limit even when only one scalar would match.

The matcher sees the remaining input at the token's peek cursor. Only the exact
`Consumed` kind peeks the consumed cursor; the other three kinds peek the matched
cursor. `Consumed` and `VirtualConsumed` advance the consumed cursor by the matched
scalar length. Match-only kinds advance only the matched cursor. Virtual kind and
input-backed source are independent. Source text, CP spans and UTF16/UTF8 boundaries
are preserved without normalization, including a family emoji's seven scalars.

At EOF or on a successful empty prefix, the token fails without producing a CST.
Inversion succeeds for one scalar only when the original prefix match fails.
MatchOnly, inversion propagation and both stoppers retain the existing Classic
contracts, including prior matched positions, transaction reset policy and the
separate returned original tokens versus exact-kind collected CST. They do not
become pure PEG lookahead. Failure rollback and diagnostic/memo modes follow the
existing runtime contracts; REGEX has a conservative unknown FIRST predictor.

A matcher resource error is sticky for the parse context and cannot be swallowed
by ordered choice, optional or repetition. Its owner transaction rolls back cursor,
CST, captures, scope, state and portable metadata. Detailed public parsing reports
`resource_limit`; further calls on that context fail with the same resource error.
A fresh context and the compiled matcher remain usable. Java's standard Pattern
adapter does not acquire the Rust VM's resource limits. Its uncontrolled stack/time
resource boundary remains an explicit unfinished item in #599.

`spec-corpus/regex-integration/modes.tsv` is an independent handwritten oracle for
109 Classic cases, tested under memoization 2 × diagnostics 3, with and without
Rust token traces. `generated-cases.json` specifies 18 grammars and 39 inputs with
handwritten prefix, coordinate, AST, evaluation and diagnostic expectations. Both
real generation hosts produce byte-identical Rust files, which are compiled and
run without a JVM; Java generated parsers/mappers/evaluators compile and run on
Java 21. The shared core corpus also fixes generation support and rejection sites.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RegexIntegrationConformanceTest,RegexTokenModesTest,RegexGeneratedConformanceTest,RegexCoreConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime --test regex_tokens
```

Reports are `target/regex-generated-{java,conformance}.tsv` and
`target/regex-validation-conformance.tsv` in `unlaxer-dsl`. A skipped opt-in check
is not a conformance result. External Java custom scanners and unsupported regex
constructs remain outside this core's coverage; the corresponding parity issues
remain open.
