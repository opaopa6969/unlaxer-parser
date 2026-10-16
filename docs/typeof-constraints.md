# Mapping-time `@typeof` constraints

Java and both Rust generation hosts implement the same mapping-time constraint:

```ubnf
@mapping(Pair, params=[left,right])
Pair ::= Item @left '=' @typeof(left) Item @right;
```

Parsing constructs the CST normally. Mapping binds the capture values, then compares
`right`'s semantic type with `left`'s. An incompatible pair is a mapping error, not a
syntax failure or an alternative that the parser can retry. Java throws
`IllegalArgumentException`; Rust returns `Err(String)`. Successful mapping continues
to the generated evaluator without changing the value or its source span.

## Type identity

| Capture value | Compared identity |
| --- | --- |
| Text | Java `String` / Rust text |
| Typed number capture | Java `Integer` / Rust `i32` |
| Mapped node | Concrete mapping type, including the complete family path |
| Enum | Declaring enum type; individual constants do not change the type |
| Mixed enum occurrence | The underlying declaring enum type, rather than the occurrence wrapper |
| Optional | `Optional`, including an empty optional |
| Repeated capture | `List`, including an empty list |

Container contents are not recursively compared. For example, an optional enum and
an optional text value have the same type for this constraint. Java list identities
are normalized to `List.class`; the result does not depend on a JDK collection
implementation. Two different node rules sharing one concrete `@mapping` type have
the same identity even if their captured enum values differ. Transparent helpers
preserve the mapped value's identity. A `@skip` helper projects text and therefore
compares as text. Numeric refinement follows the existing typed-number contract;
a numeric-looking text alias is still text.

Java retains the null guard: a comparison with a null value is not performed.
Ordinary generated optional and repeated fields represent absence as an empty
container rather than null. Rust uses its existing required-value mapping failure
for malformed/incomplete CSTs; this does not introduce nullable Rust AST fields.

## Binding and rejection

All public fields are bound in constructor order before comparison-only private
locals, then the declarations are checked in source order. This also fixes the
failure priority when multiple child mappers would fail. Forward references work.
A capture used only for comparison may be absent from `@mapping(..., params=[...])`;
it becomes a private mapping local and does not change the AST or evaluator API.
Private locals use the owning rule's type; only published constructor fields use
the shared schema when several rules share one mapping.
A constraint does not publish a hidden capture's value or add an AST field.

Each declaration is checked, including multiple declarations for the same own
capture and declarations nested in groups or containers. Constraints require a
non-skipped, non-enum `@mapping` owner. Both hosts reject these cases before code
emission:

* `E-TYPEOF-MAPPING`: the owning rule has no usable mapping.
* `E-TYPEOF-MISSING-CAPTURE`: the annotated element has no own capture.
* `E-TYPEOF-UNKNOWN-CAPTURE`: the referenced name does not occur in the owning rule.

This extends Java's previous validator, which only visited some element shapes
and required comparison-only values to be public constructor parameters. The
frontend also recognizes a prefix `@typeof` following another element without
misreading it as that previous element's postfix capture. Nested annotations stay
on their own element instead of being copied onto an enclosing group. A directly
parsed prefix annotation now has source origin `SOURCE`, rather than the
`REWRITTEN` origin previously assigned by the legacy misparse repair; its declared
annotation/capture/source spans remain intact.

## Errors and locations

A mismatch reports the own capture's code-point interval, for example:

```text
@typeof constraint violated: right must be same type as left, expected Mode but got Heat at CP 5:8
```

For a repeated capture, the interval is the union of its occurrence intervals.
An empty optional/list has no value occurrence in the existing capture-binding
CST, so its error falls back to the owning rule's code-point interval. Type labels
use the Java simple name for display; actual family comparison retains the full
mapping identity. Rust identities also carry a value-kind namespace: a mapped
node called `String` or `Integer` is distinct from the scalar type of that name. Supplementary characters and combining sequences do not change
the code-point coordinate contract.

Typed nested captures use the same shallow identity and empty-container location rules;
see [nested typeof](nested-typeof.md). Their explicit CST boundaries preserve nested
shape without reparsing or flattening.

## Generated IR and verification

The Rust code-generation IR now has `Rule.type_constraints: Vec<TypeConstraint>`.
Each constraint records `capture` and `reference` as `Field` values, including
cardinality and enum identity. Direct IR clients must add
`type_constraints: vec![]` when constructing existing rule literals. The validator
checks owner, capture existence, enum identity, and consistency with public mapping
fields. A mapped sum retains its constraints on a transparent IR rule: its body
selects a single mapped child, and successful checking returns that child without
creating a synthetic AST wrapper. Direct IR clients may use this same restricted
projection shape; skipped/enum guards and arbitrary multi-child transparent guards
are rejected. The Java IR exposes the equivalent `Rule.typeConstraints()` and retains
its older convenience constructors with an empty constraint list.

`spec-corpus/typeof` contains handwritten expectations, not snapshots produced by
either implementation. `TypeofConformanceTest` compiles and runs generated Java
parsers/mappers/evaluators, compares all five Rust files from both generation hosts
byte for byte, then compiles and runs the generated Rust consumer. Every input is
checked with memoization off/on and all three diagnostic modes. The corpus covers
syntax rollback, mapping-stage failures, private/forward bindings, container and
shared schemas, constrained mapped sums, left/right associative rules, families,
mixed enum/text/node values, and typed numbers. The
positive frontend corpus separately compares annotation ASTs and source spans.
Java 21 and Rust 1.85 are the baseline; the implementation adds no Java 17-specific
abstraction.

Run from the repository root with both toolchains on `PATH`:

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=TypeofConformanceTest,RustUbnfFrontendConformanceTest,RustUbnfSourceConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-codegen -p unlaxer-generator -p unlaxer-learning
```

The opt-in test writes `unlaxer-dsl/target/typeof-java.tsv` and
`unlaxer-dsl/target/typeof-conformance.tsv`; a skipped Rust test is not parity evidence.

The separate browser lesson interpreter (`unlaxer-learning`) is an explicit
teaching subset with its own projection implementation. It rejects `@typeof` at
compile time and points users to generated CLI / VS Code / Playground mappers;
it must never accept the annotation and silently omit its checks. This restriction
does not apply to either Rust code-generation host or the generated Playground.
