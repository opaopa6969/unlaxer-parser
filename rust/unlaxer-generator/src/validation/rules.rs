use super::*;
fn push(
    out: &mut Vec<Issue>,
    rule: &RuleDecl,
    code: &str,
    message: impl Into<String>,
    hint: impl Into<String>,
) {
    out.push(Issue::new(code, message, hint, Some(&rule.name)));
}
fn unique<T: PartialEq>(values: impl IntoIterator<Item = T>) -> Vec<T> {
    let mut result = vec![];
    for value in values {
        if !result.contains(&value) {
            result.push(value);
        }
    }
    result
}
fn body_elements<'a>(body: &'a RuleBody, all: bool, out: &mut Vec<&'a AnnotatedElement>) {
    for e in body.alternatives.iter().flat_map(|s| &s.elements) {
        out.push(e);
        atom_elements(&e.element, all, out);
    }
}
fn atom_elements<'a>(atom: &'a AtomicElement, all: bool, out: &mut Vec<&'a AnnotatedElement>) {
    match &atom.kind {
        ElementKind::Group(b)
        | ElementKind::Optional(b)
        | ElementKind::Repeat(b)
        | ElementKind::OwnedCapture { body: b, .. }
        | ElementKind::CaptureScope(b)
        | ElementKind::Commit { body: b, .. }
        | ElementKind::Label { body: b, .. }
        | ElementKind::Catch { choice: b, .. } => body_elements(b, all, out),
        ElementKind::OneOrMore(e) | ElementKind::BoundedRepeat { element: e, .. } if all => {
            atom_elements(e, all, out)
        }
        ElementKind::Separated { element, separator } if all => {
            atom_elements(element, all, out);
            atom_elements(separator, all, out);
        }
        _ => {}
    }
}
fn elements(body: &RuleBody, all: bool) -> Vec<&AnnotatedElement> {
    let mut v = vec![];
    body_elements(body, all, &mut v);
    v
}
fn refs(body: &RuleBody) -> Vec<String> {
    // Match Java's legacy collectReferencedRuleNames exactly: postfix/separated
    // wrappers are not traversed by this query (other analyses do traverse them).
    unique(elements(body, false).iter().filter_map(|e| {
        match &e.element.kind {
            ElementKind::RuleRef { namespace, name } => Some(
                namespace
                    .as_ref()
                    .map_or_else(|| name.clone(), |n| format!("{n}.{name}")),
            ),
            _ => None,
        }
    }))
}
fn assoc(rule: &RuleDecl) -> (&'static str, bool, bool) {
    let left = has(rule, |a| matches!(a, AnnotationKind::LeftAssoc));
    let right = has(rule, |a| matches!(a, AnnotationKind::RightAssoc));
    (
        if left && right {
            "BOTH"
        } else if left {
            "LEFT"
        } else if right {
            "RIGHT"
        } else {
            "NONE"
        },
        left,
        right,
    )
}
fn level(rule: &RuleDecl) -> Option<i32> {
    rule.annotations.iter().rev().find_map(|a| match a.kind {
        AnnotationKind::Precedence { level } => Some(level),
        _ => None,
    })
}
fn canonical(rule: &RuleDecl) -> bool {
    if rule.body.alternatives.len() != 1 || rule.body.alternatives[0].elements.len() != 2 {
        return false;
    }
    let ElementKind::Repeat(b) = &rule.body.alternatives[0].elements[1].element.kind else {
        return false;
    };
    b.alternatives.len() == 1
        && b.alternatives[0].elements.len() == 2
        && matches!(&b.alternatives[0].elements[1].element.kind,ElementKind::RuleRef {name,..} if name==&rule.name)
}
pub(super) fn collect(grammar: &GrammarDecl, out: &mut Vec<Issue>) {
    // Bounded definitions precede per-rule annotation diagnostics in Java.
    for rule in &grammar.rules {
        bounded(&rule.body, rule, out);
    }
    for rule in &grammar.rules {
        let name = &rule.name;
        let all = elements(&rule.body, true);
        let captures = unique(all.iter().filter_map(|e| e.capture.as_deref()));
        let constraints: Vec<_> = all
            .iter()
            .filter(|e| e.typeof_constraint.is_some())
            .collect();
        let constraint_names = unique(
            constraints
                .iter()
                .flat_map(|e| [e.capture.as_deref(), e.typeof_constraint.as_deref()])
                .flatten(),
        );
        let mapping = rule.annotations.iter().rev().find_map(|a| match &a.kind {
            AnnotationKind::Mapping { class_name, params } => Some((class_name, params)),
            _ => None,
        });
        let (_, left, right) = assoc(rule);
        let count = |test: fn(&AnnotationKind) -> bool| {
            rule.annotations.iter().filter(|a| test(&a.kind)).count()
        };
        let spaces = count(|a| matches!(a, AnnotationKind::Whitespace { .. }));
        for a in &rule.annotations {
            if let AnnotationKind::Whitespace { style } = &a.kind {
                let style = style.as_deref().unwrap_or("javaStyle").trim();
                if !whitespace(grammar, style) {
                    push(out,rule,"E-WHITESPACE-RULE-STYLE",format!("rule {name} uses unsupported @whitespace style: {style} (allowed: javaStyle, none, non-nullable declarative token)"),"Use @whitespace, @whitespace(none), or @whitespace(TOKEN_NAME).");
                }
            }
        }
        if spaces > 1 {
            push(
                out,
                rule,
                "E-WHITESPACE-RULE-DUPLICATE",
                format!("rule {name} has duplicate @whitespace annotations"),
                "Keep a single @whitespace annotation.",
            );
        }
        if let Some((class, params)) = mapping {
            let mut seen = vec![];
            let mut duplicates = vec![];
            for param in params {
                if seen.contains(&param) {
                    if !duplicates.contains(&param.as_str()) {
                        duplicates.push(param.as_str());
                    }
                } else {
                    seen.push(param);
                }
            }
            if !duplicates.is_empty() {
                push(
                    out,
                    rule,
                    "E-MAPPING-DUPLICATE-PARAM",
                    format!(
                        "rule {name} @mapping({class}) has duplicate params: [{}]",
                        duplicates.join(", ")
                    ),
                    "Remove duplicate parameter names in @mapping params.",
                );
            }
            for param in seen {
                if !captures.contains(&param.as_str()) {
                    push(
                        out,
                        rule,
                        "E-MAPPING-MISSING-CAPTURE",
                        format!(
                            "rule {name} @mapping({class}) param '{param}' has no matching capture"
                        ),
                        format!("Add @{param} capture in the rule body or remove it from params."),
                    );
                }
            }
            for capture in &captures {
                if !params.iter().any(|p| p == capture) && !constraint_names.contains(capture) {
                    push(out,rule,"E-MAPPING-UNLISTED-CAPTURE",format!("rule {name} has capture @{capture} not listed in @mapping({class}) params"),format!("Add '{capture}' to @mapping params."));
                }
            }
        }
        if left && right {
            push(
                out,
                rule,
                "E-ASSOC-BOTH",
                format!("rule {name} cannot use both @leftAssoc and @rightAssoc"),
                "Keep exactly one associativity annotation per rule.",
            );
        } else if left || right {
            let assoc = if right { "@rightAssoc" } else { "@leftAssoc" };
            if let Some((class, params)) = mapping {
                for required in ["left", "op", "right"] {
                    if !params.iter().any(|p| p == required) {
                        push(out,rule,"E-ASSOC-MAPPING-PARAM",format!("rule {name} uses {assoc} but @mapping({class}) params does not contain '{required}'"),"Include left/op/right in @mapping params.");
                    }
                }
            } else {
                push(
                    out,
                    rule,
                    "E-ASSOC-NO-MAPPING",
                    format!("rule {name} uses {assoc} but has no @mapping"),
                    "Add @mapping(ClassName, params=[left, op, right]) to this rule.",
                );
            }
            for required in ["left", "op", "right"] {
                if !captures.contains(&required) {
                    push(
                        out,
                        rule,
                        "E-ASSOC-MISSING-CAPTURE",
                        format!("rule {name} uses {assoc} but capture @{required} is missing"),
                        format!("Add @{required} capture in the rule body."),
                    );
                }
            }
            if !elements(&rule.body, false)
                .iter()
                .any(|e| matches!(e.element.kind, ElementKind::Repeat(_)))
            {
                push(
                    out,
                    rule,
                    "E-ASSOC-NO-REPEAT",
                    format!("rule {name} uses {assoc} but has no repeat segment"),
                    "Use canonical operator pattern: Base { Op Right }.",
                );
            }
            if right && !canonical(rule) {
                push(out,rule,"E-RIGHTASSOC-NONCANONICAL",format!("rule {name} uses @rightAssoc but body is not canonical: expected Base {{ Op {name} }}"),format!("Rewrite right-assoc rule as Base {{ op {name} }}."));
            }
        }
        let longest = count(|a| matches!(a, AnnotationKind::LongestChoice));
        let predictive = count(|a| matches!(a, AnnotationKind::PredictiveChoice));
        let unique = count(|a| matches!(a, AnnotationKind::UniqueLongestChoice));
        if unique > 0 {
            if unique > 1 {
                push(
                    out,
                    rule,
                    "E-UNIQUE-LONGEST-DUPLICATE",
                    "duplicate @uniqueLongestChoice",
                    "Keep a single profile.",
                );
            }
            if rule.body.alternatives.len() < 2 {
                push(
                    out,
                    rule,
                    "E-UNIQUE-LONGEST-SHAPE",
                    "@uniqueLongestChoice requires multiple alternatives",
                    "Use 2 to 64 alternatives.",
                );
            } else if rule.body.alternatives.len() > 64 {
                push(
                    out,
                    rule,
                    "E-UNIQUE-LONGEST-LIMIT",
                    "@uniqueLongestChoice exceeds 64 alternatives",
                    "Split the dispatch rule.",
                );
            }
            if left || right || longest > 0 || predictive > 0 {
                push(
                    out,
                    rule,
                    "E-UNIQUE-LONGEST-CONFLICT",
                    "conflicting @uniqueLongestChoice profiles",
                    "Use one choice profile and no associativity rewriting.",
                );
            }
        }
        for (count, label, code, hint) in [
            (
                longest,
                "longestChoice",
                "LONGEST-CHOICE",
                "Move longest matching to a separate dispatch rule.",
            ),
            (
                predictive,
                "predictiveChoice",
                "PREDICTIVE-CHOICE",
                "Move predictive dispatch to a separate rule.",
            ),
        ] {
            if count == 0 {
                continue;
            }
            if count > 1 {
                push(
                    out,
                    rule,
                    &format!("E-{code}-DUPLICATE"),
                    format!("rule {name} has duplicate @{label} annotations"),
                    format!("Keep a single @{label} annotation."),
                );
            }
            if rule.body.alternatives.len() < 2 {
                push(
                    out,
                    rule,
                    &format!("E-{code}-SHAPE"),
                    format!("rule {name} uses @{label} without multiple alternatives"),
                    format!("Use @{label} only on a rule with at least two '|' alternatives."),
                );
            }
            if left || right {
                push(
                    out,
                    rule,
                    &format!("E-{code}-ASSOC"),
                    format!("rule {name} combines @{label} with associativity rewriting"),
                    hint,
                );
            }
        }
        if longest > 0 && predictive > 0 {
            push(
                out,
                rule,
                "E-PREDICTIVE-CHOICE-CONFLICT",
                format!("rule {name} combines @predictiveChoice with @longestChoice"),
                "Choose ordered predictive dispatch or longest-match dispatch.",
            );
        }
        let precedences: Vec<_> = rule
            .annotations
            .iter()
            .filter_map(|a| match a.kind {
                AnnotationKind::Precedence { level } => Some((level, a.span)),
                _ => None,
            })
            .collect();
        if precedences.len() > 1 {
            push(
                out,
                rule,
                "E-PRECEDENCE-DUPLICATE",
                format!("rule {name} has duplicate @precedence annotations"),
                "Keep a single @precedence(level=...) annotation.",
            );
        }
        for (level, span) in &precedences {
            if *level == i32::MIN {
                push(
                    out,
                    rule,
                    "E-PRECEDENCE-OVERFLOW",
                    format!(
                        "precedence level exceeds 2147483647 at CP {}..{}",
                        span.codepoint_start, span.codepoint_end
                    ),
                    "Use a level between 0 and 2147483647.",
                );
            } else if *level < 0 {
                push(
                    out,
                    rule,
                    "E-PRECEDENCE-NEGATIVE",
                    format!("rule {name} has invalid @precedence level: {level}"),
                    "Use a non-negative integer (e.g. @precedence(level=10)).",
                );
            }
        }
        if !precedences.is_empty() && !left && !right {
            push(
                out,
                rule,
                "E-PRECEDENCE-NO-ASSOC",
                format!("rule {name} uses @precedence but has no @leftAssoc/@rightAssoc"),
                "Add one associativity annotation alongside @precedence.",
            );
        }
        for (label, code, parameter, cnt) in [
            (
                "interleave",
                "INTERLEAVE",
                "profile",
                count(|a| matches!(a, AnnotationKind::Interleave { .. })),
            ),
            (
                "backref",
                "BACKREF",
                "name",
                count(|a| matches!(a, AnnotationKind::Backref { .. })),
            ),
            (
                "scopeTree",
                "SCOPETREE",
                "mode",
                count(|a| matches!(a, AnnotationKind::ScopeTree { .. })),
            ),
        ] {
            if cnt > 1 {
                push(
                    out,
                    rule,
                    &format!("E-ANNOTATION-DUPLICATE-{code}"),
                    format!("rule {name} has duplicate @{label} annotations"),
                    format!("Keep a single @{label}({parameter}=...) annotation."),
                );
            }
            let first = rule.annotations.iter().find_map(|a| match &a.kind {
                AnnotationKind::Interleave { profile } if label == "interleave" => {
                    Some(profile.trim())
                }
                AnnotationKind::ScopeTree { mode } if label == "scopeTree" => Some(mode.trim()),
                _ => None,
            });
            if let Some(value) = first {
                let (allowed, hint) = if label == "interleave" {
                    (["javaStyle","commentsAndSpaces"],"Use @interleave(profile=javaStyle) or @interleave(profile=commentsAndSpaces).")
                } else {
                    (
                        ["lexical", "dynamic"],
                        "Use @scopeTree(mode=lexical) or @scopeTree(mode=dynamic).",
                    )
                };
                if !allowed.contains(&value) {
                    push(
                        out,
                        rule,
                        &format!("E-ANNOTATION-{code}-{}", parameter.to_ascii_uppercase()),
                        format!("rule {name} uses unsupported @{label} {parameter}: {value}"),
                        hint,
                    );
                }
            }
        }
        if count(|a| matches!(a, AnnotationKind::Declares { .. })) > 1 {
            push(
                out,
                rule,
                "E-ANNOTATION-DUPLICATE-DECLARES",
                format!("rule {name} has duplicate @declares annotations"),
                "Keep a single @declares(symbol=...) annotation.",
            );
        }
        for annotation in &rule.annotations {
            let (target, code) = match &annotation.kind {
                AnnotationKind::Declares { symbol_capture, .. } => {
                    (symbol_capture, "E-ANNOTATION-DECLARES-CAPTURE")
                }
                AnnotationKind::Backref { name } => (name, "E-ANNOTATION-BACKREF-CAPTURE"),
                _ => continue,
            };
            if !captures.contains(&target.as_str()) {
                push(
                    out,
                    rule,
                    code,
                    format!("rule {name} has no scope capture: {target}"),
                    format!("Capture the target in this rule's body using @{target}."),
                );
            }
        }
        let hint = "Use named captures in the same mapped rule; forward references are allowed.";
        if !constraints.is_empty()
            && (mapping.is_none()
                || has(rule, |a| {
                    matches!(a, AnnotationKind::Skip | AnnotationKind::Enum)
                }))
        {
            push(
                out,
                rule,
                "E-TYPEOF-MAPPING",
                "@typeof requires a non-skipped @mapping rule",
                hint,
            );
        }
        for e in constraints {
            if e.capture.as_ref().is_none_or(|s| s.is_empty()) {
                push(
                    out,
                    rule,
                    "E-TYPEOF-MISSING-CAPTURE",
                    "@typeof must be paired with its own capture",
                    hint,
                );
            }
            let target = e.typeof_constraint.as_ref().unwrap();
            if !captures.contains(&target.as_str()) {
                push(
                    out,
                    rule,
                    "E-TYPEOF-UNKNOWN-CAPTURE",
                    format!("@typeof refers to unknown capture '{target}'"),
                    hint,
                );
            }
        }
        if has(rule, |a| matches!(a, AnnotationKind::Catalog { .. })) && captures.is_empty() {
            push(
                out,
                rule,
                "E-ANNOTATION-CATALOG-NO-CAPTURE",
                format!("rule {name} has @catalog but no capture names in its body"),
                "Add at least one @captureName in the rule body for catalog completion to work.",
            );
        }
    }
    for rule in &grammar.rules {
        let Some(precedence) = level(rule).filter(|v| *v != i32::MIN) else {
            continue;
        };
        if assoc(rule).0 == "NONE" {
            continue;
        }
        for reference in refs(&rule.body) {
            if reference == rule.name {
                continue;
            }
            let Some(other) = grammar.rules.iter().find(|r| r.name == reference) else {
                continue;
            };
            let Some(other_level) = level(other).filter(|v| *v != i32::MIN) else {
                continue;
            };
            if assoc(other).0 != "NONE" && other_level <= precedence {
                push(out,rule,"E-PRECEDENCE-ORDER",format!("rule {} precedence {precedence} must be lower than referenced operator rule {reference} precedence {other_level}",rule.name),format!("Decrease {} level or increase {reference} level.",rule.name));
            }
        }
    }
    let mut by_level = std::collections::BTreeMap::new();
    for rule in &grammar.rules {
        let (kind, _, _) = assoc(rule);
        if kind == "NONE" || kind == "BOTH" {
            continue;
        }
        if let Some(level) = level(rule) {
            if level == i32::MIN {
                continue;
            }
            let existing = by_level.entry(level).or_insert(kind);
            if *existing != kind {
                push(
                    out,
                    rule,
                    "E-PRECEDENCE-MIXED-ASSOC",
                    format!("precedence level {level} mixes associativity: {existing} and {kind}"),
                    "Use one associativity per precedence level.",
                );
            }
        } else {
            push(
                out,
                rule,
                "E-ASSOC-NO-PRECEDENCE",
                format!(
                    "rule {} uses @{}Assoc but has no @precedence",
                    rule.name,
                    kind.to_ascii_lowercase()
                ),
                "Add @precedence(level=...) to this operator rule.",
            );
        }
    }
    let known = unique(
        grammar
            .rules
            .iter()
            .map(|r| r.name.as_str())
            .chain(grammar.tokens.iter().map(|t| t.name.as_str())),
    );
    for rule in &grammar.rules {
        for reference in refs(&rule.body) {
            if known.contains(&reference.as_str())
                || reference
                    .split_once('.')
                    .is_some_and(|(alias, _)| grammar.imports.iter().any(|i| i.alias == alias))
            {
                continue;
            }
            let closest = known
                .iter()
                .map(|n| (*n, distance(&reference, n)))
                .min_by_key(|(_, d)| *d)
                .filter(|(_, d)| *d <= 2);
            let hint = closest.map_or_else(
                || format!("Define a rule or token named '{reference}'."),
                |(name, _)| format!("Did you mean '{name}'?"),
            );
            push(
                out,
                rule,
                "E-RULE-UNDEFINED",
                format!(
                    "rule {} references undefined rule or token '{reference}'",
                    rule.name
                ),
                hint,
            );
        }
    }
}
fn distance(a: &str, b: &str) -> usize {
    let b: Vec<_> = b.encode_utf16().collect();
    let mut row: Vec<_> = (0..=b.len()).collect();
    for (i, a) in a.encode_utf16().enumerate() {
        let mut previous = row[0];
        row[0] = i + 1;
        for (j, b) in b.iter().enumerate() {
            let old = row[j + 1];
            row[j + 1] = (row[j] + 1)
                .min(old + 1)
                .min(previous + usize::from(a != *b));
            previous = old;
        }
    }
    row[b.len()]
}
fn bounded(body: &RuleBody, rule: &RuleDecl, out: &mut Vec<Issue>) {
    for e in body.alternatives.iter().flat_map(|s| &s.elements) {
        bounded_atom(&e.element, rule, out);
    }
}
fn bounded_atom(atom: &AtomicElement, rule: &RuleDecl, out: &mut Vec<Issue>) {
    match &atom.kind {
        ElementKind::BoundedRepeat { element, min, max } => {
            if *min > i32::MAX as u32 || max.is_some_and(|n| n > i32::MAX as u32) {
                push(
                    out,
                    rule,
                    "E-BOUNDED-OVERFLOW",
                    format!(
                        "repetition bound exceeds 2147483647 at CP {}..{}",
                        atom.span.codepoint_start, atom.span.codepoint_end
                    ),
                    "Use bounds between 0 and 2147483647.",
                );
            } else if max.is_some_and(|n| n < *min) {
                push(
                    out,
                    rule,
                    "E-BOUNDED-INVALID",
                    format!(
                        "rule {} has BoundedRepeatElement with min={min}, max={}",
                        rule.name,
                        max.unwrap()
                    ),
                    "Ensure 0 <= min <= max in {min,max} quantifier.",
                );
            }
            bounded_atom(element, rule, out);
        }
        ElementKind::OneOrMore(e) => bounded_atom(e, rule, out),
        ElementKind::Separated { element, separator } => {
            bounded_atom(element, rule, out);
            bounded_atom(separator, rule, out);
        }
        ElementKind::Group(b)
        | ElementKind::Optional(b)
        | ElementKind::Repeat(b)
        | ElementKind::OwnedCapture { body: b, .. }
        | ElementKind::CaptureScope(b)
        | ElementKind::Commit { body: b, .. }
        | ElementKind::Label { body: b, .. }
        | ElementKind::Catch { choice: b, .. } => bounded(b, rule, out),
        _ => {}
    }
}
