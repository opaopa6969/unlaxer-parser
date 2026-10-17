# Enum conformance oracle

See [the contract](../../docs/enum-values.md). `generated-cases.json` fixes literal
values and field cardinalities separately from the generator implementation.
`ast`, `eval`, raw `enums` occurrences, CP spans and prefix/diagnostic expectations
are the oracle. `coordinates` uses code points, UTF-16 code units and UTF-8 bytes.
The family emoji remains seven code points even though it is one grapheme.

`invalid-cases.json` contains malformed enum declarations, constant/type/wrapper
name collisions, restricted type names and the explicitly deferred mixed joins
of #539. Both target generators must reject them before source emission, and
both portability checkers retain source positions for structural rejection.

Run `EnumConformanceTest -DrustConformance=true` on JDK21. The test builds the
native frontend independently, compares five generated modules and API impact
reports, compiles Java/Rust consumers and requires the shared values and source
positions under every memo/diagnostics configuration. It also verifies that a
new enum constant invalidates a stale exhaustive switch/match.
