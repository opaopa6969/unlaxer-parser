# Literal enum values in generated Java and Rust

Issue [#537](https://github.com/opaopa6969/unlaxer-parser/issues/537) adds the same
literal enum contract to the Java target and the Java-host/native-host Rust targets.
The Java generation and evaluator baseline is JDK21.

```ubnf
grammar Config {
  @package: example
  @whitespace: none
  @enum
  Mode ::= 'sync' | 'auto';
  @root @mapping(Value, params=[mode])
  Root ::= Mode @mode;
}
```

The Java field is `ConfigAST.Mode`; the Rust field is `ast::Mode`. Optional and
repeated captures use `Optional<Mode>` / `List<Mode>` and `Option<Mode>` /
`Vec<Mode>`, respectively. The same rule applies through transparent aliases,
groups, `+`, bounded repetition and separated lists. Rust evaluation receives
`&Mode`, `Option<&Mode>` or `&[Mode]`; Java evaluation receives the generated record.
Both generators preserve enum type identity rather than silently projecting it
to `String`. Multiple different enum fields in one mapped node are supported.

Each alternative must contain exactly one uncaptured literal. Matching is exact,
case-sensitive and follows the grammar's ordinary choice policy. No case folding,
normalization, quote stripping or trimming is applied to an enum's value. Literal
spaces, supplementary characters, ZWJ families, quotes, backslashes and line
terminators remain part of the original value. Java `Mode.fromText(String)` and
Rust `Mode::from_text(&str)` accept exactly those literal spellings; unknown text
produces an error.

## Names and validation

Both hosts compute names without consulting a JDK or Rust Unicode case table:

- ASCII lowercase becomes uppercase. An ASCII uppercase letter after a lowercase
  letter inserts `_`, preserving the existing `camelCase` → `CAMEL_CASE` rule.
- ASCII `-`, `_` and space become `_`, preserving `kebab-case` → `KEBAB_CASE`.
- Every other scalar becomes `U<uppercase hexadecimal>_`; an underscore is added
  before that escape when it follows existing output. Thus `日本` becomes
  `U65E5__U672C_`, `𐐨` becomes `U10428_`, and `a"b` becomes `A_U22_B`.

The resulting constant must match `[A-Z_][A-Z0-9_]*` and must not be `_`.
Empty literals, numeric-leading names, repeated literals, colliding constant
names, unpaired Java surrogates, nonliteral alternatives and captured enum
alternatives are rejected before AST/mapper emission with `E-ENUM` diagnostics.
Escaping is intentionally not assumed injective: `😀` and `U1F600_` collide and are
rejected when declared together. Enum type names use the portable ASCII
identifier contract and reject the fixed Java/Rust keyword/restricted-name set
and names that shadow generated support types/imports. Enum/mapped type and
root-wrapper name collisions are rejected. Root wrapper callbacks must also remain
unique after the portable Rust snake-case conversion (for example, `FooBarNode`
and `Foo_BarNode` collide). `java` is reserved to prevent generated fully qualified
standard-library references from being shadowed. `@skip`, `@eval`,
`@commonField` and duplicate `@enum` on the enum declaration are conflicting.
A legacy redundant `@mapping` on an enum is ignored; its type name remains the
rule name and it does not create a second AST record.

These checks apply to unused enum declarations too. The portability check now
recognizes `@enum`; invalid declarations fail structural validation with a source
span. Its other unsupported annotations continue to be inventoried independently.

## Root values, positions and ownership

For an enum projected at the root before an explicit mapped boundary, Java emits
`ModeNode(Mode value) implements ConfigAST`; Rust emits
`Ast::ModeNode { span: Span, value: Mode }`. The wrapper participates in normal
mapper selection, evaluator dispatch and API impact reporting. Its source span
is the selected enum rule's span, including when reached through an unmapped
root alias. An enum captured inside an explicitly mapped parent stays a typed
field and does not introduce an extra semantic callback.

Java enum constants are singletons. They cannot identify an occurrence, and
`SourceMappedAst.sourceSpanOf(enumConstant)` is empty. Use the retained CST rule
or capture for each field occurrence's raw text and CP span. Repeated `SYNC`
values may have distinct source ranges. Root wrappers and ordinary mapped nodes
use the existing retained source-map snapshot. Rust enum values likewise have
no occurrence span; the owning AST wrapper/parent and retained `Tree` supply
node/capture positions. Both targets retain the original input; CP positions
convert to UTF-16/UTF-8 through their source snapshot APIs.

An added constant invalidates an old exhaustive Java21 `switch` or Rust `match`.
The conformance test actually compiles these stale clients and requires
`not.exhaustive` / `E0004`. Arithmetic or another interpretation of the enum
remains application semantics; enum generation does not add automatic `@eval`.

## Migration and staged scope

Before this change Java generated `Mode` in the mapper but `String` in the AST
field, causing real generated-code compilation to fail. Scalar/optional/list
fields now agree on the enum type. An enum-only root previously emitted an empty
AST marker and now emits its enum and source-positioned root wrapper. Quote and
backslash literals now produce escaped generated source.

Non-ASCII constant names formerly depended on `Character.toUpperCase(char)`:
for example the old name `日本` becomes `U65E5__U672C_`, and `é`/`É` produce
`UE9_`/`UC9_` respectively instead of both producing `É`. This deliberate naming
change avoids host Unicode-version differences and surrogate splitting. Existing
valid ASCII camel/kebab names are unchanged. Update handwritten constant
references when regenerating non-ASCII enum APIs.

## Mixed values

[#539](https://github.com/opaopa6969/unlaxer-parser/issues/539) supports enum/Text,
enum/Node and different enum families in one capture, including scalar,
optional and repeated values and shared mapping fields. Java uses `Object`,
`Optional<Object>` or `List<Object>`: text stays `String`, mapped nodes retain
their generated record, and an enum occurrence is `GrammarAST.EnumValue` whose
`value()` retains the concrete enum type. Its identity-based source map entry
belongs to that occurrence. Equal enum values in repetition, later parses and
concurrent maps never share a mutable span. Pure single-enum fields retain their
typed API. Generated enums provide `text()` for the exact literal.

Rust `AstValue` adds a variant `EnumMode { value: Mode, span: Span }` for each enum
family (`Mode` here). `span()` retains the occurrence, and `canonical_json()`
includes `enum`, constant `value` and `span`. The concrete enum's `text()` returns
the literal. Semantics borrows the tagged value and can exhaustively match its
variants. Adding a family may therefore require a new consumer match arm.
Java reserves `EnumValue` for its occurrence wrapper in grammars containing enums;
both hosts reject collisions before emission.

A root choice may project one enum wrapper or one explicit mapped node. The same
enum used inside a mixed capture remains an occurrence value, even if that enum
also has a root wrapper. Explicit mapped and skipped boundaries are preserved.
A root that can produce raw text, multiple nodes or an absent node still needs an
explicit `@mapping`. Shared mapping field names, order and cardinality must agree;
only element types join. Different enum families are distinct types, not an
ambiguous grammar.

DAP AST stepping visits mapped nodes and enum root wrappers, not field-value
wrappers or enum singletons. The enum's original parser/token occurrences remain
available in token mode. Java `@typeof` compares underlying enum identity, not the
occurrence-wrapper class; Rust `@typeof` support, including mixed enums, remains
tracked by [#542](https://github.com/opaopa6969/unlaxer-parser/issues/542).

Nested container capture shapes and arbitrary Java capture rules
keep their existing separately documented limits. New Java enum value-shape analysis
rejects helper chains deeper than 128 rules with a controlled diagnostic; structural
analysis limits are implementation bounds, not guarantees of unbounded grammar depth.

## Verification

The independent corpus is
[`spec-corpus/enums/generated-cases.json`](../spec-corpus/enums/generated-cases.json)
and [`invalid-cases.json`](../spec-corpus/enums/invalid-cases.json). Expected enum
values, AST fields, evaluator values, raw occurrences and CP spans are handwritten
from each grammar, not regenerated from an emitter snapshot. The shared test
executes both memo policies and all three diagnostic policies, checks rollback,
UTF-16/UTF-8 boundaries, repeated values and retained Java source maps, and
compiles/runs generated parsers, mappers and handwritten semantics.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=EnumConformanceTest,MixedEnumConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-codegen -p unlaxer-generator
```

The opt-in native test also requires byte equality for the five modules and API
impact JSON from the Java-host and native-host Rust generators. Native generation
runs with an empty PATH and an invalid JAVA_HOME. Reports are written to
`unlaxer-dsl/target/enum-java.tsv` and `enum-conformance.tsv`. On a JDK below 21
this test declares the baseline assumption; it is not a successful Java21 parity
check. Without `-DrustConformance=true` the native portion is skipped.

The [mixed-value corpus](../spec-corpus/mixed-enums/README.md) adds nine grammar
shapes and 116 inputs under six parse configurations, native/Java-host output
agreement (including DAP), generated evaluator execution, retained/concurrent
source maps, root/capture composition, and an independent DAP label/span oracle.
API impact reports expose enum-to-mixed field widening in both targets.
