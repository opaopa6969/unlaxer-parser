//! Read-only module source and verified identity snapshots used by authoring tools.
use serde_json::{json, Value};
use std::path::Path;

pub fn inspect(path: &Path) -> Result<Value, String> {
    let path = if path.is_absolute() {
        path.to_owned()
    } else {
        std::env::current_dir()
            .map_err(|e| e.to_string())?
            .join(path)
    };
    let source = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
    let ast = unlaxer_ubnf::parse(&source).map_err(|e| e.to_string())?;
    crate::modules::load(&path)?;
    let mut resolver = crate::packages::Resolver::new(&path);
    let mut modules = Vec::new();
    for grammar in &ast.grammars {
        for imported in &grammar.imports {
            let target = resolver.import_path(&path, &imported.path)?;
            let text = resolver.read(&target)?;
            let module = unlaxer_ubnf::parse(&text).map_err(|e| e.to_string())?;
            let definitions: Vec<Value> = module.grammars[0].tokens.iter().map(|token| {
                json!({"end":token.span.codepoint_end,"name":token.name,"start":token.span.codepoint_start})
            }).collect();
            modules.push(json!({"alias":imported.alias,"grammar":grammar.name,"identity":resolver.identity(&target)?,
                "source":imported.path,"text":text,"definitions":definitions}));
        }
    }
    let mut whitespace = Vec::new();
    for grammar in &ast.grammars {
        let global = grammar
            .settings
            .iter()
            .find_map(|setting| match &setting.value {
                unlaxer_ubnf::SettingValue::String(value) if setting.key == "whitespace" => {
                    Some(value.as_str())
                }
                _ => None,
            })
            .unwrap_or("none");
        let normalize = |style: &str| {
            let style = style.trim();
            if style.eq_ignore_ascii_case("javaStyle") {
                "javaStyle".to_owned()
            } else if style.eq_ignore_ascii_case("none") {
                "none".to_owned()
            } else {
                style.to_owned()
            }
        };
        whitespace.push(json!({"grammar":grammar.name,"rule":null,"policy":normalize(global)}));
        for rule in &grammar.rules {
            let local =
                rule.annotations
                    .iter()
                    .rev()
                    .find_map(|annotation| match &annotation.kind {
                        unlaxer_ubnf::AnnotationKind::Whitespace { style } => {
                            Some(style.as_deref().unwrap_or("javaStyle"))
                        }
                        _ => None,
                    })
                    .unwrap_or_else(|| {
                        if rule.annotations.iter().any(|a| {
                            matches!(a.kind, unlaxer_ubnf::AnnotationKind::Interleave { .. })
                        }) {
                            "javaStyle"
                        } else {
                            global
                        }
                    });
            whitespace
                .push(json!({"grammar":grammar.name,"rule":rule.name,"policy":normalize(local)}));
        }
    }
    Ok(json!({"modules":modules,"schemaVersion":1,"whitespace":whitespace}))
}

/// Match the Java JSON writer's escaping of JavaScript line separators.
pub fn to_json(value: &Value) -> String {
    value
        .to_string()
        .replace('\u{2028}', "\\u2028")
        .replace('\u{2029}', "\\u2029")
}
