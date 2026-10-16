//! Refine only pure numeric capture contracts, preserving existing helper/text projections.
use unlaxer_codegen::ir::{GrammarIr, Kind};
use unlaxer_ubnf::ast::{
    AnnotationKind, AtomicElement, ElementKind, GrammarDecl, RuleBody, RuleDecl, TokenKind,
};

fn mapping(rule: &RuleDecl) -> Option<&str> {
    rule.annotations
        .iter()
        .find_map(|annotation| match &annotation.kind {
            AnnotationKind::Mapping { class_name, .. } => Some(class_name.as_str()),
            _ => None,
        })
}

pub(crate) fn refine(grammar: &GrammarDecl, ir: &mut GrammarIr) {
    for rule in &mut ir.rules {
        let Some(mapped) = &mut rule.mapping else {
            continue;
        };
        let Some(source) = grammar.rules.iter().find(|r| r.name == rule.name) else {
            continue;
        };
        let Some(name) = mapping(source) else {
            continue;
        };
        let contributors: Vec<_> = grammar
            .rules
            .iter()
            .filter(|r| {
                mapping(r) == Some(name)
                    && !r
                        .annotations
                        .iter()
                        .any(|a| matches!(a.kind, AnnotationKind::Skip))
            })
            .collect();
        for field in &mut mapped.fields {
            if contributors.iter().all(|r| {
                let mut captures = Vec::new();
                collect(grammar, &r.body, &field.name, &mut captures);
                !captures.is_empty() && captures.iter().all(|numeric| *numeric)
            }) {
                field.kind = Kind::Integer;
            }
        }
    }
}

fn numeric_body(grammar: &GrammarDecl, body: &RuleBody) -> bool {
    let [sequence] = body.alternatives.as_slice() else {
        return false;
    };
    let [element] = sequence.elements.as_slice() else {
        return false;
    };
    scalar(grammar, &element.element)
}
fn scalar(grammar: &GrammarDecl, atom: &AtomicElement) -> bool {
    matches!(&atom.kind, ElementKind::RuleRef { name, .. } if !grammar.rules.iter().any(|r| &r.name == name)
        && grammar.tokens.iter().any(|token| &token.name == name && matches!(&token.kind,
            TokenKind::Simple { parser_class } if matches!(parser_class.rsplit('.').next(), Some("NumberParser" | "DigitParser")))))
}
fn numeric(grammar: &GrammarDecl, atom: &AtomicElement) -> bool {
    match &atom.kind {
        ElementKind::RuleRef { .. } => scalar(grammar, atom),
        ElementKind::Optional(body) | ElementKind::Repeat(body) => numeric_body(grammar, body),
        ElementKind::OneOrMore(child)
        | ElementKind::BoundedRepeat { element: child, .. }
        | ElementKind::Separated { element: child, .. } => scalar(grammar, child),
        _ => false,
    }
}
fn collect(grammar: &GrammarDecl, body: &RuleBody, name: &str, values: &mut Vec<bool>) {
    for sequence in &body.alternatives {
        for child in &sequence.elements {
            if child.capture.as_deref() == Some(name) {
                values.push(numeric(grammar, &child.element));
            }
            nested(grammar, &child.element, name, values);
        }
    }
}
fn nested(grammar: &GrammarDecl, atom: &AtomicElement, name: &str, values: &mut Vec<bool>) {
    match &atom.kind {
        ElementKind::Group(body) | ElementKind::Optional(body) | ElementKind::Repeat(body) => {
            collect(grammar, body, name, values)
        }
        ElementKind::OneOrMore(child)
        | ElementKind::BoundedRepeat { element: child, .. }
        | ElementKind::Separated { element: child, .. } => nested(grammar, child, name, values),
        _ => {}
    }
}
