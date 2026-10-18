//! Independently mirror the Java generated token wrappers, not just rule nesting.
use std::collections::HashSet;
use unlaxer_ubnf::ast::*;
type Key = (u8, usize, usize);
#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Text,
    Node,
    Value,
}
#[derive(Clone, Copy, PartialEq)]
enum Count {
    One,
    Optional,
    Many,
}
#[derive(Clone, Copy)]
struct Shape(Kind, Count);
fn text() -> Shape {
    Shape(Kind::Text, Count::One)
}
fn wrap(s: Shape, c: Count) -> Shape {
    if s.0 == Kind::Text {
        s
    } else {
        Shape(
            s.0,
            if s.1 == Count::Many || c == Count::Many {
                Count::Many
            } else if s.1 == Count::Optional || c == Count::Optional {
                Count::Optional
            } else {
                Count::One
            },
        )
    }
}
fn merge(a: Shape, b: Shape, seq: bool) -> Shape {
    Shape(
        if a.0 == b.0 { a.0 } else { Kind::Value },
        if seq || a.1 == Count::Many || b.1 == Count::Many {
            Count::Many
        } else if a.1 == Count::Optional || b.1 == Count::Optional {
            Count::Optional
        } else {
            Count::One
        },
    )
}
fn body_shape(g: &GrammarDecl, b: &RuleBody, seen: &mut HashSet<String>) -> Shape {
    b.alternatives
        .iter()
        .map(|s| seq_shape(g, s, seen))
        .reduce(|a, b| merge(a, b, false))
        .unwrap_or_else(text)
}
fn seq_shape(g: &GrammarDecl, s: &Sequence, seen: &mut HashSet<String>) -> Shape {
    s.elements
        .iter()
        .map(|a| shape(g, &a.element, seen))
        .filter(|s| s.0 != Kind::Text)
        .reduce(|a, b| merge(a, b, true))
        .unwrap_or_else(text)
}
fn shape(g: &GrammarDecl, a: &AtomicElement, seen: &mut HashSet<String>) -> Shape {
    match &a.kind {
        ElementKind::RuleRef { name, .. } => {
            let Some(r) = g.rules.iter().find(|r| &r.name == name) else {
                return text();
            };
            if r.annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Skip))
            {
                return text();
            }
            if r.annotations.iter().any(|a| {
                matches!(
                    a.kind,
                    AnnotationKind::Enum | AnnotationKind::Mapping { .. }
                )
            }) {
                return Shape(Kind::Node, Count::One);
            }
            if !seen.insert(name.clone()) {
                return text();
            }
            let out = body_shape(g, &r.body, seen);
            seen.remove(name);
            out
        }
        ElementKind::Group(b)
        | ElementKind::OwnedCapture { body: b, .. }
        | ElementKind::CaptureScope(b)
        | ElementKind::Commit { body: b, .. }
        | ElementKind::Label { body: b, .. }
        | ElementKind::Catch { choice: b, .. } => body_shape(g, b, seen),
        ElementKind::Optional(b) => wrap(body_shape(g, b, seen), Count::Optional),
        ElementKind::Repeat(b) => wrap(body_shape(g, b, seen), Count::Many),
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => wrap(shape(g, a, seen), Count::Many),
        _ => text(),
    }
}
fn one(b: &RuleBody) -> Option<&AtomicElement> {
    let [s] = b.alternatives.as_slice() else {
        return None;
    };
    let [a] = s.elements.as_slice() else {
        return None;
    };
    Some(&a.element)
}
fn site_body(g: &GrammarDecl, b: &RuleBody) -> Shape {
    one(b).map_or_else(|| body_shape(g, b, &mut HashSet::new()), |a| site(g, a))
}
fn site(g: &GrammarDecl, a: &AtomicElement) -> Shape {
    match &a.kind {
        ElementKind::Optional(b) | ElementKind::Repeat(b) => site_body(g, b),
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => site(g, a),
        _ => shape(g, a, &mut HashSet::new()),
    }
}
fn key(tag: u8, s: Span) -> Key {
    (tag, s.codepoint_start, s.codepoint_end)
}
struct Plan<'a> {
    g: &'a GrammarDecl,
    nested: bool,
    bindings: HashSet<Key>,
    sites: Vec<&'a AtomicElement>,
}
impl<'a> Plan<'a> {
    fn bind_values(&mut self, a: &'a AtomicElement) {
        match &a.kind {
            ElementKind::Optional(b) | ElementKind::Repeat(b) => self.bind_body(b),
            ElementKind::OneOrMore(a)
            | ElementKind::BoundedRepeat { element: a, .. }
            | ElementKind::Separated { element: a, .. } => self.bind_values(a),
            _ => {
                self.bindings.insert(key(2, a.span));
            }
        }
    }
    fn bind_body(&mut self, b: &'a RuleBody) {
        if let Some(a) = one(b) {
            self.bind_values(a)
        } else {
            self.bindings.insert(key(0, b.span));
        }
    }
    fn visit(&mut self, b: &'a RuleBody) {
        for s in &b.alternatives {
            for a in &s.elements {
                if a.capture.is_some() {
                    self.sites.push(&a.element);
                    if self.nested {
                        self.bindings.insert(key(2, a.element.span));
                    } else {
                        self.bind_values(&a.element);
                    }
                }
                self.visit_atom(&a.element);
            }
        }
    }
    fn visit_atom(&mut self, a: &'a AtomicElement) {
        match &a.kind {
            ElementKind::Group(b)
            | ElementKind::OwnedCapture { body: b, .. }
            | ElementKind::CaptureScope(b)
            | ElementKind::Commit { body: b, .. }
            | ElementKind::Label { body: b, .. }
            | ElementKind::Catch { choice: b, .. }
            | ElementKind::Optional(b)
            | ElementKind::Repeat(b) => self.visit(b),
            ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => {
                self.visit_atom(a)
            }
            ElementKind::Separated { element, separator } => {
                self.visit_atom(element);
                self.visit_atom(separator);
            }
            _ => {}
        }
    }
    fn unit_body(&mut self, b: &'a RuleBody) {
        if let Some(a) = one(b) {
            self.bindings.insert(key(2, a.span));
        } else {
            self.bindings.insert(key(0, b.span));
        }
    }
    fn semantic_body(&mut self, b: &'a RuleBody) {
        let mixed = body_shape(self.g, b, &mut HashSet::new()).0 == Kind::Value;
        for s in &b.alternatives {
            if mixed && seq_shape(self.g, s, &mut HashSet::new()).0 == Kind::Text {
                if s.elements.len() == 1 {
                    self.bindings.insert(key(2, s.elements[0].element.span));
                } else {
                    self.bindings.insert(key(1, s.span));
                }
            }
            for a in &s.elements {
                self.semantic_atom(&a.element);
            }
        }
    }
    fn semantic_item_body(&mut self, b: &'a RuleBody) {
        let s = body_shape(self.g, b, &mut HashSet::new());
        if s.0 == Kind::Value && s.1 != Count::Many {
            self.unit_body(b)
        }
        self.semantic_body(b)
    }
    fn semantic_item_atom(&mut self, a: &'a AtomicElement) {
        let s = shape(self.g, a, &mut HashSet::new());
        if s.0 == Kind::Value && s.1 != Count::Many {
            self.bindings.insert(key(2, a.span));
        }
        self.semantic_atom(a)
    }
    fn semantic_atom(&mut self, a: &'a AtomicElement) {
        match &a.kind {
            ElementKind::Group(b)
            | ElementKind::OwnedCapture { body: b, .. }
            | ElementKind::CaptureScope(b)
            | ElementKind::Commit { body: b, .. }
            | ElementKind::Label { body: b, .. }
            | ElementKind::Catch { choice: b, .. } => self.semantic_body(b),
            ElementKind::Optional(b) | ElementKind::Repeat(b) => self.semantic_item_body(b),
            ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => {
                self.semantic_item_atom(a)
            }
            ElementKind::Separated { element, separator } => {
                self.semantic_item_atom(element);
                self.semantic_atom(separator);
            }
            _ => {}
        }
    }
    fn body(&self, b: &RuleBody, depth: usize, out: &mut Vec<(usize, usize)>) {
        for s in &b.alternatives {
            let extra = if b.alternatives.len() > 1 && s.elements.len() > 1 {
                1 + usize::from(self.bindings.contains(&key(1, s.span)))
            } else {
                0
            };
            for a in &s.elements {
                self.atom(&a.element, depth + extra + 1, out);
            }
        }
    }
    fn atom(&self, a: &AtomicElement, depth: usize, out: &mut Vec<(usize, usize)>) {
        if self.nested && self.bindings.contains(&key(2, a.span)) {
            self.nested_atom(a, depth + 1, out);
            return;
        }
        let depth = depth + usize::from(self.bindings.contains(&key(2, a.span)));
        match &a.kind {
            ElementKind::RuleRef { name, .. } => {
                if let Some(id) = self.g.rules.iter().position(|r| &r.name == name) {
                    out.push((id, depth));
                }
            }
            // CatchLabels adds no CST node: each operand is its own group, as on Java (#700).
            ElementKind::Catch { choice, operands } => {
                self.body(&operands.body(choice), depth, out);
                self.body(&operands.alternative(choice), depth, out);
            }
            ElementKind::OwnedCapture { body: b, .. }
            | ElementKind::CaptureScope(b)
            | ElementKind::Commit { body: b, .. }
            | ElementKind::Label { body: b, .. } => {
                if let Some(child) =
                    one(b).filter(|a| matches!(a.kind, ElementKind::RuleRef { .. }))
                {
                    self.atom(child, depth, out)
                } else {
                    self.body(b, depth, out)
                }
            }
            ElementKind::Group(b) => self.body(b, depth, out),
            ElementKind::Optional(b) => {
                if let Some(a) = one(b).filter(|a| {
                    matches!(
                        a.kind,
                        ElementKind::RuleRef { .. } | ElementKind::Terminal(_)
                    )
                }) {
                    self.atom(a, depth + 1, out)
                } else {
                    self.body(b, depth + 1, out)
                }
            }
            ElementKind::Repeat(b) => {
                if let Some(a) = one(b).filter(|a| matches!(a.kind, ElementKind::RuleRef { .. })) {
                    self.atom(a, depth + 1, out)
                } else {
                    self.body(b, depth + 1, out)
                }
            }
            ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => self.atom(
                a,
                depth
                    + if matches!(a.kind, ElementKind::RuleRef { .. }) {
                        1
                    } else {
                        2
                    },
                out,
            ),
            ElementKind::Separated { element, separator } => {
                self.atom(element, depth + 1, out);
                self.atom(separator, depth + 3, out);
            }
            _ => {}
        }
    }
    fn nested_atom(&self, a: &AtomicElement, depth: usize, out: &mut Vec<(usize, usize)>) {
        // A transparent alias stands for its single body element, as in the nested lowering.
        if let Some(element) = crate::nested_captures::inlined_alias(self.g, a) {
            return self.nested_atom(element, depth, out);
        }
        match &a.kind {
            ElementKind::OwnedCapture { body, .. }
            | ElementKind::CaptureScope(body)
            | ElementKind::Commit { body, .. }
            | ElementKind::Label { body, .. }
            | ElementKind::Catch { choice: body, .. }
            | ElementKind::Group(body) => self.nested_body(body, depth, out),
            ElementKind::Optional(body) | ElementKind::Repeat(body) => {
                self.nested_body(body, depth + 2, out)
            }
            ElementKind::OneOrMore(child) | ElementKind::BoundedRepeat { element: child, .. } => {
                self.nested_atom(child, depth + 2, out)
            }
            ElementKind::Separated { element, separator } => {
                self.nested_atom(element, depth + 2, out);
                self.atom(separator, depth + 5, out);
            }
            ElementKind::RuleRef { name, .. } => {
                if let Some(id) = self.g.rules.iter().position(|r| &r.name == name) {
                    out.push((id, depth + 1));
                }
            }
            _ => (),
        }
    }
    fn nested_body(&self, body: &RuleBody, depth: usize, out: &mut Vec<(usize, usize)>) {
        if let Some(child) = one(body) {
            self.nested_atom(child, depth, out);
        } else {
            self.body(body, depth + 1, out);
        }
    }
}
pub(crate) fn calls(g: &GrammarDecl) -> Vec<Vec<(usize, usize)>> {
    let mut plans: Vec<_> = g
        .rules
        .iter()
        .map(|r| {
            let mut p = Plan {
                g,
                nested: crate::nested_captures::enabled(r),
                bindings: HashSet::new(),
                sites: vec![],
            };
            p.visit(&r.body);
            p
        })
        .collect();
    let semantic = plans.iter().zip(&g.rules).any(|(p, r)| {
        !r.annotations.iter().any(|a| {
            matches!(
                a.kind,
                AnnotationKind::LeftAssoc | AnnotationKind::RightAssoc
            )
        }) && p.sites.iter().any(|a| {
            let s = site(g, a);
            s.0 != Kind::Text && s.1 != Count::One
        })
    });
    plans
        .iter_mut()
        .zip(&g.rules)
        .map(|(p, r)| {
            if semantic && !p.nested {
                p.semantic_body(&r.body)
            }
            let mut out = vec![];
            if r.annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::RightAssoc))
            {
                let seq = &r.body.alternatives[0];
                let ElementKind::Repeat(tail) = &seq.elements[1].element.kind else {
                    unreachable!("validated right associative source");
                };
                p.atom(&seq.elements[0].element, 2, &mut out);
                p.atom(&tail.alternatives[0].elements[0].element, 2, &mut out);
                p.atom(&tail.alternatives[0].elements[1].element, 2, &mut out);
                p.atom(&seq.elements[0].element, 1, &mut out);
            } else {
                p.body(&r.body, 0, &mut out);
            }
            out
        })
        .collect()
}
