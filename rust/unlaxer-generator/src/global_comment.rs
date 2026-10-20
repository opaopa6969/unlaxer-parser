//! Global `@comment: { line: '//' }` (#712), shared in meaning with Java's `GlobalComment`.
//!
//! The only form is the one whose meaning every generator honours: Java's `CPPComment`
//! (`//` up to, not including, CR / LF / EOF). Forms whose written value Java used to ignore
//! are rejected instead of being silently replaced.
use crate::adapters::AdapterDiagnostic;
use unlaxer_ubnf::*;

pub fn problems(grammar: &GrammarDecl) -> Vec<AdapterDiagnostic> {
    // tokenStream owns @comment there (E-TOKEN-STREAM-TRIVIA); report it once.
    if crate::token_stream::enabled(grammar) {
        return vec![];
    }
    let settings: Vec<_> = settings(grammar).collect();
    let mut issues: Vec<_> = settings
        .iter()
        .enumerate()
        .filter(|(i, setting)| *i > 0 || !supported(&setting.value))
        .map(|(_, setting)| AdapterDiagnostic {
            code: "E-COMMENT-SETTING",
            subject: "comment".into(),
            span: setting.span,
        })
        .collect();
    if issues.is_empty() && !settings.is_empty() {
        if let Some(style) = global_whitespace(grammar) {
            let builtin = ["javaStyle", "none"]
                .iter()
                .any(|name| style.trim().eq_ignore_ascii_case(name));
            // An undefined or nullable style is reported by the whitespace checks.
            let named = !builtin
                && crate::lexical::compile_syntax(grammar)
                    .ok()
                    .and_then(|definitions| definitions.get(style.trim()).cloned())
                    .is_some_and(|expression| !expression.nullable());
            if named {
                issues.push(AdapterDiagnostic {
                    code: "E-COMMENT-WHITESPACE",
                    subject: style.trim().into(),
                    span: settings[0].span,
                });
            }
        }
    }
    issues
}

/// True when rules that inherit the grammar default skip only line comments.
pub fn line_comments(grammar: &GrammarDecl) -> bool {
    if settings(grammar).next().is_none() || !problems(grammar).is_empty() {
        return false;
    }
    // javaStyle already includes line comments; named global whitespace is rejected above.
    global_whitespace(grammar).is_none_or(|style| !style.trim().eq_ignore_ascii_case("javaStyle"))
}

fn settings(grammar: &GrammarDecl) -> impl Iterator<Item = &GlobalSetting> {
    grammar
        .settings
        .iter()
        .filter(|setting| setting.key == "comment")
}

fn supported(value: &SettingValue) -> bool {
    matches!(value, SettingValue::Block(entries)
        if entries.len() == 1 && entries[0].key == "line" && entries[0].value == "//")
}

fn global_whitespace(grammar: &GrammarDecl) -> Option<&str> {
    grammar
        .settings
        .iter()
        .find_map(|setting| match (&*setting.key, &setting.value) {
            ("whitespace", SettingValue::String(value)) => Some(value.as_str()),
            _ => None,
        })
}
