//! Native UBNF -> normalized IR -> Rust modules. No Java process is launched.
pub mod adapters;
pub mod impact;
mod lexical;
pub mod lowering;
pub mod modules;
pub mod packages;
pub mod playground;
pub mod portability;
mod token_stream;

/// Parse and validate the complete grammar before producing any artifacts.
pub fn generate(source: &str) -> Result<Vec<unlaxer_codegen::GeneratedFile>, String> {
    let file = unlaxer_ubnf::parse(source).map_err(|error| error.to_string())?;
    generate_ast(&file)
}

pub fn generate_file(
    path: &std::path::Path,
) -> Result<Vec<unlaxer_codegen::GeneratedFile>, String> {
    generate_ast(&modules::load(path)?)
}

fn generate_ast(
    file: &unlaxer_ubnf::UbnfFile,
) -> Result<Vec<unlaxer_codegen::GeneratedFile>, String> {
    if file.grammars.len() != 1 {
        return Err("Rust generation requires exactly one grammar".into());
    }
    generate_grammar(&file.grammars[0])
}
pub(crate) fn generate_grammar(
    grammar: &unlaxer_ubnf::GrammarDecl,
) -> Result<Vec<unlaxer_codegen::GeneratedFile>, String> {
    let ir = lowering::lower(grammar)?;
    let mut files = unlaxer_codegen::generate(&ir).map_err(|error| error.to_string())?;
    if token_stream::enabled(grammar) {
        let api = unlaxer_codegen::lexing_api(
            &token_stream::terminals(grammar)?,
            ir.root,
            ir.java_whitespace,
        );
        files
            .iter_mut()
            .find(|f| f.relative_path == "parser.rs")
            .unwrap()
            .content
            .push_str(&api);
    }
    Ok(files)
}
