//! Opt-in typed AST DAP module; ordinary parser generation stays unchanged.
use crate::{mappings, quote, Cardinality, GeneratedFile, GrammarIr, Kind, HEADER};
use std::fmt::Write;

pub fn generate(ir: &GrammarIr) -> GeneratedFile {
    let mut out = format!("{HEADER}\n/// Strict typed AST nodes in mapping-field declaration order.\npub fn steps(source: &str) -> Result<Vec<unlaxer_dap::Step>, String> {{\n    let tree = super::parser::parse_tree(source).map_err(|error| format!(\"{{error:?}}\"))?;\n    let ast = super::mapper::map(&tree)?;\n");
    let mappings = mappings(ir);
    if mappings.is_empty() {
        out.push_str("    match ast {}\n}\n");
    } else {
        out.push_str("    let mut out = Vec::new();\n    collect(&ast, &mut out);\n    Ok(out)\n}\n\nfn collect(node: &super::ast::Ast, out: &mut Vec<unlaxer_dap::Step>) {\n    match node {\n");
        for mapping in mappings {
            write!(out, "        super::ast::Ast::r#{} {{ span", mapping.name).unwrap();
            for field in &mapping.fields {
                if matches!(field.kind, Kind::Node | Kind::Value) {
                    write!(out, ", r#{}", field.name).unwrap();
                }
            }
            out.push_str(", .. } => {\n");
            writeln!(
                out,
                "            out.push(unlaxer_dap::Step {{ label: {}.into(), span: *span }});",
                quote(&mapping.name)
            )
            .unwrap();
            for field in &mapping.fields {
                if !matches!(field.kind, Kind::Node | Kind::Value) {
                    continue;
                }
                let name = format!("r#{}", field.name);
                let body: &str = if field.kind == Kind::Node {
                    "collect(child, out);"
                } else {
                    "if let super::ast::AstValue::Node(child) = child { collect(child, out); }"
                };
                match field.cardinality {
                    Cardinality::One => {
                        writeln!(out, "            {{ let child = {name}; {body} }}").unwrap()
                    }
                    Cardinality::Optional => {
                        writeln!(out, "            if let Some(child) = {name} {{ {body} }}")
                            .unwrap()
                    }
                    Cardinality::Many => {
                        writeln!(out, "            for child in {name} {{ {body} }}").unwrap()
                    }
                }
            }
            out.push_str("        },\n");
        }
        out.push_str("    }\n}\n");
    }
    out.push_str("\n/// Real committed terminal trace; a bounded metadata failure is never a CST fallback.\npub fn token_steps(source: &str) -> Result<Vec<unlaxer_dap::Step>, String> {\n    let options = unlaxer_runtime::ParseOptions::default().with_token_trace(unlaxer_runtime::token_trace::Limits::default());\n    let tree = super::parser::parse_tree_with_options(source, options).map_err(|error| format!(\"{error:?}\"))?;\n    unlaxer_dap::token_steps(&tree)\n}\n\npub fn backend() -> unlaxer_dap::ParserBackend {\n    unlaxer_dap::ParserBackend { mapper: steps, tokens: token_steps }\n}\n\npub fn serve_stdio() -> std::io::Result<()> {\n    unlaxer_dap::Server::new(backend()).serve_stdio()\n}\n");
    GeneratedFile {
        relative_path: "dap.rs".into(),
        content: out,
    }
}
