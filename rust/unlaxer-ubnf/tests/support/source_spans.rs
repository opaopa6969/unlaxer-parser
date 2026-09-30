use unlaxer_ubnf::*;

pub fn file(snapshot: &SourceSnapshot) -> Vec<(String, Span)> {
    let mut rows = vec![("file".to_owned(), snapshot.ast().span)];
    for (i, grammar) in snapshot.ast().grammars.iter().enumerate() {
        let p = format!("file.grammars[{i}]");
        rows.push((p.clone(), grammar.span));
        for (i, import) in grammar.imports.iter().enumerate() {
            rows.push((format!("{p}.imports[{i}]"), import.span));
        }
        for (i, setting) in grammar.settings.iter().enumerate() {
            let p = format!("{p}.settings[{i}]");
            rows.push((p.clone(), setting.span));
            rows.push((format!("{p}.value"), setting.value_span));
            if let SettingValue::Block(entries) = &setting.value {
                for (i, entry) in entries.iter().enumerate() {
                    rows.push((format!("{p}.value.entries[{i}]"), entry.span));
                }
            }
        }
        for (i, token) in grammar.tokens.iter().enumerate() {
            rows.push((format!("{p}.tokens[{i}]"), token.span));
        }
        for (i, rule) in grammar.rules.iter().enumerate() {
            let p = format!("{p}.rules[{i}]");
            rows.push((p.clone(), rule.span));
            for (i, annotation) in rule.annotations.iter().enumerate() {
                rows.push((format!("{p}.annotations[{i}]"), annotation.span));
            }
            body(&mut rows, &format!("{p}.body"), &rule.body);
        }
    }
    rows
}

fn body(rows: &mut Vec<(String, Span)>, p: &str, body: &RuleBody) {
    rows.push((p.to_owned(), body.span));
    for (i, sequence) in body.alternatives.iter().enumerate() {
        let p = format!("{p}.alternatives[{i}]");
        rows.push((p.clone(), sequence.span));
        for (i, annotated) in sequence.elements.iter().enumerate() {
            let p = format!("{p}.elements[{i}]");
            rows.push((p.clone(), annotated.span));
            if let Some(span) = annotated.capture_span {
                rows.push((format!("{p}.capture"), span));
            }
            if let Some(span) = annotated.typeof_span {
                rows.push((format!("{p}.typeofConstraint"), span));
            }
            element(rows, &format!("{p}.element"), &annotated.element);
        }
    }
}

fn element(rows: &mut Vec<(String, Span)>, p: &str, elem: &AtomicElement) {
    rows.push((p.to_owned(), elem.span));
    match &elem.kind {
        ElementKind::Group(b) | ElementKind::Optional(b) | ElementKind::Repeat(b) => {
            body(rows, &format!("{p}.body"), b);
        }
        ElementKind::OneOrMore(e) | ElementKind::BoundedRepeat { element: e, .. } => {
            element(rows, &format!("{p}.body"), e);
        }
        ElementKind::Separated {
            element: e,
            separator,
        } => {
            element(rows, &format!("{p}.element"), e);
            element(rows, &format!("{p}.separator"), separator);
        }
        _ => {}
    }
}
