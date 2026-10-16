# Common-field oracle

The source, input, expected accessor evaluation, and invalid source CP ranges are
hand-authored independently of generated results. `java` and `rust` are tiny
consumer expressions compiled against the generated typed API; they are not
expected output snapshots. Valid source preserves supplementary and combining
characters as written. Numeric values use Java's existing `int` and Rust's `i32` contracts before
evaluating the same arithmetic.

Run `CommonFieldConformanceTest -DrustConformance=true` with Java21 and Rust1.85,
and `cargo test -p unlaxer-generator --test common_fields` from `rust/`.

Mixed-field cases exercise flat/dotted families and multiple rules sharing a
concrete mapping. The fourth case column lists enum occurrence paths and literal
CP ranges: `[field, listIndex, start, end]`. Consumers preserve enum nominal type,
text/node alternatives, optional absence, list order, and distinct occurrence
identity even when the underlying enum constant repeats.
