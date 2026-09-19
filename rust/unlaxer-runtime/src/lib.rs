//! Experimental UBNF structural subset. No JVM, unsafe code, or external dependencies.
use std::collections::BTreeSet;

/// Half-open Unicode scalar (code-point) offsets, not UTF-8 bytes or UTF-16 units.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Span {
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone)]
pub enum Expr {
    Literal(&'static str),
    Number,
    Rule(usize),
    Sequence(Vec<Expr>),
    Choice(Vec<Expr>),
    Capture(&'static str, Box<Expr>),
}

#[derive(Debug, Clone)]
pub struct Rule {
    pub name: &'static str,
    pub expression: Expr,
}

#[derive(Debug, Clone)]
pub struct Capture {
    pub name: &'static str,
    pub span: Span,
    pub nodes: Vec<usize>,
}

#[derive(Debug, Clone)]
pub struct Node {
    pub rule: usize,
    pub span: Span,
    pub children: Vec<usize>,
    pub captures: Vec<Capture>,
}

/// Owns the input and a flat CST arena; retained trees have no global mutable state.
#[derive(Debug, Clone)]
pub struct Tree {
    pub source: String,
    pub nodes: Vec<Node>,
    pub root: usize,
    byte_offsets: Vec<usize>,
}

impl Tree {
    pub fn text(&self, span: Span) -> &str {
        &self.source[self.byte_offsets[span.start]..self.byte_offsets[span.end]]
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError {
    pub offset: usize,
    pub expected: Vec<String>,
}

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "at code point {}: expected {}",
            self.offset,
            self.expected.join(", ")
        )
    }
}
impl std::error::Error for ParseError {}

/// Matches Java String.strip / Character.isWhitespace (not Rust's broader trim set).
pub fn strip_capture(text: &str) -> &str {
    text.trim_matches(|c| {
        matches!(c,
            '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{1680}' |
            '\u{2000}'..='\u{2006}' | '\u{2008}'..='\u{200a}' | '\u{2028}' | '\u{2029}' |
            '\u{205f}' | '\u{3000}'
        )
    })
}

#[derive(Default)]
struct Fragment {
    nodes: Vec<usize>,
    captures: Vec<Capture>,
}

struct Parser<'a> {
    input: &'a str,
    rules: &'a [Rule],
    whitespace: bool,
    position: usize,
    nodes: Vec<Node>,
    farthest: usize,
    expected: BTreeSet<String>,
    byte_offsets: Vec<usize>,
}

/// Ordered choice with rollback and full-input acceptance. Rule nesting is bounded at 256.
pub fn parse(
    rules: &[Rule],
    root: usize,
    whitespace: bool,
    input: &str,
) -> Result<Tree, ParseError> {
    let mut parser = Parser {
        input,
        rules,
        whitespace,
        position: 0,
        nodes: Vec::new(),
        farthest: 0,
        expected: BTreeSet::new(),
        byte_offsets: input
            .char_indices()
            .map(|(i, _)| i)
            .chain(std::iter::once(input.len()))
            .collect(),
    };
    if let Some(root) = parser.rule(root, 0) {
        if parser.position == input.len() {
            return Ok(Tree {
                source: input.to_owned(),
                nodes: parser.nodes,
                root,
                byte_offsets: parser.byte_offsets,
            });
        }
        parser.fail("end of input");
    }
    Err(ParseError {
        offset: parser.code_point(parser.farthest),
        expected: parser.expected.into_iter().collect(),
    })
}

