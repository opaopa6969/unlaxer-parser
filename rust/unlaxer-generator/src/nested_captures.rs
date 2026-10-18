use std::collections::{BTreeMap, HashMap, HashSet};
use unlaxer_codegen::{BoundaryKind, Cardinality, Expression, Kind};
use unlaxer_ubnf::{
    AnnotationKind, AtomicElement, ElementKind, GrammarDecl, RuleBody, RuleDecl, TokenKind,
};

pub fn enabled(rule: &RuleDecl) -> bool {
    rule.annotations
        .iter()
        .any(|a| matches!(&a.kind, AnnotationKind::Simple { name } if name == "nestedCaptures"))
}
fn error(rule: &RuleDecl, detail: &str) -> String {
    format!(
        "E-NESTED-SHAPE: {}: {detail} at CP {}..{}",
        rule.name, rule.span.codepoint_start, rule.span.codepoint_end
    )
}
pub fn sites(rule: &RuleDecl) -> Result<BTreeMap<String, &AtomicElement>, String> {
    fn body<'a>(
        rule: &RuleDecl,
        value: &'a RuleBody,
        inside: bool,
        depth: usize,
        result: &mut BTreeMap<String, &'a AtomicElement>,
    ) -> Result<(), String> {
        if depth > 64 {
            return Err(error(rule, "capture traversal depth exceeds 64"));
        }
        for sequence in &value.alternatives {
            for element in &sequence.elements {
                if let Some(name) = &element.capture {
                    if inside || result.insert(name.clone(), &element.element).is_some() {
                        return Err(error(rule, "one outer capture site required"));
                    }
                }
                atom(
                    rule,
                    &element.element,
                    inside || element.capture.is_some(),
                    depth + 1,
                    result,
                )?;
            }
        }
        Ok(())
    }
    fn atom<'a>(
        rule: &RuleDecl,
        value: &'a AtomicElement,
        inside: bool,
        depth: usize,
        result: &mut BTreeMap<String, &'a AtomicElement>,
    ) -> Result<(), String> {
        if depth > 64 {
            return Err(error(rule, "capture traversal depth exceeds 64"));
        }
        match &value.kind {
            ElementKind::OwnedCapture { body: v, .. }
            | ElementKind::CaptureScope(v)
            | ElementKind::Commit { body: v, .. }
            | ElementKind::Label { body: v, .. }
            | ElementKind::Catch { choice: v, .. }
            | ElementKind::Group(v) => body(rule, v, inside, depth + 1, result)?,
            ElementKind::Optional(v) | ElementKind::Repeat(v) => {
                body(rule, v, true, depth + 1, result)?
            }
            ElementKind::OneOrMore(v) | ElementKind::BoundedRepeat { element: v, .. } => {
                atom(rule, v, true, depth + 1, result)?
            }
            ElementKind::Separated { element, separator } => {
                atom(rule, element, true, depth + 1, result)?;
                atom(rule, separator, true, depth + 1, result)?;
            }
            _ => (),
        }
        Ok(())
    }
    let mut result = BTreeMap::new();
    body(rule, &rule.body, false, 0, &mut result)?;
    Ok(result)
}
pub fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    let mut checked_mappings = HashSet::new();
    let mut validated = Vec::new();
    for rule in &grammar.rules {
        if !enabled(rule) {
            continue;
        }
        if rule
            .annotations
            .iter()
            .filter(|a| matches!(&a.kind,AnnotationKind::Simple{name} if name=="nestedCaptures"))
            .count()
            != 1
        {
            return Err(error(rule, "duplicate annotation"));
        }
        // Each unsupported combination names its tracking issue; Java uses the same order and text.
        let has =
            |pred: fn(&AnnotationKind) -> bool| rule.annotations.iter().any(|a| pred(&a.kind));
        // Checked before @mapping: a skipped rule publishes no mapping on either host.
        if has(|k| matches!(k, AnnotationKind::Skip | AnnotationKind::Enum)) {
            return Err(error(rule, "@skip/@enum rules cannot own nested captures"));
        }
        let mapping = rule
            .annotations
            .iter()
            .find_map(|a| {
                if let AnnotationKind::Mapping { class_name, params } = &a.kind {
                    Some((class_name, params))
                } else {
                    None
                }
            })
            .ok_or_else(|| error(rule, "@mapping required"))?;
        if has(|k| matches!(k, AnnotationKind::LeftAssoc | AnnotationKind::RightAssoc)) {
            return Err(error(
                rule,
                "@leftAssoc/@rightAssoc projection of nested captures is not supported yet (#695)",
            ));
        }
        if has(|k| matches!(k, AnnotationKind::Eval { .. })) {
            return Err(error(
                rule,
                "@eval on a nested capture owner is not supported yet (#694)",
            ));
        }
        if !mapping.1.is_empty() && has(|k| matches!(k, AnnotationKind::CommonField { .. })) {
            return Err(error(
                rule,
                "@commonField on a nested owner with fields is not supported yet (#697)",
            ));
        }
        if grammar.settings.iter().any(|s| s.key == "tokenStream") {
            return Err(error(
                rule,
                "token stream with nested captures is not supported yet (#696)",
            ));
        }
        let values = sites(rule)?;
        let mut usages = Vec::new();
        crate::lowering::collect_typeof(&rule.body, &mut usages)?;
        for (_, reference) in &usages {
            if !values.contains_key(reference) {
                return Err(format!("E-TYPEOF-UNKNOWN-CAPTURE: {reference}"));
            }
        }
        let typeof_names: Vec<String> = usages.into_iter().flat_map(|(a, b)| [a, b]).collect();
        let names: HashSet<_> = mapping
            .1
            .iter()
            .cloned()
            .chain(typeof_names.iter().cloned())
            .collect();
        if values.len() != names.len() || !names.iter().all(|p| values.contains_key(p)) {
            return Err(error(rule, "mapping must name each unique outer capture"));
        }
        let local_trivia = has_annotation(rule, |k| {
            matches!(
                k,
                AnnotationKind::Whitespace { .. } | AnnotationKind::Interleave { .. }
            )
        });
        // Same field order as Java's captureNames: mapping params, then typeof captures.
        let mut ordered: Vec<&String> = Vec::new();
        for name in mapping.1.iter().chain(typeof_names.iter()) {
            if !ordered.contains(&name) {
                ordered.push(name);
            }
        }
        for value in ordered.iter().map(|name| values[*name]) {
            shape(grammar, value, 0).map_err(|e| error(rule, &e))?;
            // Inlined alias helpers keep the grammar trivia; a rule-local override would differ per host.
            if local_trivia && expand_aliases(grammar, value) != *value {
                return Err(error(
                    rule,
                    "nested capture through a helper under rule-local trivia is not supported",
                ));
            }
        }
        if values
            .values()
            .any(|value| matches!(shape(grammar, value, 0).map(parts), Ok((Kind::Value, _))))
        {
            for candidate in &grammar.rules {
                if candidate.annotations.iter().any(|a| matches!(&a.kind,AnnotationKind::Mapping{class_name,..} if class_name=="NestedValue" || class_name.starts_with("NestedValue.")))
                    || candidate.name=="NestedValue" && candidate.annotations.iter().any(|a| matches!(a.kind,AnnotationKind::Enum)) {
                    return Err(error(rule,"NestedValue is reserved for the nested mixed sum type"));
                }
            }
        }
        validated.push((rule, mapping, values));
    }
    // Cross-rule comparison only follows bounded validation of every contributor.
    for (rule, mapping, values) in validated {
        if !checked_mappings.insert(mapping.0.clone()) {
            continue;
        }
        for candidate in &grammar.rules {
            // Skipped rules do not contribute a public mapping, matching Java's schema.
            if candidate
                .annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Skip))
            {
                continue;
            }
            let Some(params) = candidate.annotations.iter().find_map(|a| match &a.kind {
                AnnotationKind::Mapping { class_name, params } if class_name == mapping.0 => {
                    Some(params)
                }
                _ => None,
            }) else {
                continue;
            };
            if std::ptr::eq(candidate, rule) {
                continue;
            }
            if !enabled(candidate) || params != mapping.1 {
                return Err(error(
                    rule,
                    &format!(
                        "shared nested mapping requires identical opt-in and parameters: {}",
                        candidate.name
                    ),
                ));
            }
            let other_sites = sites(candidate)?;
            for name in mapping.1 {
                let own = values[name];
                let Some(other) = other_sites.get(name) else {
                    return Err(error(rule, "shared nested mapping is missing a capture"));
                };
                if shape(grammar, own, 0).map_err(|e| error(rule, &e))?
                    != shape(grammar, other, 0).map_err(|e| error(rule, &e))?
                    || nominal_leaf(grammar, own) != nominal_leaf(grammar, other)
                {
                    return Err(error(
                        rule,
                        &format!(
                            "shared nested mapping requires identical shape for {name} on {}",
                            candidate.name
                        ),
                    ));
                }
            }
        }
    }
    Ok(())
}
// Rust stores every mapped node in Ast; sharing must still respect Java's nominal leaf type.
fn nominal_leaf(grammar: &GrammarDecl, atom: &AtomicElement) -> Option<String> {
    if let Some(element) = inlined_alias(grammar, atom) {
        return nominal_leaf(grammar, element);
    }
    match &atom.kind {
        ElementKind::RuleRef { name, .. } => grammar
            .rules
            .iter()
            .find(|r| r.name == *name)
            .and_then(|r| {
                r.annotations.iter().find_map(|a| match &a.kind {
                    AnnotationKind::Mapping { class_name, .. } => Some(class_name.clone()),
                    _ => None,
                })
            }),
        ElementKind::OwnedCapture { body, .. }
        | ElementKind::CaptureScope(body)
        | ElementKind::Commit { body, .. }
        | ElementKind::Label { body, .. }
        | ElementKind::Catch { choice: body, .. }
        | ElementKind::Group(body)
        | ElementKind::Optional(body)
        | ElementKind::Repeat(body) => {
            let [sequence] = body.alternatives.as_slice() else {
                return None;
            };
            let [element] = sequence.elements.as_slice() else {
                return None;
            };
            nominal_leaf(grammar, &element.element)
        }
        ElementKind::OneOrMore(child)
        | ElementKind::BoundedRepeat { element: child, .. }
        | ElementKind::Separated { element: child, .. } => nominal_leaf(grammar, child),
        _ => None,
    }
}
fn parts(shape: Kind) -> (Kind, Vec<Cardinality>) {
    if let Kind::Nested { leaf, containers } = shape {
        (*leaf, containers)
    } else {
        (shape, Vec::new())
    }
}
fn wrap(container: Cardinality, inner: Kind) -> Kind {
    let (leaf, mut containers) = parts(inner);
    containers.insert(0, container);
    Kind::Nested {
        leaf: Box::new(leaf),
        containers,
    }
}
pub fn shape(grammar: &GrammarDecl, atom: &AtomicElement, depth: usize) -> Result<Kind, String> {
    shape_in(grammar, atom, depth, &mut Expansion::default())
}
/// Alias expansion state of one schema query: the active alias chain (cycle detection) and the
/// successful alias shapes by rule and depth. A shape is a pure function of (rule, depth) once its
/// expansion is acyclic, so the memo keeps validation linear in the number of helper rules instead
/// of exponential in shared multi-element helpers. Java (`NestedCaptures.Expansion`) does the same.
#[derive(Default)]
struct Expansion {
    stack: Vec<String>,
    memo: HashMap<(String, usize), Kind>,
}
fn shape_in(
    grammar: &GrammarDecl,
    atom: &AtomicElement,
    depth: usize,
    helpers: &mut Expansion,
) -> Result<Kind, String> {
    if depth > 32 {
        return Err("schema depth exceeds 32".into());
    }
    Ok(match &atom.kind {
        ElementKind::Separated { element, .. } => wrap(
            Cardinality::Many,
            shape_in(grammar, element, depth + 1, helpers)?,
        ),
        ElementKind::Optional(v) => wrap(
            Cardinality::Optional,
            body_shape_in(grammar, v, depth + 1, helpers)?,
        ),
        ElementKind::Repeat(v) => wrap(
            Cardinality::Many,
            body_shape_in(grammar, v, depth + 1, helpers)?,
        ),
        ElementKind::OneOrMore(v) | ElementKind::BoundedRepeat { element: v, .. } => {
            wrap(Cardinality::Many, shape_in(grammar, v, depth + 1, helpers)?)
        }
        ElementKind::OwnedCapture { body: v, .. }
        | ElementKind::CaptureScope(v)
        | ElementKind::Commit { body: v, .. }
        | ElementKind::Label { body: v, .. }
        | ElementKind::Catch { choice: v, .. }
        | ElementKind::Group(v) => body_shape_in(grammar, v, depth + 1, helpers)?,
        ElementKind::Terminal(_) | ElementKind::Replay { .. } => Kind::Text,
        ElementKind::RuleRef { name, .. } => {
            let token = grammar.tokens.iter().find(|t| &t.name == name);
            if let Some(token) = token {
                if matches!(&token.kind,TokenKind::Simple{parser_class} if matches!(parser_class.rsplit('.').next(),Some("NumberParser"|"DigitParser")))
                {
                    Kind::Integer
                } else {
                    Kind::Text
                }
            } else {
                let target = grammar
                    .rules
                    .iter()
                    .find(|r| &r.name == name)
                    .ok_or("unknown nested rule")?;
                if has_annotation(target, |k| matches!(k, AnnotationKind::Enum)) {
                    Kind::Enum(target.name.clone())
                } else if !has_annotation(target, |k| matches!(k, AnnotationKind::Skip))
                    && has_annotation(target, |k| matches!(k, AnnotationKind::Mapping { .. }))
                {
                    Kind::Node
                } else {
                    alias_shape(grammar, target, depth, helpers)?
                }
            }
        }
        _ => return Err("unsupported nested element".into()),
    })
}
fn has_annotation(rule: &RuleDecl, pred: fn(&AnnotationKind) -> bool) -> bool {
    rule.annotations.iter().any(|a| pred(&a.kind))
}
/// An unmapped, unannotated, non-recursive helper is a transparent alias: its body's shape is the
/// shape of the reference. Java (`NestedCaptures.aliasShape`) checks the same conditions in the
/// same order and reports the same text.
fn alias_shape(
    grammar: &GrammarDecl,
    target: &RuleDecl,
    depth: usize,
    helpers: &mut Expansion,
) -> Result<Kind, String> {
    let name = &target.name;
    if helpers.stack.contains(name) {
        return Err(format!(
            "nested capture through recursive helper {name} is not supported"
        ));
    }
    if let Some(cached) = helpers.memo.get(&(name.clone(), depth)) {
        return Ok(cached.clone());
    }
    if has_annotation(target, |k| matches!(k, AnnotationKind::Skip)) {
        return Err(format!(
            "nested capture through @skip rule {name} is not supported"
        ));
    }
    if has_annotation(target, |k| !matches!(k, AnnotationKind::Doc { .. })) {
        return Err(format!(
            "nested capture through annotated helper {name} is not supported"
        ));
    }
    if has_bindings(&target.body) {
        return Err(format!(
            "nested capture through helper {name} with captures is not supported"
        ));
    }
    helpers.stack.push(name.clone());
    let result = body_shape_in(grammar, &target.body, depth + 1, helpers);
    helpers.stack.pop();
    let result = result?;
    if matches!(parts(result.clone()), (Kind::Value, _)) {
        return Err(format!(
            "nested capture through mixed-choice helper {name} is not supported"
        ));
    }
    helpers.memo.insert((name.clone(), depth), result.clone());
    Ok(result)
}
// AST captures, typeof constraints and general bindings stay owned by the rule that declares them.
fn has_bindings(body: &RuleBody) -> bool {
    body.alternatives.iter().any(|sequence| {
        sequence.elements.iter().any(|element| {
            element.capture.is_some()
                || element.typeof_constraint.is_some()
                || atom_has_bindings(&element.element)
        })
    })
}
fn atom_has_bindings(atom: &AtomicElement) -> bool {
    match &atom.kind {
        ElementKind::OwnedCapture { .. }
        | ElementKind::CaptureScope(_)
        | ElementKind::Replay { .. } => true,
        ElementKind::Group(v)
        | ElementKind::Commit { body: v, .. }
        | ElementKind::Label { body: v, .. }
        | ElementKind::Catch { choice: v, .. }
        | ElementKind::Optional(v)
        | ElementKind::Repeat(v) => has_bindings(v),
        ElementKind::OneOrMore(v) | ElementKind::BoundedRepeat { element: v, .. } => {
            atom_has_bindings(v)
        }
        ElementKind::Separated { element, separator } => {
            atom_has_bindings(element) || atom_has_bindings(separator)
        }
        _ => false,
    }
}
/// The transparent alias whose single body element stands for `atom` inside a nested capture, or
/// `None` when the reference stays an ordinary leaf (token, mapped node, enum, or a helper whose
/// body is a choice / multi-element sequence and therefore one text or i32 value). Only used after
/// `validate` accepted the grammar, so no cycle remains.
pub(crate) fn inlined_alias<'a>(
    grammar: &'a GrammarDecl,
    atom: &AtomicElement,
) -> Option<&'a AtomicElement> {
    let ElementKind::RuleRef { name, .. } = &atom.kind else {
        return None;
    };
    if grammar.tokens.iter().any(|t| &t.name == name) {
        return None;
    }
    let rule = grammar.rules.iter().find(|r| &r.name == name)?;
    if has_annotation(rule, |k| {
        matches!(
            k,
            AnnotationKind::Enum | AnnotationKind::Skip | AnnotationKind::Mapping { .. }
        )
    }) {
        return None;
    }
    let [sequence] = rule.body.alternatives.as_slice() else {
        return None;
    };
    let [element] = sequence.elements.as_slice() else {
        return None;
    };
    Some(&element.element)
}
/// Replaces inlined alias references by their single body element along the nested path, exactly
/// where Java's `ParserRuleEmitter.resolveNested` descends: single-element bodies, repeated items
/// and separated items. Multi-element and choice bodies (and separators) stay ordinary parsers.
pub fn expand_aliases(grammar: &GrammarDecl, atom: &AtomicElement) -> AtomicElement {
    if let Some(element) = inlined_alias(grammar, atom) {
        return expand_aliases(grammar, element);
    }
    let single = |v: &RuleBody| expand_single(grammar, v);
    let boxed = |v: &AtomicElement| Box::new(expand_aliases(grammar, v));
    let kind = match &atom.kind {
        ElementKind::Group(v) => ElementKind::Group(single(v)),
        ElementKind::OwnedCapture { name, body: v } => ElementKind::OwnedCapture {
            name: name.clone(),
            body: single(v),
        },
        ElementKind::CaptureScope(v) => ElementKind::CaptureScope(single(v)),
        ElementKind::Optional(v) => ElementKind::Optional(single(v)),
        ElementKind::Repeat(v) => ElementKind::Repeat(single(v)),
        ElementKind::OneOrMore(v) => ElementKind::OneOrMore(boxed(v)),
        ElementKind::BoundedRepeat { element, min, max } => ElementKind::BoundedRepeat {
            element: boxed(element),
            min: *min,
            max: *max,
        },
        ElementKind::Separated { element, separator } => ElementKind::Separated {
            element: boxed(element),
            separator: separator.clone(),
        },
        other => other.clone(),
    };
    AtomicElement {
        kind,
        span: atom.span,
    }
}
fn expand_single(grammar: &GrammarDecl, body: &RuleBody) -> RuleBody {
    let mut result = body.clone();
    if let [sequence] = result.alternatives.as_mut_slice() {
        if let [element] = sequence.elements.as_mut_slice() {
            element.element = expand_aliases(grammar, &element.element);
        }
    }
    result
}
// First helper reference (source order) anywhere in an element, without entering referenced rules.
fn helper_in<'a>(grammar: &GrammarDecl, atom: &'a AtomicElement) -> Option<&'a str> {
    let body = |v: &'a RuleBody| {
        v.alternatives
            .iter()
            .flat_map(|sequence| &sequence.elements)
            .find_map(|element| helper_in(grammar, &element.element))
    };
    match &atom.kind {
        ElementKind::RuleRef { name, .. } => is_helper(grammar, name).then_some(name.as_str()),
        ElementKind::Group(v)
        | ElementKind::OwnedCapture { body: v, .. }
        | ElementKind::CaptureScope(v)
        | ElementKind::Commit { body: v, .. }
        | ElementKind::Label { body: v, .. }
        | ElementKind::Catch { choice: v, .. }
        | ElementKind::Optional(v)
        | ElementKind::Repeat(v) => body(v),
        ElementKind::OneOrMore(v) | ElementKind::BoundedRepeat { element: v, .. } => {
            helper_in(grammar, v)
        }
        ElementKind::Separated { element, separator } => {
            helper_in(grammar, element).or_else(|| helper_in(grammar, separator))
        }
        _ => None,
    }
}
fn is_helper(grammar: &GrammarDecl, name: &str) -> bool {
    !grammar.tokens.iter().any(|t| t.name == name)
        && grammar.rules.iter().any(|r| {
            r.name == name
                && !has_annotation(r, |k| matches!(k, AnnotationKind::Enum))
                && (has_annotation(r, |k| matches!(k, AnnotationKind::Skip))
                    || !has_annotation(r, |k| matches!(k, AnnotationKind::Mapping { .. })))
        })
}
pub fn body_shape(grammar: &GrammarDecl, body: &RuleBody, depth: usize) -> Result<Kind, String> {
    body_shape_in(grammar, body, depth, &mut Expansion::default())
}
fn body_shape_in(
    grammar: &GrammarDecl,
    body: &RuleBody,
    depth: usize,
    helpers: &mut Expansion,
) -> Result<Kind, String> {
    let mut result = None;
    for sequence in &body.alternatives {
        let current = if let [element] = sequence.elements.as_slice() {
            shape_in(grammar, &element.element, depth + 1, helpers)?
        } else {
            for element in &sequence.elements {
                if matches!(
                    shape_in(grammar, &element.element, depth + 1, helpers)?,
                    Kind::Nested { .. } | Kind::Node | Kind::Enum(_) | Kind::Value
                ) {
                    return Err("multi-element container group needs explicit mapped node".into());
                }
            }
            Kind::Text
        };
        let current = if let Some(old) = &result {
            if matches!(old, Kind::Nested { .. }) || matches!(current, Kind::Nested { .. }) {
                return Err(
                    "choice with container alternatives is not supported yet (#693)".into(),
                );
            }
            if old != &current || matches!(current, Kind::Node | Kind::Enum(_) | Kind::Value) {
                Kind::Value
            } else {
                current
            }
        } else {
            current
        };
        result = Some(current);
    }
    let result = result.ok_or("empty body")?;
    // Mixed sums classify each alternative by its own CST node; a helper anywhere inside an
    // alternative would change that classification and its preferred depth. This also keeps mixed
    // detection of choices outside a capture (lowering `mixed_nested`) unchanged: such choices were
    // never mixed before helpers had a nested shape.
    if matches!(result, Kind::Value) {
        for sequence in &body.alternatives {
            if let Some(name) = sequence
                .elements
                .iter()
                .find_map(|element| helper_in(grammar, &element.element))
            {
                return Err(format!(
                    "mixed choice alternative through helper {name} is not supported"
                ));
            }
        }
    }
    Ok(result)
}
pub fn field(grammar: &GrammarDecl, rule: &RuleDecl, name: &str) -> Result<Kind, String> {
    let values = sites(rule)?;
    let (leaf, containers) = parts(shape(grammar, values[name], 0)?);
    Ok(Kind::Nested {
        leaf: Box::new(leaf),
        containers,
    })
}
pub fn boundary(expression: Expression) -> Expression {
    let (kind, child) = match expression {
        Expression::Sequence(mut v) if v.len() == 1 => {
            let Expression::CaptureBoundary { kind, child } = boundary(v.remove(0)) else {
                unreachable!()
            };
            (kind, Expression::Sequence(vec![*child]))
        }
        Expression::OwnedCapture { name, child } => {
            let Expression::CaptureBoundary { kind, child } = boundary(*child) else {
                unreachable!()
            };
            (kind, Expression::OwnedCapture { name, child })
        }
        Expression::CaptureScope(v) => {
            let Expression::CaptureBoundary { kind, child } = boundary(*v) else {
                unreachable!()
            };
            (kind, Expression::CaptureScope(child))
        }
        Expression::Commit { child, message } => {
            let Expression::CaptureBoundary { kind, child } = boundary(*child) else {
                unreachable!()
            };
            (kind, Expression::Commit { child, message })
        }
        Expression::Label { label, child } => {
            let Expression::CaptureBoundary { kind, child } = boundary(*child) else {
                unreachable!()
            };
            (kind, Expression::Label { label, child })
        }
        Expression::Delimited(v) => {
            let Expression::CaptureBoundary { kind, child } = boundary(*v) else {
                unreachable!()
            };
            (kind, Expression::Delimited(child))
        }
        Expression::OptionalExpr(v) => (
            BoundaryKind::Optional,
            Expression::OptionalExpr(Box::new(boundary(*v))),
        ),
        Expression::Repeat { child, min, max } => (
            BoundaryKind::List,
            Expression::Repeat {
                child: Box::new(boundary(*child)),
                min,
                max,
            },
        ),
        Expression::Separated { child, separator } => (
            BoundaryKind::List,
            Expression::Separated {
                child: Box::new(boundary(*child)),
                separator: Box::new(Expression::CaptureBoundary {
                    kind: BoundaryKind::Separator,
                    child: separator,
                }),
            },
        ),
        other => (BoundaryKind::Value, other),
    };
    Expression::CaptureBoundary {
        kind,
        child: Box::new(child),
    }
}
#[cfg(test)]
mod tests {
    /// 14 levels of helpers that each reference the next one four times: 4^14 alias expansions
    /// without the per-(rule, depth) memo. Java's NestedAliasCaptureConformanceTest checks the same.
    #[test]
    fn shared_multi_element_helpers_validate_in_linear_time() {
        let mut rules = String::new();
        for i in 0..14 {
            let n = i + 1;
            rules.push_str(&format!("H{i} ::= H{n} H{n} H{n} H{n}; "));
        }
        rules.push_str("H14 ::= 'a';");
        let source = format!("grammar G {{ @package: p @root @nestedCaptures @mapping(Root,params=[value]) Root ::= [ H0 ] @value; {rules} }}");
        let file = unlaxer_ubnf::parse(&source).unwrap();
        let started = std::time::Instant::now();
        super::validate(&file.grammars[0]).unwrap();
        let rule = &file.grammars[0].rules[0];
        assert_eq!(
            super::field(&file.grammars[0], rule, "value").unwrap(),
            unlaxer_codegen::Kind::Nested {
                leaf: Box::new(unlaxer_codegen::Kind::Text),
                containers: vec![unlaxer_codegen::Cardinality::Optional],
            }
        );
        assert!(started.elapsed() < std::time::Duration::from_secs(10));
    }
}
