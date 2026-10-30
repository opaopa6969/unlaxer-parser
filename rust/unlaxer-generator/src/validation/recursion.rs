//! Java's bounded source-level cycle query. Lowering's IR query requires a valid
//! root/schema and returns one error, so it cannot supply this advisory list.
use std::collections::{HashMap, HashSet};
use unlaxer_ubnf::*;
const LIMIT: &str = "E-LEFT-RECURSION-LIMIT: rule analysis resource limit";
struct Analysis<'a> {
    rules: Vec<&'a RuleDecl>,
    tokens: HashMap<&'a str, &'a TokenDecl>,
    nullable: HashSet<String>,
    work: usize,
}
pub(super) fn cycles(grammar: &GrammarDecl) -> Result<Vec<String>, String> {
    let mut rules: Vec<&RuleDecl> = vec![];
    for rule in &grammar.rules {
        if let Some(slot) = rules.iter_mut().find(|r| r.name == rule.name) {
            *slot = rule;
        } else {
            rules.push(rule);
        }
    }
    let mut a = Analysis {
        rules,
        tokens: grammar
            .tokens
            .iter()
            .map(|t| (t.name.as_str(), t))
            .collect(),
        nullable: HashSet::new(),
        work: 0,
    };
    loop {
        let mut changed = false;
        for rule in a.rules.clone() {
            if !a.nullable.contains(&rule.name) && a.body(&rule.body, &mut vec![], 0)? {
                changed |= a.nullable.insert(rule.name.clone());
            }
        }
        if !changed {
            break;
        }
    }
    let mut edges = HashMap::new();
    for rule in a.rules.clone() {
        let mut leading = vec![];
        a.body(&rule.body, &mut leading, 0)?;
        edges.insert(rule.name.as_str(), leading);
    }
    let mut cycles = vec![];
    let mut done = HashSet::new();
    let mut visiting = vec![];
    for rule in a.rules.clone() {
        a.visit(&rule.name, &edges, &mut done, &mut visiting, &mut cycles)?;
    }
    Ok(cycles)
}
impl Analysis<'_> {
    fn tick(&mut self, depth: usize) -> Result<(), String> {
        self.work += 1;
        if self.work > 1_000_000 || depth > 256 {
            Err(LIMIT.into())
        } else {
            Ok(())
        }
    }
    fn visit(
        &mut self,
        rule: &str,
        edges: &HashMap<&str, Vec<String>>,
        done: &mut HashSet<String>,
        visiting: &mut Vec<String>,
        cycles: &mut Vec<String>,
    ) -> Result<(), String> {
        self.tick(visiting.len() + 1)?;
        if done.contains(rule) {
            return Ok(());
        }
        if let Some(start) = visiting.iter().position(|r| r == rule) {
            let mut path = visiting[start..].to_vec();
            path.push(rule.into());
            cycles.push(path.join(" -> "));
            return Ok(());
        }
        visiting.push(rule.into());
        for next in &edges[rule] {
            self.visit(next, edges, done, visiting, cycles)?;
        }
        visiting.pop();
        done.insert(rule.into());
        Ok(())
    }
    fn body(
        &mut self,
        body: &RuleBody,
        leading: &mut Vec<String>,
        depth: usize,
    ) -> Result<bool, String> {
        self.tick(depth)?;
        if body.alternatives.len() > 1 {
            let mut nullable = false;
            for seq in &body.alternatives {
                self.tick(depth + 1)?;
                nullable |= self.sequence(seq, leading, depth + 1)?;
            }
            Ok(nullable)
        } else {
            self.sequence(&body.alternatives[0], leading, depth)
        }
    }
    fn sequence(
        &mut self,
        seq: &Sequence,
        leading: &mut Vec<String>,
        depth: usize,
    ) -> Result<bool, String> {
        for e in &seq.elements {
            if !self.atom(&e.element, leading, depth)? {
                return Ok(false);
            }
        }
        Ok(true)
    }
    fn atom(
        &mut self,
        atom: &AtomicElement,
        leading: &mut Vec<String>,
        depth: usize,
    ) -> Result<bool, String> {
        self.tick(depth)?;
        Ok(match &atom.kind {
            ElementKind::RuleRef {
                namespace: Some(_), ..
            } => true,
            ElementKind::RuleRef { name, .. } => {
                if let Some(token) = self.tokens.get(name.as_str()) {
                    token_nullable(&token.kind)
                } else if !self.rules.iter().any(|r| r.name == *name) {
                    true
                } else {
                    if !leading.contains(name) {
                        leading.push(name.clone());
                    }
                    self.nullable.contains(name)
                }
            }
            ElementKind::Replay { .. } => true,
            ElementKind::Terminal(s) => s.is_empty(),
            ElementKind::Error(_) => false,
            ElementKind::Group(b)
            | ElementKind::OwnedCapture { body: b, .. }
            | ElementKind::CaptureScope(b)
            | ElementKind::Commit { body: b, .. }
            | ElementKind::Label { body: b, .. }
            | ElementKind::Catch { choice: b, .. } => self.body(b, leading, depth + 1)?,
            ElementKind::Optional(b) | ElementKind::Repeat(b) => {
                self.body(b, leading, depth + 1)?;
                true
            }
            ElementKind::OneOrMore(e) => self.atom(e, leading, depth + 1)?,
            ElementKind::BoundedRepeat { element, min, max } => {
                if *max == Some(0) {
                    true
                } else {
                    let empty = self.atom(element, leading, depth + 1)?;
                    *min == 0 || empty
                }
            }
            ElementKind::Separated { element, separator } => {
                let empty = self.atom(element, leading, depth + 1)?;
                if empty {
                    self.atom(separator, leading, depth + 1)?;
                }
                empty
            }
        })
    }
}
fn token_nullable(token: &TokenKind) -> bool {
    match token {
        TokenKind::Declarative { expression } => expression.nullable(),
        TokenKind::Empty
        | TokenKind::Eof
        | TokenKind::Lookahead { .. }
        | TokenKind::NegativeLookahead { .. }
        | TokenKind::Until { .. } => true,
        TokenKind::Simple { parser_class } => {
            parser_class.ends_with("EndOfSourceParser")
                || crate::lowering::token_expression(token).is_err()
        }
        TokenKind::Adapter { id, .. } => ![
            "tinyexpression.string",
            "tinyexpression.code-start",
            "tinyexpression.code-end",
            "tinyexpression.long-code-block",
        ]
        .contains(&id.as_str()),
        _ => false,
    }
}
