// The shared generated module also contains APIs for the other server entry point.
#[allow(dead_code, clippy::manual_range_patterns)]
mod generated;
fn main() -> std::io::Result<()> {
    std::process::exit(generated::lsp::serve_stdio_status()?);
}
