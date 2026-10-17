# Numeric capture oracle

`cases.json` is hand-authored. Each case is `[input, stage, evaluatedValue, span]`:
`stage` is `ok`, `parse`, or `map`; span is an end-exclusive code-point range.
The Java/Rust consumer expressions compile against generated typed fields and
Semantics callbacks. Expected values and failures are not generated snapshots.

The corpus covers signed i32 bounds, leading sign/zero, overflow, fractional and
exponent syntax, NaN/Infinity, ASCII single-digit binding, supplementary input,
CRLF/trivia, scalar/optional/list/outer flat container/repeated capture sites,
and multiple rules sharing a mapping. The tests retain successful mappings
across later successful and failing maps. Run `NumericCaptureConformanceTest
-DrustConformance=true` with Java21/Rust1.85 and the native `numeric_fields` test.
