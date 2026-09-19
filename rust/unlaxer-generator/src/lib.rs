//! Native UBNF -> normalized IR -> Rust modules. No Java process is launched.
pub mod lowering;

/// Parse and validate the complete grammar before producing any artifacts.
pub fn generate(source: &str) -> Result<Vec<unlaxer_codegen::GeneratedFile>, String> {
    let file = unlaxer_ubnf::parse(source).map_err(|error| error.to_string())?;
    if file.grammars.len() != 1 {
        return Err("Rust generation requires exactly one grammar".into());
    }
    let ir = lowering::lower(&file.grammars[0])?;
    unlaxer_codegen::generate(&ir).map_err(|error| error.to_string())
}
