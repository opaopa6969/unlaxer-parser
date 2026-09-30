//! Java/Rust source-position oracle. No imports, generation, or target-code execution.
#[path = "../tests/support/canonical.rs"]
mod canonical;
#[path = "../tests/support/source_spans.rs"]
mod source_spans;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    for path in std::env::args().skip(1) {
        let snapshot = unlaxer_ubnf::parse_with_source(std::fs::read_to_string(path)?)?;
        let rows = source_spans::file(&snapshot)
            .into_iter()
            .map(|(path, span)| {
                format!(
                    "{}:[{},{},{}]",
                    canonical::quote(&path),
                    span.codepoint_start,
                    span.codepoint_end,
                    canonical::quote(snapshot.slice(span).expect("source boundary")),
                )
            })
            .collect::<Vec<_>>();
        println!(
            "{{\"ast\":{},\"spans\":{{{}}}}}",
            canonical::file(snapshot.ast()),
            rows.join(",")
        );
    }
    Ok(())
}
