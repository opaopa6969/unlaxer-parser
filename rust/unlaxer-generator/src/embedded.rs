//! Opt-in bounded embedding declarations using the existing setting block syntax.
use std::collections::{HashMap, HashSet};
use std::fmt::Write;
use unlaxer_ubnf::{AnnotationKind, ElementKind, GrammarDecl, SettingValue};
struct Declaration {
    rule: String,
    body: String,
    language: String,
    package: String,
    version: String,
    grammar: String,
    entry: String,
}
pub fn enabled(grammar: &GrammarDecl) -> bool {
    grammar
        .settings
        .iter()
        .any(|s| matches!(s.key.as_str(), "embedding" | "embedded"))
}
pub fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    declarations(grammar).map(|_| ())
}
fn invalid(message: &str) -> String {
    format!("E-EMBEDDING: {message}")
}
fn declarations(grammar: &GrammarDecl) -> Result<Vec<Declaration>, String> {
    let mut result = vec![];
    let mut seen = HashSet::new();
    let mut profiles = 0;
    for setting in &grammar.settings {
        if setting.key == "embedding" {
            profiles += 1;
            if profiles > 1 || !matches!(&setting.value, SettingValue::String(v) if v == "enabled")
            {
                return Err(invalid("embedding must be enabled once"));
            }
        }
        if setting.key != "embedded" {
            continue;
        }
        let SettingValue::Block(block) = &setting.value else {
            return Err(invalid("embedded needs a block"));
        };
        let mut values = HashMap::new();
        for pair in block {
            if pair.value.is_empty()
                || values
                    .insert(pair.key.as_str(), pair.value.as_str())
                    .is_some()
            {
                return Err(invalid("empty or duplicate embedding field"));
            }
        }
        let keys: HashSet<_> = [
            "rule", "body", "language", "package", "version", "grammar", "entry",
        ]
        .into_iter()
        .collect();
        if values.keys().copied().collect::<HashSet<_>>() != keys {
            return Err(invalid(
                "embedding fields must be rule/body/language/package/version/grammar/entry",
            ));
        }
        let name = values["rule"];
        let body = values["body"];
        if !seen.insert(name) {
            return Err(invalid("duplicate embedded rule"));
        }
        let rule = grammar
            .rules
            .iter()
            .find(|r| r.name == name)
            .ok_or_else(|| invalid("unknown embedded rule"))?;
        if rule.body.alternatives.len() != 1
            || rule
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Recovery { .. }))
        {
            return Err(invalid(
                "embedding needs one direct body capture in an unrecovered sequence",
            ));
        }
        let captures: Vec<_> = rule.body.alternatives[0]
            .elements
            .iter()
            .filter(|e| e.capture.as_deref() == Some(body))
            .collect();
        if captures.len() != 1 || count_captures(&rule.body, body) != 1 {
            return Err(invalid(
                "embedding needs one direct body capture in an unrecovered sequence",
            ));
        }
        if matches!(
            captures[0].element.kind,
            ElementKind::Optional(_)
                | ElementKind::Repeat(_)
                | ElementKind::OneOrMore(_)
                | ElementKind::BoundedRepeat { .. }
                | ElementKind::Separated { .. }
        ) {
            return Err(invalid("embedding body must be a scalar capture"));
        }
        result.push(Declaration {
            rule: name.into(),
            body: body.into(),
            language: values["language"].into(),
            package: values["package"].into(),
            version: values["version"].into(),
            grammar: values["grammar"].into(),
            entry: values["entry"].into(),
        });
    }
    Ok(result)
}
fn count_captures(body: &unlaxer_ubnf::RuleBody, name: &str) -> usize {
    body.alternatives
        .iter()
        .flat_map(|s| &s.elements)
        .map(|e| {
            usize::from(e.capture.as_deref() == Some(name)) + nested_count(&e.element.kind, name)
        })
        .sum()
}
fn nested_count(element: &ElementKind, name: &str) -> usize {
    match element {
        ElementKind::Group(b) | ElementKind::Optional(b) | ElementKind::Repeat(b) => {
            count_captures(b, name)
        }
        ElementKind::OneOrMore(e) | ElementKind::BoundedRepeat { element: e, .. } => {
            nested_count(&e.kind, name)
        }
        ElementKind::Separated { element, separator } => {
            nested_count(&element.kind, name) + nested_count(&separator.kind, name)
        }
        _ => 0,
    }
}
fn quote(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' | '\\' => {
                out.push('\\');
                out.push(c);
            }
            c if (c as u32) < 32 || c == '\u{7f}' => {
                write!(out, "\\u{{{:x}}}", c as u32).unwrap();
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
pub fn api(grammar: &GrammarDecl, ir: &unlaxer_codegen::GrammarIr) -> Result<String, String> {
    let declarations = declarations(grammar)?;
    if !enabled(grammar) {
        return Ok(String::new());
    }
    let index = |name: &str| {
        ir.rules
            .iter()
            .position(|r| r.name == name)
            .expect("lowered rule")
    };
    let mut out = format!("\npub fn embedded_grammar() -> unlaxer_runtime::embedded::CstGrammar {{\n    unlaxer_runtime::embedded::CstGrammar {{ name: {}.into(), grammar: std::sync::Arc::clone(grammar()), whitespace: {}, entries: [\n", quote(&grammar.name), ir.java_whitespace);
    for rule in &grammar.rules {
        writeln!(
            out,
            "        ({}.into(), {}),",
            quote(&rule.name),
            index(&rule.name)
        )
        .unwrap();
    }
    out.push_str("    ].into_iter().collect(), bindings: vec![\n");
    for d in declarations {
        writeln!(out, "        unlaxer_runtime::embedded::Binding {{ rule: {}, capture: {}.into(), language: unlaxer_runtime::source::Language {{ id: {}.into(), package_id: {}.into(), version: {}.into(), grammar: {}.into(), entry: {}.into() }} }},", index(&d.rule), quote(&d.body), quote(&d.language), quote(&d.package), quote(&d.version), quote(&d.grammar), quote(&d.entry)).unwrap();
    }
    out.push_str("    ] }\n}\n");
    Ok(out)
}
