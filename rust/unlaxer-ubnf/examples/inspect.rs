//! Conformance probe: each UTF-8 file argument produces one JSON result.
#[path = "../tests/support/canonical.rs"]
mod canonical;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    for path in std::env::args().skip(1) {
        let source = std::fs::read_to_string(path)?;
        match unlaxer_ubnf::parse(&source) {
            Ok(file) => println!("{{\"ast\":{}}}", canonical::file(&file)),
            Err(error) => println!(
                "{{\"error\":{},\"span\":[{},{},{},{}],\"line\":{},\"column\":{}}}",
                canonical::quote(&error.message),
                error.span.byte_start,
                error.span.byte_end,
                error.span.codepoint_start,
                error.span.codepoint_end,
                error.line,
                error.column
            ),
        }
    }
    Ok(())
}
