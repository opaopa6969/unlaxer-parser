//! Validation and terminal collection for the explicitly enabled lexical input profile.
use crate::adapters::AdapterDiagnostic;
use std::collections::HashSet;
use unlaxer_codegen::lexical::{LexicalExpression, Op};
use unlaxer_ubnf::*;

pub fn enabled(grammar: &GrammarDecl) -> bool {
    grammar.settings.iter().any(|s| s.key == "tokenStream")
}
pub fn problems(grammar: &GrammarDecl) -> Vec<AdapterDiagnostic> {
    if !enabled(grammar) {
        return vec![];
    }
    let mut issues = vec![];
    let mut add = |code, subject: &str, span| {
        issues.push(AdapterDiagnostic {
            code,
            subject: subject.into(),
            span,
        })
    };
    let settings: Vec<_> = grammar
        .settings
        .iter()
        .filter(|s| s.key == "tokenStream")
        .collect();
    for (i, s) in settings.iter().enumerate() {
        if i > 0 || !matches!(&s.value, SettingValue::String(v) if v == "enabled") {
            add("E-TOKEN-STREAM-SETTING", "tokenStream", s.span);
        }
    }
    if !grammar
        .settings
        .iter()
        .any(|s| s.key == "ubnf" && matches!(&s.value, SettingValue::String(v) if v == "v2"))
    {
        add("E-TOKEN-STREAM-VERSION", "tokenStream", settings[0].span);
    }
    for s in &grammar.settings {
        if s.key == "comment" {
            add("E-TOKEN-STREAM-TRIVIA", "comment", s.span);
        }
    }
    let Ok(programs) = crate::lexical::compile(grammar) else {
        return issues;
    };
    let mut references = HashSet::new();
    for rule in &grammar.rules {
        body(&rule.body, &mut vec![], &mut references);
    }
    for token in &grammar.tokens {
        if matches!(token.kind, TokenKind::Eof | TokenKind::Empty) {
            continue;
        }
        if !matches!(token.kind, TokenKind::Declarative { .. })
            || references.contains(&token.name) && programs[&token.name].nullable()
        {
            add("E-TOKEN-STREAM-TOKEN", &token.name, token.span);
        }
    }
    for rule in &grammar.rules {
        for a in &rule.annotations {
            let name = match a.kind {
                AnnotationKind::Whitespace { .. } => Some("whitespace"),
                AnnotationKind::Interleave { .. } => Some("interleave"),
                AnnotationKind::Backref { .. } => Some("backref"),
                AnnotationKind::Recovery { .. } => Some("recovery"),
                _ => None,
            };
            if let Some(name) = name {
                add("E-TOKEN-STREAM-ANNOTATION", name, a.span);
            }
        }
    }
    issues
}
pub fn terminals(grammar: &GrammarDecl) -> Result<Vec<(String, bool, LexicalExpression)>, String> {
    let mut literals = vec![];
    let mut references = HashSet::new();
    for rule in &grammar.rules {
        body(&rule.body, &mut literals, &mut references);
    }
    let mut entries: Vec<_> = literals
        .into_iter()
        .filter(|s| !s.is_empty())
        .map(|s| {
            (
                s.clone(),
                true,
                LexicalExpression {
                    op: Op::LITERAL,
                    text: s,
                    min: 0,
                    max: 0,
                    children: vec![],
                },
            )
        })
        .collect();
    let programs = crate::lexical::compile(grammar)?;
    for token in &grammar.tokens {
        if references.contains(&token.name) && matches!(token.kind, TokenKind::Declarative { .. }) {
            entries.push((token.name.clone(), false, programs[&token.name].clone()));
        }
    }
    Ok(entries)
}
fn body(value: &RuleBody, literals: &mut Vec<String>, references: &mut HashSet<String>) {
    for sequence in &value.alternatives {
        for e in &sequence.elements {
            element(&e.element, literals, references);
        }
    }
}
fn element(e: &AtomicElement, literals: &mut Vec<String>, references: &mut HashSet<String>) {
    match &e.kind {
        ElementKind::Terminal(t) => {
            if !literals.contains(t) {
                literals.push(t.clone());
            }
        }
        ElementKind::RuleRef { name, .. } => {
            references.insert(name.clone());
        }
        ElementKind::Group(b) | ElementKind::Optional(b) | ElementKind::Repeat(b) => {
            body(b, literals, references)
        }
        ElementKind::OneOrMore(e) | ElementKind::BoundedRepeat { element: e, .. } => {
            element(e, literals, references)
        }
        ElementKind::Separated {
            element: e,
            separator,
        } => {
            element(e, literals, references);
            element(separator, literals, references);
        }
        ElementKind::Error(_) => {}
    }
}
