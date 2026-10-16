# Common-field oracle

The source, input, expected accessor evaluation, and invalid source CP ranges are
hand-authored independently of generated results. `java` and `rust` are tiny
consumer expressions compiled against the generated typed API; they are not
expected output snapshots. Valid source preserves supplementary and combining
characters as written. Numeric values intentionally use Java's existing `int`
and Rust's existing source `String` before evaluating the same arithmetic.

Run `CommonFieldConformanceTest -DrustConformance=true` with Java21 and Rust1.85,
and `cargo test -p unlaxer-generator --test common_fields` from `rust/`.
