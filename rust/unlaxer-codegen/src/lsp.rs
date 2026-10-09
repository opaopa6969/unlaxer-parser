//! Opt-in LSP module; the default five-file generation contract is unchanged.
use crate::{quote, Expression, GeneratedFile, GrammarIr, HEADER};
const KEYWORDS: &str = "grammar token @root @mapping @whitespace @interleave @backref @typeof @scopeTree @leftAssoc @rightAssoc @longestChoice @predictiveChoice @precedence @declares @catalog params level profile name mode symbol context description";
pub fn generate(ir: &GrammarIr, name: &str) -> GeneratedFile {
    let mut keywords: Vec<String> = KEYWORDS.split(' ').map(str::to_owned).collect();
    for rule in &ir.rules {
        collect(&rule.body, &mut keywords);
    }
    GeneratedFile { relative_path: "lsp.rs".into(), content: format!("{HEADER}\n/// Intrinsic Classic grammar adapter; implement Backend for additional typed/provider queries.\npub fn backend() -> unlaxer_lsp::GrammarBackend {{\n    unlaxer_lsp::GrammarBackend {{\n        name: {}.into(), entry: {}.into(),\n        grammar: std::sync::Arc::clone(super::parser::grammar()),\n        root: {}, whitespace: {},\n        keywords: vec![{}],\n    }}\n}}\n\npub fn serve_stdio() -> std::io::Result<()> {{\n    unlaxer_lsp::Server::new(backend()).serve_stdio()\n}}\n", quote(name),quote(&ir.rules[ir.root].name),ir.root,ir.java_whitespace,keywords.iter().map(|k| format!("{}.into()",quote(k))).collect::<Vec<_>>().join(", ")) }
}
fn collect(expression: &Expression, keywords: &mut Vec<String>) {
    use Expression::*;
    match expression {
        Literal(text) => {
            if !keywords.contains(text) {
                keywords.push(text.clone());
            }
        }
        Sequence(children) | Choice(children) | LongestChoice(children) => {
            for child in children {
                collect(child, keywords);
            }
        }
        PredictiveChoice { alternatives, .. } => {
            for child in alternatives {
                collect(child, keywords);
            }
        }
        RuleEffects { child, .. }
        | CaptureEquality { child, .. }
        | Recovery { child, .. }
        | OptionalExpr(child)
        | Repeat { child, .. }
        | Delimited(child)
        | TextValue(child)
        | ValueBoundary(child)
        | TriviaScope { child, .. }
        | LexicalTriviaScope { child, .. } => collect(child, keywords),
        Capture { expression, .. } => collect(expression, keywords),
        Separated { child, separator } => {
            collect(child, keywords);
            collect(separator, keywords);
        }
        _ => {}
    }
}
