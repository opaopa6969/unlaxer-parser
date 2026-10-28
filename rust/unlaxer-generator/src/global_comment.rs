//! Global `@comment: { line: '//' }` (#712), shared in meaning with Java's `GlobalComment`.
//!
//! The only form is the one whose meaning every generator honours: Java's `CPPComment`
//! (`//` up to, not including, CR / LF / EOF). Forms whose written value Java used to ignore
//! are rejected instead of being silently replaced, except next to a global
//! `@whitespace: javaStyle`: there every such form generated the same code as javaStyle alone
//! in 3.3.0, so it is kept and reported as `W-COMMENT-IGNORED`.
use crate::adapters::AdapterDiagnostic;
use unlaxer_ubnf::*;

pub const IGNORED: &str = "W-COMMENT-IGNORED";

pub fn problems(grammar: &GrammarDecl) -> Vec<AdapterDiagnostic> {
    // tokenStream owns @comment there (E-TOKEN-STREAM-TRIVIA); report it once.
    if crate::token_stream::enabled(grammar) || java_style(grammar) {
        return vec![];
    }
    let settings: Vec<_> = settings(grammar).collect();
    let mut issues = unsupported(&settings, "E-COMMENT-SETTING");
    if issues.is_empty() && !settings.is_empty() {
        if let Some(style) = global_whitespace(grammar) {
            let builtin = ["javaStyle", "none"]
                .iter()
                .any(|name| style.trim().eq_ignore_ascii_case(name));
            // Like Java's GlobalComment, resolve only after lexical compilation succeeds.
            // Lexical errors and undefined/nullable styles belong to the whitespace checks.
            let named = crate::lexical::compile_syntax(grammar)
                .ok()
                .is_some_and(|definitions| {
                    !builtin
                        && definitions
                            .get(style.trim())
                            .is_some_and(|expression| !expression.nullable())
                });
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

/// `W-COMMENT-IGNORED` for every `@comment` that `E-COMMENT-SETTING` would reject, when the
/// grammar default is javaStyle: javaStyle already skips `//`, and 3.3.0 generated these
/// grammars as javaStyle alone. Generation is unchanged. Same codes and spans as Java's
/// `GlobalComment.warnings`.
pub fn warnings(grammar: &GrammarDecl) -> Vec<AdapterDiagnostic> {
    if crate::token_stream::enabled(grammar) || !java_style(grammar) {
        return vec![];
    }
    unsupported(&settings(grammar).collect::<Vec<_>>(), IGNORED)
}

/// True when rules that inherit the grammar default skip only line comments.
pub fn line_comments(grammar: &GrammarDecl) -> bool {
    if settings(grammar).next().is_none() || !problems(grammar).is_empty() {
        return false;
    }
    // javaStyle already includes line comments; named global whitespace is rejected above.
    !java_style(grammar)
}

fn unsupported(settings: &[&GlobalSetting], code: &'static str) -> Vec<AdapterDiagnostic> {
    settings
        .iter()
        .enumerate()
        .filter(|(i, setting)| *i > 0 || !supported(&setting.value))
        .map(|(_, setting)| AdapterDiagnostic {
            code,
            subject: "comment".into(),
            span: setting.span,
        })
        .collect()
}

fn java_style(grammar: &GrammarDecl) -> bool {
    global_whitespace(grammar).is_some_and(|style| style.trim().eq_ignore_ascii_case("javaStyle"))
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
