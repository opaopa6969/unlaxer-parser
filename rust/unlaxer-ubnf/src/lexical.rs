//! Structured lexical expressions, shared in meaning with Java's LexicalExpression.
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
    REF,
    SCOPE,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LexicalExpression {
    pub op: Op,
    pub text: String,
    pub min: i32,
    pub max: i32,
    pub children: Vec<LexicalExpression>,
}
impl LexicalExpression {
    pub fn leaf(op: Op, text: String) -> Self {
        Self {
            op,
            text,
            min: 0,
            max: 0,
            children: vec![],
        }
    }
    pub fn node(op: Op, children: Vec<Self>) -> Self {
        Self {
            children,
            ..Self::leaf(op, String::new())
        }
    }
    pub fn repeat(child: Self, min: i32, max: i32) -> Self {
        Self {
            min,
            max,
            ..Self::node(Op::REPEAT, vec![child])
        }
    }
    pub fn nullable(&self) -> bool {
        match self.op {
            Op::LITERAL => self.text.is_empty(),
            Op::ANY | Op::XID_IDENTIFIER | Op::RANGE | Op::EXCEPT => false,
            Op::SEQUENCE => self.children.iter().all(Self::nullable),
            Op::CHOICE => self.children.iter().any(Self::nullable),
            Op::REPEAT => self.min == 0 || self.children[0].nullable(),
            Op::CAPTURE | Op::SCOPE => self.children[0].nullable(),
            _ => true,
        }
    }
}
