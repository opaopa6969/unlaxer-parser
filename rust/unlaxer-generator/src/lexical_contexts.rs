//! Explicit lexical goals, resolved after lexical module imports are flattened.
use std::collections::HashSet;
use unlaxer_codegen::lexical::{LexicalExpression, Op};
use unlaxer_ubnf::{AnnotationKind, GrammarDecl, SettingValue, TokenKind};
pub fn enabled(grammar: &GrammarDecl) -> bool {
    grammar
        .rules
        .iter()
        .flat_map(|rule| &rule.annotations)
        .any(|annotation| matches!(annotation.kind, AnnotationKind::LexicalContext { .. }))
}
pub fn problems(grammar: &GrammarDecl) -> Vec<crate::portability::Diagnostic> {
    let Err(message) = validate(grammar) else {
        return vec![];
    };
    let Some((name, subject)) = message.split_once(": ") else {
        return vec![];
    };
    let code = match name {
        "E-LEXICAL-CONTEXT-VERSION" => "E-LEXICAL-CONTEXT-VERSION",
        "E-LEXICAL-CONTEXT-DUPLICATE" => "E-LEXICAL-CONTEXT-DUPLICATE",
        "E-LEXICAL-CONTEXT-TOKEN" => "E-LEXICAL-CONTEXT-TOKEN",
        "E-LEXICAL-CONTEXT-LITERAL" => "E-LEXICAL-CONTEXT-LITERAL",
        "E-LEXICAL-CONTEXT-LIMIT" => "E-LEXICAL-CONTEXT-LIMIT",
        _ => return vec![],
    };
    let mut span = grammar.span;
    if code != "E-LEXICAL-CONTEXT-VERSION" {
        for rule in &grammar.rules {
            let contexts = rule
                .annotations
                .iter()
                .filter(|annotation| {
                    matches!(annotation.kind, AnnotationKind::LexicalContext { .. })
                })
                .collect::<Vec<_>>();
            if contexts.is_empty() {
                continue;
            }
            if code == "E-LEXICAL-CONTEXT-DUPLICATE" && contexts.len() > 1 {
                span = contexts[1].span;
                break;
            }
            if code != "E-LEXICAL-CONTEXT-DUPLICATE" {
                if let AnnotationKind::LexicalContext { tokens, literals } = &contexts[0].kind {
                    if terminals(grammar, tokens, literals).is_err() {
                        span = contexts[0].span;
                        break;
                    }
                }
            }
        }
        if span == grammar.span {
            for token in &grammar.tokens {
                if !matches!(
                    token.kind,
                    TokenKind::Declarative { .. } | TokenKind::Eof | TokenKind::Empty
                ) {
                    span = token.span;
                    break;
                }
            }
        }
    }
    vec![crate::portability::Diagnostic {
        code,
        subject: subject.into(),
        span: Some(span),
    }]
}
pub fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    if !enabled(grammar) {
        return Ok(());
    }
    if !grammar.settings.iter().any(|setting| {
        setting.key == "ubnf"
            && matches!(&setting.value, SettingValue::String(value) if value == "v2")
    }) {
        return Err("E-LEXICAL-CONTEXT-VERSION: requires v2".into());
    }
    for rule in &grammar.rules {
        let contexts = rule
            .annotations
            .iter()
            .filter(|annotation| matches!(annotation.kind, AnnotationKind::LexicalContext { .. }))
            .collect::<Vec<_>>();
        if contexts.len() > 1 {
            return Err(format!("E-LEXICAL-CONTEXT-DUPLICATE: {}", rule.name));
        }
        if let Some(annotation) = contexts.first() {
            if let AnnotationKind::LexicalContext { tokens, literals } = &annotation.kind {
                terminals(grammar, tokens, literals)?;
            }
        }
    }
    for token in &grammar.tokens {
        if !matches!(
            token.kind,
            TokenKind::Declarative { .. } | TokenKind::Eof | TokenKind::Empty
        ) {
            return Err(format!("E-LEXICAL-CONTEXT-TOKEN: {}", token.name));
        }
    }
    Ok(())
}
pub fn terminals(
    grammar: &GrammarDecl,
    tokens: &[String],
    literals: &[String],
) -> Result<Vec<(String, bool, LexicalExpression)>, String> {
    if tokens.len() + literals.len() > 256 {
        return Err("E-LEXICAL-CONTEXT-LIMIT: 256 terminals".into());
    }
    let programs = crate::lexical::compile(grammar)?;
    let mut result = vec![];
    let mut seen = HashSet::new();
    for literal in literals {
        if literal.is_empty() || !seen.insert(literal) {
            return Err("E-LEXICAL-CONTEXT-LITERAL: empty or duplicate literal".into());
        }
        result.push((
            literal.clone(),
            true,
            LexicalExpression {
                op: Op::LITERAL,
                text: literal.clone(),
                min: 0,
                max: 0,
                children: vec![],
            },
        ));
    }
    let mut names = HashSet::new();
    for name in tokens {
        if !names.insert(name) || programs.get(name).is_none_or(LexicalExpression::nullable) {
            return Err(format!("E-LEXICAL-CONTEXT-TOKEN: {name}"));
        }
    }
    for token in &grammar.tokens {
        if names.contains(&token.name) {
            result.push((token.name.clone(), false, programs[&token.name].clone()));
        }
    }
    Ok(result)
}
