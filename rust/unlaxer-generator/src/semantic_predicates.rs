//! Named pure semantic predicates (#1006), validated exactly like Java `SemanticPredicates`.
use unlaxer_ubnf::ast::{AnnotationKind, GrammarDecl, RuleDecl};

pub fn annotation(rule: &RuleDecl) -> Option<&str> {
    rule.annotations.iter().find_map(|a| match &a.kind {
        AnnotationKind::Predicate { name } => Some(name.as_str()),
        _ => None,
    })
}

pub fn enabled(grammar: &GrammarDecl) -> bool {
    grammar.rules.iter().any(|rule| annotation(rule).is_some())
}

fn name_predicates(grammar: &GrammarDecl) -> bool {
    grammar.rules.iter().any(|rule| {
        rule.annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::NamePredicate { .. }))
    })
}

/// Java validates name predicates first. Native lowering checks them on lowered bodies,
/// so it validates `@predicate` there when a grammar has both annotations.
pub fn waits_for_name_predicates(grammar: &GrammarDecl) -> bool {
    enabled(grammar) && name_predicates(grammar)
}

/// Distinct names in rule order, including rules unreachable from the root.
pub fn requirements(grammar: &GrammarDecl) -> Vec<String> {
    let mut names = Vec::<String>::new();
    for name in grammar.rules.iter().filter_map(annotation) {
        if !names.iter().any(|known| known == name) {
            names.push(name.to_owned());
        }
    }
    names
}

/// Version 1 accepts `@root`, `@mapping` and `@doc` beside `@predicate`; other rule
/// annotations, name predicates, token-stream and embedding grammars are refused, never ignored.
pub fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    if !enabled(grammar) {
        return Ok(());
    }
    for rule in &grammar.rules {
        let names = rule
            .annotations
            .iter()
            .filter_map(|a| match &a.kind {
                AnnotationKind::Predicate { name } => Some(name),
                _ => None,
            })
            .collect::<Vec<_>>();
        if names.len() > 1 {
            return Err(format!("E-PREDICATE-DUPLICATE: {}", rule.name));
        }
        let Some(name) = names.first() else {
            continue;
        };
        if unlaxer_runtime::semantic_predicates::validate_name(name).is_err() {
            return Err(format!("E-PREDICATE-NAME: {}", rule.name));
        }
        for other in &rule.annotations {
            if !matches!(
                other.kind,
                AnnotationKind::Predicate { .. }
                    | AnnotationKind::Root
                    | AnnotationKind::Mapping { .. }
                    | AnnotationKind::Doc { .. }
            ) {
                return Err(format!(
                    "E-PREDICATE-COMBINATION: {} {}",
                    rule.name,
                    annotation_name(&other.kind)
                ));
            }
        }
    }
    if name_predicates(grammar) {
        return Err("E-PREDICATE-COMBINATION: @namePredicate".into());
    }
    if crate::token_stream::enabled(grammar) || crate::lexical_contexts::enabled(grammar) {
        return Err("E-PREDICATE-COMBINATION: token-stream grammar".into());
    }
    // Java's embedded entries resolve rule classes without the registry; version 1 refuses them.
    if crate::embedded::enabled(grammar) {
        return Err("E-PREDICATE-COMBINATION: embedding grammar".into());
    }
    if requirements(grammar).len() > unlaxer_runtime::semantic_predicates::MAX_PREDICATES {
        return Err("E-PREDICATE-LIMIT: at most 64 predicates".into());
    }
    Ok(())
}

/// Java's annotation record name with the `Annotation` suffix removed.
fn annotation_name(kind: &AnnotationKind) -> String {
    let name = match kind {
        AnnotationKind::Root => "root",
        AnnotationKind::Mapping { .. } => "mapping",
        AnnotationKind::Eval { .. } => "eval",
        AnnotationKind::Whitespace { .. } => "whitespace",
        AnnotationKind::Interleave { .. } => "interleave",
        AnnotationKind::LexicalContext { .. } => "lexicalContext",
        AnnotationKind::Backref { .. } => "backref",
        AnnotationKind::ScopeTree { .. } => "scopeTree",
        AnnotationKind::Declares { .. } => "declares",
        AnnotationKind::Catalog { .. } => "catalog",
        AnnotationKind::LeftAssoc => "leftAssoc",
        AnnotationKind::RightAssoc => "rightAssoc",
        AnnotationKind::LongestChoice => "longestChoice",
        AnnotationKind::UniqueLongestChoice => "uniqueLongestChoice",
        AnnotationKind::Predicate { .. } => "predicate",
        AnnotationKind::NamePredicate { .. } => "namePredicate",
        AnnotationKind::PredictiveChoice => "predictiveChoice",
        AnnotationKind::Precedence { .. } => "precedence",
        AnnotationKind::Doc { .. } => "doc",
        AnnotationKind::Recovery { .. } => "recovery",
        AnnotationKind::Skip => "skip",
        AnnotationKind::Simple { name } => return format!("@{name}"),
        AnnotationKind::CommonField { .. } => "commonField",
        AnnotationKind::Enum => "enum",
    };
    format!("@{name}")
}
