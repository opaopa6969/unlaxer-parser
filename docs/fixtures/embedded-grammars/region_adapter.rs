//! Copy into generated FormulaInfo Playground src/, alongside generated tiny/ and java/ modules.
#[path = "java/mod.rs"]
mod java;
#[path = "tiny/mod.rs"]
mod tiny;
use std::collections::HashMap;
use unlaxer_runtime::{
    editor_cst::Options,
    embedded::{self, Grammar},
    source::{Language, Snapshot},
};
fn language(id: &str, grammar: &str, entry: &str) -> Language {
    Language {
        id: id.into(),
        package_id: "example".into(),
        version: "1".into(),
        grammar: grammar.into(),
        entry: entry.into(),
    }
}
pub fn analyze(source: &str, cursor: usize, version: u64) -> Option<String> {
    let snapshot = Snapshot::new("playground", version, source).ok()?;
    let formula =
        crate::generated::parser::embedded_editor_grammar(vec!["}F".into()], Options::default());
    let tiny = tiny::parser::embedded_editor_grammar(vec!["]T".into()], Options::default());
    let java = java::parser::embedded_grammar();
    let root = language("formula", "FormulaInfo", "Document");
    let providers: HashMap<Language, &dyn Grammar> = HashMap::from([
        (root.clone(), &formula as &dyn Grammar),
        (
            language("tiny", "TinyExpression", "Expression"),
            &tiny as &dyn Grammar,
        ),
        (
            language("java", "Java", "CompilationUnit"),
            &java as &dyn Grammar,
        ),
    ]);
    let result = embedded::parse(&snapshot, &root, &providers, 8, 32).ok()?;
    let selected = result
        .tree()
        .ok()?
        .at(cursor)
        .ok()?
        .map(|r| unlaxer_runtime::json_string(&r.language.grammar))
        .unwrap_or_else(|| "null".into());
    let mut json = result.canonical_json();
    json.pop();
    Some(format!("{},\"selected\":{}}}", json, selected))
}
