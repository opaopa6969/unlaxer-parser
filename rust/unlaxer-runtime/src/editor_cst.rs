//! Opt-in, bounded EOF repair. Only original-source spans and text escape this module.
use crate::{Diagnostics, ParseOptions, SharedGrammar, Span, Tree};
use std::collections::{HashSet, VecDeque};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Complete,
    Partial,
    Failed,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reason {
    None,
    Repaired,
    Syntax,
    Limit,
    NoCompletion,
    Unsafe,
}
impl Status {
    pub fn name(self) -> &'static str {
        match self {
            Self::Complete => "COMPLETE",
            Self::Partial => "PARTIAL",
            Self::Failed => "FAILED",
        }
    }
}
impl Reason {
    pub fn name(self) -> &'static str {
        match self {
            Self::None => "NONE",
            Self::Repaired => "REPAIRED",
            Self::Syntax => "SYNTAX",
            Self::Limit => "LIMIT",
            Self::NoCompletion => "NO_COMPLETION",
            Self::Unsafe => "UNSAFE",
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DefectKind {
    Missing,
    Error,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Capture {
    pub name: String,
    pub span: Span,
    pub synthetic: bool,
    pub text: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Node {
    pub rule: String,
    pub span: Span,
    pub synthetic: bool,
    pub captures: Vec<Capture>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Defect {
    pub kind: DefectKind,
    pub span: Span,
    pub candidate_rules: Vec<String>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Options {
    pub max_fragments: usize,
    pub max_attempts: usize,
}
impl Default for Options {
    fn default() -> Self {
        Self {
            max_fragments: 4,
            max_attempts: 256,
        }
    }
}
#[derive(Debug, Clone)]
pub struct EditorCst {
    source: String,
    status: Status,
    reason: Reason,
    nodes: Vec<Node>,
    defects: Vec<Defect>,
}
impl EditorCst {
    pub fn source(&self) -> &str {
        &self.source
    }
    pub fn status(&self) -> Status {
        self.status
    }
    pub fn reason(&self) -> Reason {
        self.reason
    }
    pub fn nodes(&self) -> &[Node] {
        &self.nodes
    }
    pub fn defects(&self) -> &[Defect] {
        &self.defects
    }
}
fn failed(source: &str, reason: Reason) -> EditorCst {
    EditorCst {
        source: source.into(),
        status: Status::Failed,
        reason,
        nodes: vec![],
        defects: vec![],
    }
}
/// Custom callbacks must declare diagnostic independence and replayability before EOF retries.
pub fn parse(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    source: &str,
    completions: &[&str],
    options: Options,
) -> Result<EditorCst, &'static str> {
    if source.chars().count() > 65536 || options.max_fragments > 8 || options.max_attempts > 4096 {
        return Err("invalid editor source or completion limit");
    }
    let attempt = |input: &str| {
        crate::parse_detailed_shared_with_options(
            grammar,
            root,
            whitespace,
            input,
            ParseOptions::default().with_diagnostics(Diagnostics::Detailed),
        )
    };
    let length = source.chars().count();
    let original_failure = match attempt(source) {
        Ok(tree) => return Ok(snapshot(grammar, source, &tree, false)),
        Err(error) => error.farthest.offset,
    };
    if !crate::grammar_allows_deferred_diagnostics(grammar) {
        return Ok(failed(source, Reason::Unsafe));
    }
    let mut seen = HashSet::new();
    let candidates: Vec<_> = completions
        .iter()
        .copied()
        .filter(|value| !value.is_empty() && value.chars().count() <= 64 && seen.insert(*value))
        .collect();
    let mut pending = VecDeque::from([(String::new(), 0)]);
    let mut attempts = 0;
    let mut depth_limit = false;
    while let Some((suffix, fragments)) = pending.pop_front() {
        if fragments >= options.max_fragments {
            depth_limit = true;
            continue;
        }
        for completion in &candidates {
            if attempts >= options.max_attempts {
                return Ok(failed(source, Reason::Limit));
            }
            attempts += 1;
            let suffix = format!("{suffix}{completion}");
            let completed = format!("{source}{suffix}");
            match attempt(&completed) {
                Ok(tree) => return Ok(snapshot(grammar, source, &tree, true)),
                Err(error) if error.farthest.offset >= completed.chars().count() => {
                    pending.push_back((suffix, fragments + 1))
                }
                Err(_) => {}
            }
        }
    }
    Ok(failed(
        source,
        if depth_limit {
            Reason::Limit
        } else if original_failure < length {
            Reason::Syntax
        } else {
            Reason::NoCompletion
        },
    ))
}
fn clipped(span: Span, length: usize) -> Span {
    Span {
        start: span.start.min(length),
        end: span.end.min(length),
    }
}
fn candidates(mut nodes: Vec<&Node>) -> Vec<String> {
    nodes.sort_by(|left, right| {
        (left.span.end - left.span.start, &left.rule)
            .cmp(&(right.span.end - right.span.start, &right.rule))
    });
    let mut seen = HashSet::new();
    nodes
        .into_iter()
        .filter_map(|node| {
            if seen.insert(node.rule.clone()) {
                Some(node.rule.clone())
            } else {
                None
            }
        })
        .collect()
}
fn snapshot(grammar: &SharedGrammar, source: &str, tree: &Tree, repaired: bool) -> EditorCst {
    let offsets: Vec<_> = source
        .char_indices()
        .map(|(offset, _)| offset)
        .chain(std::iter::once(source.len()))
        .collect();
    let length = offsets.len() - 1;
    let mut nodes = Vec::new();
    let mut pending = vec![tree.root];
    let mut visited = HashSet::new();
    while let Some(index) = pending.pop() {
        if !visited.insert(index) {
            continue;
        }
        let node = &tree.nodes[index];
        pending.extend(node.children.iter().rev());
        let Some(rule) = grammar.get(node.rule) else {
            continue;
        };
        let mut captures: Vec<Capture> = node
            .captures
            .iter()
            .map(|capture| {
                let span = clipped(capture.span, length);
                Capture {
                    name: capture.name.into(),
                    span,
                    synthetic: capture.span.end > length,
                    text: source[offsets[span.start]..offsets[span.end]].into(),
                }
            })
            .collect();
        captures.sort_by(|left, right| {
            (left.span.start, left.span.end, &left.name).cmp(&(
                right.span.start,
                right.span.end,
                &right.name,
            ))
        });
        nodes.push(Node {
            rule: rule.name.into(),
            span: clipped(node.span, length),
            synthetic: node.span.end > length,
            captures,
        });
    }
    let mut defects = Vec::new();
    for diagnostic in tree.recoveries() {
        if diagnostic.span.start >= length {
            continue;
        }
        let diagnostic_span = clipped(diagnostic.span, length);
        defects.push(Defect {
            kind: DefectKind::Error,
            span: diagnostic_span,
            candidate_rules: candidates(
                nodes
                    .iter()
                    .filter(|node| {
                        node.span.start <= diagnostic.span.start
                            && diagnostic_span.end <= node.span.end
                    })
                    .collect(),
            ),
        });
    }
    if repaired {
        defects.push(Defect {
            kind: DefectKind::Missing,
            span: Span {
                start: length,
                end: length,
            },
            candidate_rules: candidates(nodes.iter().filter(|node| node.synthetic).collect()),
        });
    }
    EditorCst {
        source: source.into(),
        status: if defects.is_empty() {
            Status::Complete
        } else {
            Status::Partial
        },
        reason: if repaired {
            Reason::Repaired
        } else {
            Reason::None
        },
        nodes,
        defects,
    }
}

/// Literal fragments present in this grammar; this is a bounded hint set, not an acceptance claim.
pub fn literal_completions(grammar: &SharedGrammar) -> Vec<&'static str> {
    fn lexical(expression: &crate::lexical::LexicalExpression, result: &mut Vec<&'static str>) {
        if expression.op == crate::lexical::Op::LITERAL {
            result.push(expression.text);
        }
        for child in &expression.children {
            lexical(child, result);
        }
    }
    fn visit(expression: &crate::Expr, result: &mut Vec<&'static str>) {
        use crate::Expr::*;
        match expression {
            Literal(value) => result.push(value),
            Lexical(_, expression) => lexical(expression, result),
            Sequence(children) | Choice(children) | LongestChoice(children) => {
                for child in children {
                    visit(child, result);
                }
            }
            PredictiveChoice { alternatives, .. } => {
                for child in alternatives {
                    visit(child, result);
                }
            }
            Capture(_, child)
            | TextValue(child)
            | ValueBoundary(child)
            | Optional(child)
            | JavaOptional(child)
            | Repeat { child, .. }
            | JavaRepeat { child, .. }
            | RuleEffects { child, .. }
            | CaptureEquality { child, .. }
            | TriviaScope { child, .. }
            | Recovery { child, .. } => visit(child, result),
            _ => {}
        }
    }
    let mut result = Vec::new();
    for rule in grammar.iter() {
        visit(&rule.expression, &mut result);
    }
    result.retain(|value| !value.is_empty() && value.chars().count() <= 64);
    let priority = |value: &&str| match *value {
        ")" => 0,
        ";" => 1,
        "}" => 2,
        "]" => 3,
        "?" => 4,
        _ => 5,
    };
    result.sort_by_key(|value| (priority(value), value.chars().count(), *value));
    result.dedup();
    result.truncate(256);
    result
}
impl EditorCst {
    /// All text and locations refer to the original input, including crossing synthetic captures.
    pub fn canonical_json(&self) -> String {
        let q = crate::json_string;
        let nodes=self.nodes.iter().map(|node| {
            let captures=node.captures.iter().map(|capture|format!("{{\"name\":{},\"span\":[{},{}],\"synthetic\":{},\"text\":{}}}",q(&capture.name),capture.span.start,capture.span.end,capture.synthetic,q(&capture.text))).collect::<Vec<_>>().join(",");
            format!("{{\"rule\":{},\"span\":[{},{}],\"synthetic\":{},\"children\":[],\"captures\":[{}]}}",q(&node.rule),node.span.start,node.span.end,node.synthetic,captures)
        }).collect::<Vec<_>>().join(",");
        let defects = self
            .defects
            .iter()
            .map(|defect| {
                format!(
                    "{{\"kind\":{},\"span\":[{},{}],\"candidateRules\":[{}]}}",
                    q(match defect.kind {
                        DefectKind::Missing => "MISSING",
                        DefectKind::Error => "ERROR",
                    }),
                    defect.span.start,
                    defect.span.end,
                    defect
                        .candidate_rules
                        .iter()
                        .map(|value| q(value))
                        .collect::<Vec<_>>()
                        .join(",")
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        format!(
            "{{\"status\":{},\"reason\":{},\"sourceLength\":{},\"nodes\":[{}],\"defects\":[{}]}}",
            q(self.status.name()),
            q(self.reason.name()),
            self.source.chars().count(),
            nodes,
            defects
        )
    }
}