impl Parser<'_> {
    fn code_point(&self, byte: usize) -> usize {
        self.byte_offsets
            .binary_search(&byte)
            .expect("parser maintains UTF-8 boundaries")
    }

    fn span(&self, start: usize) -> Span {
        Span {
            start: self.code_point(start),
            end: self.code_point(self.position),
        }
    }

    fn fail(&mut self, expected: &str) {
        if self.position > self.farthest {
            self.farthest = self.position;
            self.expected.clear();
        }
        if self.position == self.farthest {
            self.expected.insert(expected.to_owned());
        }
    }

    fn rule(&mut self, id: usize, depth: usize) -> Option<usize> {
        if depth >= 256 {
            self.fail("rule nesting below 256");
            return None;
        }
        let Some(rule) = self.rules.get(id) else {
            self.fail("valid rule reference");
            return None;
        };
        let start = self.position;
        let count = self.nodes.len();
        match self.expression(&rule.expression, depth + 1) {
            Some(fragment) => {
                let node_id = self.nodes.len();
                self.nodes.push(Node {
                    rule: id,
                    span: self.span(start),
                    children: fragment.nodes,
                    captures: fragment.captures,
                });
                Some(node_id)
            }
            None => {
                self.position = start;
                self.nodes.truncate(count);
                None
            }
        }
    }

    fn expression(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::Literal(literal) => {
                if self.input[self.position..].starts_with(literal) {
                    self.position += literal.len();
                    Some(Fragment::default())
                } else {
                    self.fail(literal);
                    None
                }
            }
            Expr::Number => self.number().then(Fragment::default),
            Expr::Rule(rule) => self.rule(*rule, depth).map(|id| Fragment {
                nodes: vec![id],
                captures: vec![],
            }),
            Expr::Sequence(elements) => {
                let mut result = Fragment::default();
                self.skip();
                for element in elements {
                    let mut fragment = self.expression(element, depth)?;
                    result.nodes.append(&mut fragment.nodes);
                    result.captures.append(&mut fragment.captures);
                    self.skip();
                }
                Some(result)
            }
            Expr::Choice(alternatives) => {
                let start = self.position;
                let count = self.nodes.len();
                for alternative in alternatives {
                    if let Some(fragment) = self.expression(alternative, depth) {
                        return Some(fragment);
                    }
                    self.position = start;
                    self.nodes.truncate(count);
                }
                None
            }
            Expr::Capture(name, expression) => {
                let start = self.position;
                let mut fragment = self.expression(expression, depth)?;
                fragment.captures.push(Capture {
                    name,
                    span: self.span(start),
                    nodes: fragment.nodes.clone(),
                });
                Some(fragment)
            }
        }
    }

    fn skip(&mut self) {
        if !self.whitespace {
            return;
        }
        loop {
            let start = self.position;
            while self
                .input
                .as_bytes()
                .get(self.position)
                .is_some_and(|c| matches!(c, b' ' | b'\t' | b'\n' | b'\r' | 11 | 12))
            {
                self.position += 1;
            }
            let rest = &self.input[self.position..];
            if rest.starts_with("//") {
                self.position += rest.find(['\r', '\n']).unwrap_or(rest.len());
            } else if let Some(comment) = rest.strip_prefix("/*") {
                if let Some(end) = comment.find("*/") {
                    self.position += end + 4;
                }
                // An unterminated comment is not trivia and will be rejected by full consumption.
            }
            if self.position == start {
                break;
            }
        }
    }

    // Matches Java NumberParser: optional sign, decimal, optional complete exponent.
    fn number(&mut self) -> bool {
        let start = self.position;
        self.sign();
        let before = self.digits();
        let mut after = false;
        if self.input.as_bytes().get(self.position) == Some(&b'.') {
            self.position += 1;
            after = self.digits();
        }
        if !before && !after {
            self.fail("number");
            self.position = start;
            return false;
        }
        let exponent = self.position;
        if self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(|c| matches!(c, b'e' | b'E'))
        {
            self.position += 1;
            self.sign();
            if !self.digits() {
                self.position = exponent;
            }
        }
        true
    }

    fn sign(&mut self) {
        if self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(|c| matches!(c, b'+' | b'-'))
        {
            self.position += 1;
        }
    }

    fn digits(&mut self) -> bool {
        let start = self.position;
        while self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(u8::is_ascii_digit)
        {
            self.position += 1;
        }
        self.position > start
    }
}

/// Shared JSON string escaping for deterministic cross-language AST fixtures.
pub fn json_string(value: &str) -> String {
    let mut result = String::from("\"");
    for character in value.chars() {
        match character {
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            '\n' => result.push_str("\\n"),
            '\r' => result.push_str("\\r"),
            '\t' => result.push_str("\\t"),
            c if c < ' ' => result.push_str(&format!("\\u{:04x}", c as u32)),
            c => result.push(c),
        }
    }
    result.push('"');
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capture_stripping_preserves_nonbreaking_spaces_like_java() {
        assert_eq!(strip_capture("\u{001c}\u{3000}value \t"), "value");
        for c in ['\u{0085}', '\u{00a0}', '\u{2007}', '\u{202f}'] {
            let text = format!("{c}value{c}");
            assert_eq!(strip_capture(&text), text);
        }
    }

    #[test]
    fn rollback_unicode_and_diagnostic_offsets() {
        let rules = vec![
            Rule {
                name: "root",
                expression: Expr::Choice(vec![
                    Expr::Sequence(vec![Expr::Rule(1), Expr::Literal("!")]),
                    Expr::Sequence(vec![Expr::Rule(1), Expr::Literal("?")]),
                ]),
            },
            Rule {
                name: "word",
                expression: Expr::Literal("😀"),
            },
        ];
        let tree = parse(&rules, 0, false, "😀?").unwrap();
        assert_eq!(tree.nodes.len(), 2); // failed branch left no CST nodes
        assert_eq!(tree.nodes[tree.root].span, Span { start: 0, end: 2 });
        assert_eq!(tree.text(tree.nodes[0].span), "😀");
        let error = parse(&rules, 0, false, "😀x").unwrap_err();
        assert_eq!(error.offset, 1);
        assert_eq!(error.expected, vec!["!", "?"]);
    }

    #[test]
    fn number_full_consumption_and_comments() {
        let rules = vec![Rule {
            name: "root",
            expression: Expr::Sequence(vec![Expr::Number]),
        }];
        for input in ["0", "+1", "-.5", "12.", "1E-2", " /*😀*/ 2 //ok"] {
            assert!(parse(&rules, 0, true, input).is_ok(), "{input}");
        }
        for input in ["", ".", "+", "1e", "1x", "2/*unclosed", "١"] {
            assert!(parse(&rules, 0, true, input).is_err(), "{input}");
        }
    }

    #[test]
    fn recursion_limit_is_a_diagnostic() {
        let rules = vec![Rule {
            name: "cycle",
            expression: Expr::Rule(0),
        }];
        assert!(parse(&rules, 0, false, "").unwrap_err().expected[0].contains("256"));
    }
}
