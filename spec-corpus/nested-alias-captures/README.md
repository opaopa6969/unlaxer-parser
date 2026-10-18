# Nested captures through transparent rule aliases (#583)

`cases.json` gives hand-written types, values, capture/leaf CP spans and preferred
candidates for `@nestedCaptures` fields that reach their containers through
unmapped, unannotated, non-recursive helper rules. Fixtures with `mirrors` reuse
the independent oracle of the equivalent in-place form from
`nested-mapped-leaves/cases.json`: an alias must be observationally identical to
writing its body at the reference. `DirectTrivia` / `AliasTrivia` pin the same
equivalence under `@whitespace: javaStyle`; their expected text keeps the trailing
trivia that the existing in-place form already reports on both hosts.

Covered: two-level alias chains, a choice helper kept as one text leaf, empty and
duplicate lists, separated lists with missing nullable i32 items, multi-element
groups inside an alias, combining sequences, mapped node / enum leaves, a
`@commonField` family, a `@typeof` comparison with a private capture, an owned
`CAPTURE` around an alias and bounded repetition. Each input also runs under
memoization 2 x diagnostics 3 on both hosts.

`invalid.json` lists the forms both hosts reject before emission with the same
`E-NESTED-SHAPE`, rule CP range and `detail` text (recursive helpers, `@skip`,
annotated helpers, helpers with captures, mixed choices and helpers anywhere inside a
mixed choice alternative, rule-local trivia, and two multi-field rows whose first
reported reason depends on checking fields in mapping-parameter order).
`depth-boundaries.json` places the shared schema depth limit inside alias chains,
and `failures.json` checks mapping failures and parse rollback through an alias.
The test class also pins that a choice outside every capture keeps its previous
lowering on both hosts, and that shared multi-element helpers validate in linear time.

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=NestedAliasCaptureConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```
