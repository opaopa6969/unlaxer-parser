use crate::{diagnostic, Diagnostic, DiagnosticKind, Span};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    Identifier(String),
    Number(String),
    Quoted(String),
    Symbol(char),
    Define,
    Eof,
}

#[derive(Clone, Debug)]
pub(crate) struct Token {
    pub kind: Kind,
    pub span: Span,
}

pub(crate) fn lex(source: &str) -> Result<Vec<Token>, Diagnostic> {
    let mut chars = source.char_indices().peekable();
    let mut cp = 0;
    let mut tokens = Vec::new();
    while let Some((start, c)) = chars.next() {
        let cp_start = cp;
        cp += 1;
        if matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{b}' | '\u{c}') {
            continue;
        }
        if c == '/' && chars.peek().is_some_and(|(_, c)| *c == '/') {
            chars.next();
            cp += 1;
            while chars.peek().is_some_and(|(_, c)| !matches!(c, '\r' | '\n')) {
                chars.next();
                cp += 1;
            }
            continue;
        }
        let kind = if c.is_ascii_alphabetic() || c == '_' {
            while chars
                .peek()
                .is_some_and(|(_, c)| c.is_ascii_alphanumeric() || *c == '_')
            {
                chars.next();
                cp += 1;
            }
            Kind::Identifier(
                source[start..chars.peek().map_or(source.len(), |(i, _)| *i)].to_owned(),
            )
        } else if c.is_ascii_digit() {
            while chars.peek().is_some_and(|(_, c)| c.is_ascii_digit()) {
                chars.next();
                cp += 1;
            }
            Kind::Number(source[start..chars.peek().map_or(source.len(), |(i, _)| *i)].to_owned())
        } else if c == '\'' {
            let mut value = String::new();
            let mut closed = false;
            while let Some((_, c)) = chars.next() {
                cp += 1;
                if c == '\'' {
                    closed = true;
                    break;
                }
                if c == '\\' {
                    let Some((_, next)) = chars.next() else {
                        break;
                    };
                    cp += 1;
                    match next {
                        'n' => value.push('\n'),
                        'r' => value.push('\r'),
                        't' => value.push('\t'),
                        '\\' => value.push('\\'),
                        '\'' => value.push('\''),
                        _ => {
                            value.push('\\');
                            value.push(next);
                        }
                    }
                } else {
                    value.push(c);
                }
            }
            if !closed {
                return Err(diagnostic(
                    source,
                    Span {
                        byte_start: start,
                        byte_end: source.len(),
                        codepoint_start: cp_start,
                        codepoint_end: cp,
                    },
                    DiagnosticKind::Syntax,
                    "unterminated single-quoted literal",
                ));
            }
            Kind::Quoted(value)
        } else if c == ':' && source[start..].starts_with("::=") {
            chars.next();
            chars.next();
            cp += 2;
            Kind::Define
        } else if "@{}[]():;,=.|+?*%".contains(c) {
            Kind::Symbol(c)
        } else {
            return Err(diagnostic(
                source,
                Span {
                    byte_start: start,
                    byte_end: start + c.len_utf8(),
                    codepoint_start: cp_start,
                    codepoint_end: cp,
                },
                DiagnosticKind::UnsupportedSyntax,
                if source[start..].starts_with("/*") {
                    "UBNF block comments are unsupported; use // comments".to_owned()
                } else {
                    format!("unexpected character {c:?}")
                },
            ));
        };
        tokens.push(Token {
            kind,
            span: Span {
                byte_start: start,
                byte_end: chars.peek().map_or(source.len(), |(i, _)| *i),
                codepoint_start: cp_start,
                codepoint_end: cp,
            },
        });
    }
    tokens.push(Token {
        kind: Kind::Eof,
        span: Span {
            byte_start: source.len(),
            byte_end: source.len(),
            codepoint_start: cp,
            codepoint_end: cp,
        },
    });
    Ok(tokens)
}
