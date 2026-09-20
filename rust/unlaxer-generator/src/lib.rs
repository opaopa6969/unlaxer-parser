//! Native UBNF -> normalized IR -> Rust modules. No Java process is launched.
pub mod lowering;

pub use unlaxer_codegen::{ExecutionTier, GenerateOptions};

/// Parse and validate the complete grammar before producing any artifacts.
pub fn generate(source: &str) -> Result<Vec<unlaxer_codegen::GeneratedFile>, String> {
    generate_with_options(source, GenerateOptions::default())
}

pub fn generate_with_options(
    source: &str,
    options: GenerateOptions,
) -> Result<Vec<unlaxer_codegen::GeneratedFile>, String> {
    let file = unlaxer_ubnf::parse(source).map_err(|error| error.to_string())?;
    if file.grammars.len() != 1 {
        return Err("Rust generation requires exactly one grammar".into());
    }
    let ir = lowering::lower(&file.grammars[0])?;
    unlaxer_codegen::generate_with_options(&ir, options).map_err(|error| error.to_string())
}
