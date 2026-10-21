//! Independently mirror the Java generated token wrappers, not just rule nesting.
use std::cell::OnceCell;
use std::collections::{HashMap, HashSet};
use unlaxer_ubnf::ast::*;
type Key = (u8, usize, usize);
#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Empty,
    Text,
    Node,
    Value,
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum Count {
    One,
    Optional,
    Many,
}
#[derive(Clone, Copy, PartialEq, Eq)]
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
struct RecursiveShapes<'a> {
    g: &'a GrammarDecl,
    indices: HashMap<&'a str, usize>,
    values: OnceCell<Result<Vec<Shape>, String>>,
}
impl<'a> RecursiveShapes<'a> {
    fn new(g: &'a GrammarDecl) -> Self {
        Self {
            g,
            indices: g
                .rules
                .iter()
                .enumerate()
                .map(|(id, r)| (r.name.as_str(), id))
                .collect(),
            values: OnceCell::new(),
        }
    }
    fn rule(&self, id: usize) -> Result<Shape, String> {
        // SemanticCardinality.shape → RecursiveSemanticShapes.rule と同じく、再帰時だけ解く。
        self.values
            .get_or_init(|| recursive_shapes(self.g, &self.indices))
            .as_ref()
            .map(|values| values[id])
            .map_err(Clone::clone)
    }
}
// MapperElementUtil.containsMappedValue の RuleRef 分岐と同じ探索。
// 規則本体は collectRuleRefs と同じ順に走査し、visited は枝を戻っても消さない。
fn contains_mapped_value(
    name: &str,
    g: &GrammarDecl,
    indices: &HashMap<&str, usize>,
    visited: &mut HashSet<String>,
) -> bool {
    if !visited.insert(name.to_owned()) {
        return false;
    }
    let Some(&id) = indices.get(name) else {
        return false;
    };
    let rule = &g.rules[id];
    !rule
        .annotations
        .iter()
        .any(|a| matches!(a.kind, AnnotationKind::Skip))
        && (rule.annotations.iter().any(|a| {
            matches!(
                a.kind,
                AnnotationKind::Mapping { .. } | AnnotationKind::Enum
            )
        }) || body_contains_mapped_ref(&rule.body, g, indices, visited))
}
fn body_contains_mapped_ref(
    body: &RuleBody,
    g: &GrammarDecl,
    indices: &HashMap<&str, usize>,
    visited: &mut HashSet<String>,
) -> bool {
    body.alternatives.iter().any(|sequence| {
        sequence
            .elements
            .iter()
            .any(|item| contains_mapped_ref(&item.element, g, indices, visited))
    })
}
fn contains_mapped_ref(
    a: &AtomicElement,
    g: &GrammarDecl,
    indices: &HashMap<&str, usize>,
    visited: &mut HashSet<String>,
) -> bool {
    match &a.kind {
        ElementKind::RuleRef { name, .. } => contains_mapped_value(name, g, indices, visited),
        ElementKind::Group(b)
        | ElementKind::OwnedCapture { body: b, .. }
        | ElementKind::CaptureScope(b)
        | ElementKind::Commit { body: b, .. }
        | ElementKind::Label { body: b, .. }
        | ElementKind::Catch { choice: b, .. }
        | ElementKind::Optional(b)
        | ElementKind::Repeat(b) => body_contains_mapped_ref(b, g, indices, visited),
        ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => {
            contains_mapped_ref(a, g, indices, visited)
        }
        ElementKind::Separated { element, separator } => {
            contains_mapped_ref(element, g, indices, visited)
                || contains_mapped_ref(separator, g, indices, visited)
        }
        _ => false,
    }
}
fn body_shape(
    g: &GrammarDecl,
    b: &RuleBody,
    seen: &mut HashSet<String>,
    recursive: &RecursiveShapes<'_>,
) -> Result<Shape, String> {
    let mut result = None;
    for sequence in &b.alternatives {
        let candidate = seq_shape(g, sequence, seen, recursive)?;
        result = Some(result.map_or(candidate, |r| merge(r, candidate, false)));
    }
    Ok(result.unwrap_or_else(text))
}
fn seq_shape(
    g: &GrammarDecl,
    s: &Sequence,
    seen: &mut HashSet<String>,
    recursive: &RecursiveShapes<'_>,
) -> Result<Shape, String> {
    let mut result = None;
    for item in &s.elements {
        let candidate = shape(g, &item.element, seen, recursive)?;
        if candidate.0 != Kind::Text {
            result = Some(result.map_or(candidate, |r| merge(r, candidate, true)));
        }
    }
    Ok(result.unwrap_or_else(text))
}
fn shape(
    g: &GrammarDecl,
    a: &AtomicElement,
    seen: &mut HashSet<String>,
    recursive: &RecursiveShapes<'_>,
) -> Result<Shape, String> {
    Ok(match &a.kind {
        ElementKind::RuleRef { name, .. } => {
            let Some(&id) = recursive.indices.get(name.as_str()) else {
                return Ok(text());
            };
            let r = &g.rules[id];
            if r.annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Skip))
            {
                return Ok(text());
            }
            if r.annotations.iter().any(|a| {
                matches!(
                    a.kind,
                    AnnotationKind::Enum | AnnotationKind::Mapping { .. }
                )
            }) {
                return Ok(Shape(Kind::Node, Count::One));
            }
            if !contains_mapped_value(name, g, &recursive.indices, &mut HashSet::new()) {
                return Ok(text());
            }
            if !seen.insert(name.clone()) {
                return recursive.rule(id);
            }
            let out = body_shape(g, &r.body, seen, recursive)?;
            seen.remove(name);
            out
        }
        ElementKind::Group(b)
        | ElementKind::OwnedCapture { body: b, .. }
        | ElementKind::CaptureScope(b)
        | ElementKind::Commit { body: b, .. }
        | ElementKind::Label { body: b, .. }
        | ElementKind::Catch { choice: b, .. } => body_shape(g, b, seen, recursive)?,
        ElementKind::Optional(b) => wrap(body_shape(g, b, seen, recursive)?, Count::Optional),
        ElementKind::Repeat(b) => wrap(body_shape(g, b, seen, recursive)?, Count::Many),
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => {
            wrap(shape(g, a, seen, recursive)?, Count::Many)
        }
        _ => text(),
    })
}
// Java の RecursiveSemanticShapes と同じ有限固定点を使う。再帰を Text で打ち切ると、
// Node の反復を Value と誤認し、存在しない semantic wrapper を深さに加えてしまう (#730)。
// IR 版は lowering.rs の Lowering::recursive_shapes / fixed_shape を参照。
fn recursive_shapes(g: &GrammarDecl, indices: &HashMap<&str, usize>) -> Result<Vec<Shape>, String> {
    let terminal = |r: &RuleDecl| {
        if r.annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::Skip))
        {
            Some(text())
        } else if r.annotations.iter().any(|a| {
            matches!(
                a.kind,
                AnnotationKind::Enum | AnnotationKind::Mapping { .. }
            )
        }) {
            Some(Shape(Kind::Node, Count::One))
        } else {
            None
        }
    };
    let mut current: Vec<_> = g.rules.iter().map(terminal).collect();
    let mut work = 0;
    for _ in 0..4 * g.rules.len() + 4 {
        let mut next = current.clone();
        for (id, rule) in g.rules.iter().enumerate() {
            if terminal(rule).is_none() {
                next[id] = fixed_body(&rule.body, indices, &current, &mut work, 0)?;
            }
        }
        if next == current {
            return Ok(next
                .into_iter()
                .map(|s| s.filter(|s| s.0 != Kind::Empty).unwrap_or_else(text))
                .collect());
        }
        current = next;
    }
    Err("E-RECURSIVE-SHAPE: transparent shape analysis did not converge".into())
}
// None は有限導出が未確定の枝、Empty は省略可能な枝。文字列の選択肢と混同しない。
fn empty() -> Shape {
    Shape(Kind::Empty, Count::Optional)
}
fn fixed_wrap(s: Option<Shape>, count: Count, nullable: bool) -> Option<Shape> {
    match s {
        None if nullable => Some(empty()),
        Some(s) if s.0 == Kind::Empty => Some(s),
        other => other.map(|s| wrap(s, count)),
    }
}
fn fixed_join(a: Shape, b: Shape, sequence: bool) -> Shape {
    if a.0 == Kind::Empty {
        if sequence {
            b
        } else {
            wrap(b, Count::Optional)
        }
    } else if b.0 == Kind::Empty {
        if sequence {
            a
        } else {
            wrap(a, Count::Optional)
        }
    } else {
        merge(a, b, sequence)
    }
}
fn fixed_body(
    b: &RuleBody,
    indices: &HashMap<&str, usize>,
    state: &[Option<Shape>],
    work: &mut usize,
    depth: usize,
) -> Result<Option<Shape>, String> {
    check_work(work, depth)?;
    let mut result = None;
    for sequence in &b.alternatives {
        // Java body(ChoiceBody) → body(SequenceBody) → element の各呼出しを数える。
        check_work(work, depth + 1)?;
        let mut value = Some(empty());
        let mut has_text = false;
        for item in &sequence.elements {
            match fixed_atom(&item.element, indices, state, work, depth + 2)? {
                None => {
                    value = None;
                    break;
                }
                Some(s) if s.0 == Kind::Text => has_text = true,
                Some(s) => value = value.map(|v| fixed_join(v, s, true)),
            }
        }
        if has_text && value == Some(empty()) {
            value = Some(text());
        }
        if let Some(candidate) = value {
            result = Some(result.map_or(candidate, |r| fixed_join(r, candidate, false)));
        }
    }
    Ok(result)
}
fn fixed_atom(
    a: &AtomicElement,
    indices: &HashMap<&str, usize>,
    state: &[Option<Shape>],
    work: &mut usize,
    depth: usize,
) -> Result<Option<Shape>, String> {
    check_work(work, depth)?;
    Ok(match &a.kind {
        ElementKind::RuleRef { name, .. } => indices
            .get(name.as_str())
            .map_or(Some(text()), |&id| state[id]),
        ElementKind::Group(b)
        | ElementKind::OwnedCapture { body: b, .. }
        | ElementKind::CaptureScope(b)
        | ElementKind::Commit { body: b, .. }
        | ElementKind::Label { body: b, .. }
        | ElementKind::Catch { choice: b, .. } => fixed_body(b, indices, state, work, depth + 1)?,
        ElementKind::Optional(b) | ElementKind::Repeat(b) => {
            let postfix = one(b).filter(|_| a.span.codepoint_start == b.span.codepoint_start);
            let child = if let Some(child) = postfix {
                // Java の postfix ?/* は合成 SequenceBody で、ChoiceBody を挟まない。
                check_work(work, depth + 1)?;
                fixed_atom(child, indices, state, work, depth + 2)?
            } else {
                fixed_body(b, indices, state, work, depth + 1)?
            };
            fixed_wrap(
                child,
                if matches!(a.kind, ElementKind::Optional(_)) {
                    Count::Optional
                } else {
                    Count::Many
                },
                true,
            )
        }
        ElementKind::OneOrMore(a) => fixed_wrap(
            fixed_atom(a, indices, state, work, depth + 1)?,
            Count::Many,
            false,
        ),
        ElementKind::BoundedRepeat { element, min, .. } => fixed_wrap(
            fixed_atom(element, indices, state, work, depth + 1)?,
            Count::Many,
            *min == 0,
        ),
        ElementKind::Separated { element, separator } => {
            if matches!(fixed_atom(separator, indices, state, work, depth + 1)?, Some(s) if s.0 != Kind::Text && s.0 != Kind::Empty)
            {
                return Err("Mapped semantic separator is unsupported".into());
            }
            fixed_wrap(
                fixed_atom(element, indices, state, work, depth + 1)?,
                Count::Many,
                false,
            )
        }
        _ => Some(text()),
    })
}
fn check_work(work: &mut usize, depth: usize) -> Result<(), String> {
    *work += 1;
    if *work > 1_000_000 {
        return Err("E-RECURSIVE-SHAPE: analysis work exceeds 1000000".into());
    }
    if depth > 256 {
        return Err("E-RECURSIVE-SHAPE: expression depth exceeds 256".into());
    }
    Ok(())
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
fn site_body(
    g: &GrammarDecl,
    b: &RuleBody,
    recursive: &RecursiveShapes<'_>,
) -> Result<Shape, String> {
    one(b).map_or_else(
        || body_shape(g, b, &mut HashSet::new(), recursive),
        |a| site(g, a, recursive),
    )
}
fn site(
    g: &GrammarDecl,
    a: &AtomicElement,
    recursive: &RecursiveShapes<'_>,
) -> Result<Shape, String> {
    match &a.kind {
        ElementKind::Optional(b) | ElementKind::Repeat(b) => site_body(g, b, recursive),
        ElementKind::OneOrMore(a)
        | ElementKind::BoundedRepeat { element: a, .. }
        | ElementKind::Separated { element: a, .. } => site(g, a, recursive),
        _ => shape(g, a, &mut HashSet::new(), recursive),
    }
}
fn key(tag: u8, s: Span) -> Key {
    (tag, s.codepoint_start, s.codepoint_end)
}
struct Plan<'a> {
    g: &'a GrammarDecl,
    nested: bool,
    recursive: &'a RecursiveShapes<'a>,
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
    fn semantic_body(&mut self, b: &'a RuleBody) -> Result<(), String> {
        let mixed = body_shape(self.g, b, &mut HashSet::new(), self.recursive)?.0 == Kind::Value;
        for s in &b.alternatives {
            if mixed && seq_shape(self.g, s, &mut HashSet::new(), self.recursive)?.0 == Kind::Text {
                if s.elements.len() == 1 {
                    self.bindings.insert(key(2, s.elements[0].element.span));
                } else {
                    self.bindings.insert(key(1, s.span));
                }
            }
            for a in &s.elements {
                self.semantic_atom(&a.element)?;
            }
        }
        Ok(())
    }
    fn semantic_item_body(&mut self, b: &'a RuleBody) -> Result<(), String> {
        let s = body_shape(self.g, b, &mut HashSet::new(), self.recursive)?;
        if s.0 == Kind::Value && s.1 != Count::Many {
            self.unit_body(b)
        }
        self.semantic_body(b)
    }
    fn semantic_item_atom(&mut self, a: &'a AtomicElement) -> Result<(), String> {
        let s = shape(self.g, a, &mut HashSet::new(), self.recursive)?;
        if s.0 == Kind::Value && s.1 != Count::Many {
            self.bindings.insert(key(2, a.span));
        }
        self.semantic_atom(a)
    }
    fn semantic_atom(&mut self, a: &'a AtomicElement) -> Result<(), String> {
        match &a.kind {
            ElementKind::Group(b)
            | ElementKind::OwnedCapture { body: b, .. }
            | ElementKind::CaptureScope(b)
            | ElementKind::Commit { body: b, .. }
            | ElementKind::Label { body: b, .. }
            | ElementKind::Catch { choice: b, .. } => self.semantic_body(b)?,
            ElementKind::Optional(b) | ElementKind::Repeat(b) => self.semantic_item_body(b)?,
            ElementKind::OneOrMore(a) | ElementKind::BoundedRepeat { element: a, .. } => {
                self.semantic_item_atom(a)?
            }
            ElementKind::Separated { element, separator } => {
                self.semantic_item_atom(element)?;
                self.semantic_atom(separator)?;
            }
            _ => {}
        }
        Ok(())
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
                if let Some(&id) = self.recursive.indices.get(name.as_str()) {
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
                if let Some(&id) = self.recursive.indices.get(name.as_str()) {
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
pub(crate) fn calls(g: &GrammarDecl) -> Result<Vec<Vec<(usize, usize)>>, String> {
    let recursive = RecursiveShapes::new(g);
    let mut plans: Vec<_> = g
        .rules
        .iter()
        .map(|r| {
            let mut p = Plan {
                g,
                recursive: &recursive,
                nested: crate::nested_captures::enabled(r),
                bindings: HashSet::new(),
                sites: vec![],
            };
            p.visit(&r.body);
            p
        })
        .collect();
    let mut semantic = false;
    for (p, r) in plans.iter().zip(&g.rules) {
        if r.annotations.iter().any(|a| {
            matches!(
                a.kind,
                AnnotationKind::LeftAssoc | AnnotationKind::RightAssoc
            )
        }) {
            continue;
        }
        for a in &p.sites {
            let s = site(g, a, &recursive)?;
            if s.0 != Kind::Text && s.1 != Count::One {
                semantic = true;
                break;
            }
        }
        if semantic {
            break;
        }
    }
    plans
        .iter_mut()
        .zip(&g.rules)
        .map(|(p, r)| {
            if semantic && !p.nested {
                p.semantic_body(&r.body)?
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
            Ok(out)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn mapped_value_search_guards_cycles_and_stops_at_skip_and_mapping() {
        for (rules, expected) in [
            ("T ::= 'n' U | 'a'; U ::= 'u' T;", false),
            ("T ::= 'n' U | 'a'; U ::= 'u' T | M;", true),
            ("T ::= [U]; @skip U ::= M;", false),
            ("T ::= (U % M); U ::= 'u';", true),
            ("T ::= U+; @mapping(N) U ::= Missing;", true),
            ("T ::= Missing;", false),
        ] {
            let source = format!("grammar G {{ @root R ::= T; {rules} @mapping(NM) M ::= 'm'; }}");
            let file = unlaxer_ubnf::parse(&source).unwrap();
            let g = &file.grammars[0];
            let shapes = super::RecursiveShapes::new(g);
            let mut visited = std::collections::HashSet::new();
            assert_eq!(
                super::contains_mapped_value("T", g, &shapes.indices, &mut visited),
                expected,
                "{source}"
            );
            assert!(visited.contains("T"));
            assert!(!super::contains_mapped_value(
                "T",
                g,
                &shapes.indices,
                &mut visited
            ));
        }
    }

    #[test]
    fn text_only_recursion_does_not_initialize_fixed_point() {
        let source = format!(
            "grammar G {{ @root R ::= T; T ::= 'n' T | 'a'; H ::= {}'a' M{}; \
             @mapping(NM) M ::= 'm'; }}",
            "(".repeat(86),
            ")+".repeat(86)
        );
        let file = unlaxer_ubnf::parse(&source).unwrap();
        let g = &file.grammars[0];
        let shapes = super::RecursiveShapes::new(g);
        let result = super::body_shape(g, &g.rules[0].body, &mut Default::default(), &shapes);
        assert!(result.is_ok_and(|s| s == super::text()));
        assert!(shapes.values.get().is_none());
    }

    #[test]
    fn postfix_with_compound_body_falls_back_without_panicking() {
        for (wrapper, count) in [
            ("['a' M]", super::Count::Optional),
            ("{'a' M}", super::Count::Many),
        ] {
            let source = format!("grammar G {{ @root R ::= {wrapper}; @mapping(N) M ::= 'm'; }}");
            let mut file = unlaxer_ubnf::parse(&source).unwrap();
            let g = &mut file.grammars[0];
            let atom = &mut g.rules[0].body.alternatives[0].elements[0].element;
            let (super::ElementKind::Optional(body) | super::ElementKind::Repeat(body)) =
                &atom.kind
            else {
                unreachable!();
            };
            atom.span = body.span;
            let shapes = super::RecursiveShapes::new(g);
            let state = vec![
                None,
                Some(super::Shape(super::Kind::Node, super::Count::One)),
            ];
            let result = super::fixed_atom(
                &g.rules[0].body.alternatives[0].elements[0].element,
                &shapes.indices,
                &state,
                &mut 0,
                0,
            );
            assert!(result.is_ok_and(|s| s == Some(super::Shape(super::Kind::Node, count))));
        }
    }

    #[test]
    fn fixed_point_depth_matches_java_choice_sequence_element_calls() {
        // RustNativeGeneratorTest の RecursiveSemanticShapes.rule と共通の境界。
        for (open, close, limit) in [
            ("(", ")", 84),
            ("(", ")+", 63),
            ("(", ")?", 50),
            ("(", ")*", 50),
            ("[", "]", 84),
            ("{", "}", 84),
        ] {
            for depth in [limit, limit + 1] {
                let source = format!(
                    "grammar Pbt {{ @root @mapping(N0, params=[x]) R0 ::= 'x' H @x; \
                    H ::= {}'a' M{}; @mapping(NM) M ::= 'm'; }}",
                    open.repeat(depth),
                    close.repeat(depth)
                );
                let file = unlaxer_ubnf::parse(&source).unwrap();
                let shapes = super::RecursiveShapes::new(&file.grammars[0]);
                let result = shapes.rule(1);
                if depth == limit {
                    assert!(result.is_ok(), "{source}: {:?}", result.err());
                } else {
                    assert_eq!(
                        result.err().as_deref(),
                        Some("E-RECURSIVE-SHAPE: expression depth exceeds 256"),
                        "{source}"
                    );
                }
            }
        }
    }

    #[test]
    fn fixed_point_counts_choice_sequence_and_each_element_as_work() {
        let file =
            unlaxer_ubnf::parse("grammar G { @root R ::= 'a' M; @mapping(N) M ::= 'm'; }").unwrap();
        let g = &file.grammars[0];
        let shapes = super::RecursiveShapes::new(g);
        let state = vec![
            None,
            Some(super::Shape(super::Kind::Node, super::Count::One)),
        ];
        let mut work = 999_996;
        assert!(super::fixed_body(&g.rules[0].body, &shapes.indices, &state, &mut work, 0).is_ok());
        assert_eq!(work, 1_000_000);
        assert_eq!(
            super::fixed_body(&g.rules[0].body, &shapes.indices, &state, &mut work, 0)
                .err()
                .as_deref(),
            Some("E-RECURSIVE-SHAPE: analysis work exceeds 1000000")
        );
    }

    #[test]
    fn recursive_collection_call_depths_match_java_token_wrappers() {
        // #730: semantic 値が MANY に収束する規則参照には、単一 Value 用の境界は無い。
        for repeated in ["R1+", "{R1}", "R1{1,2}", "R1 % ','"] {
            for base in ["", " | 'a'"] {
                let source = format!(
                    "grammar Pbt {{ @root @mapping(N0, params=[f2]) R0 ::= (R1 R1) @f2; \
                     R1 ::= 'n' R0 | 'y' ({repeated}){base}; }}"
                );
                let file = unlaxer_ubnf::parse(&source).unwrap();
                assert_eq!(
                    super::calls(&file.grammars[0]).unwrap(),
                    vec![vec![(1, 3), (1, 3)], vec![(0, 2), (1, 4)]],
                    "{source}"
                );
            }
        }
    }
}
