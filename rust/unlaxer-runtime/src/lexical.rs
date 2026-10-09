//! Atomic lexical programs generated from UBNF. Matching has no host-context side effects.
use std::collections::HashMap;

#[allow(clippy::upper_case_acronyms, non_camel_case_types)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    LITERAL,
    ANY,
    XID_IDENTIFIER,
    EOF,
    BOF,
    BOL,
    EOL,
    RANGE,
    EXCEPT,
    SEQUENCE,
    CHOICE,
    REPEAT,
    LOOK,
    NOT,
    CAPTURE,
    BACKREF,
    SCOPE,
}

#[derive(Clone, Debug)]
pub struct LexicalExpression {
    pub op: Op,
    pub text: &'static str,
    pub min: i32,
    pub max: i32,
    pub children: Vec<LexicalExpression>,
}

impl LexicalExpression {
    pub fn match_at(&self, source: &str, start: usize) -> Option<usize> {
        self.run(source, start, &mut HashMap::new())
    }
    fn run<'a>(
        &self,
        s: &'a str,
        p: usize,
        bindings: &mut HashMap<&'static str, &'a str>,
    ) -> Option<usize> {
        let before = bindings.clone();
        let end = self.eval(s, p, bindings);
        if end.is_none() {
            *bindings = before;
        }
        end
    }
    fn eval<'a>(
        &self,
        s: &'a str,
        p: usize,
        bindings: &mut HashMap<&'static str, &'a str>,
    ) -> Option<usize> {
        match self.op {
            Op::LITERAL => s[p..].starts_with(self.text).then_some(p + self.text.len()),
            Op::XID_IDENTIFIER => crate::unicode_xid::identifier_end(s, p),
            Op::ANY => s[p..].chars().next().map(|c| p + c.len_utf8()),
            Op::EOF => (p == s.len()).then_some(p),
            Op::BOF => (p == 0).then_some(p),
            Op::BOL => {
                (p == 0 || matches!(s.as_bytes().get(p - 1), Some(b'\r' | b'\n'))).then_some(p)
            }
            Op::EOL => {
                (p == s.len() || matches!(s.as_bytes().get(p), Some(b'\r' | b'\n'))).then_some(p)
            }
            Op::RANGE => s[p..]
                .chars()
                .next()
                .filter(|c| (*c as i32) >= self.min && (*c as i32) <= self.max)
                .map(|c| p + c.len_utf8()),
            Op::EXCEPT => s[p..]
                .chars()
                .next()
                .filter(|c| !self.text.contains(*c))
                .map(|c| p + c.len_utf8()),
            Op::SEQUENCE => self
                .children
                .iter()
                .try_fold(p, |p, child| child.run(s, p, bindings)),
            Op::CHOICE => self
                .children
                .iter()
                .find_map(|child| child.run(s, p, bindings)),
            Op::REPEAT => {
                let mut end = p;
                let mut count = 0;
                while self.max < 0 || count < self.max {
                    let Some(next) = self.children[0].run(s, end, bindings) else {
                        break;
                    };
                    count += 1;
                    assert!(next != end || self.max >= 0, "nullable lexical repeat");
                    end = next;
                }
                (count >= self.min).then_some(end)
            }
            Op::LOOK | Op::NOT => {
                let matched = self.children[0].run(s, p, &mut bindings.clone()).is_some();
                (matched == (self.op == Op::LOOK)).then_some(p)
            }
            Op::CAPTURE => {
                let end = self.children[0].run(s, p, bindings)?;
                bindings.insert(self.text, &s[p..end]);
                Some(end)
            }
            Op::BACKREF => bindings
                .get(self.text)
                .filter(|text| s[p..].starts_with(**text))
                .map(|text| p + text.len()),
            Op::SCOPE => self.children[0].run(s, p, &mut HashMap::new()),
        }
    }
}
