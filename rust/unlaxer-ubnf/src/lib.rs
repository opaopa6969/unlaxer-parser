//! Java-free UBNF syntax frontend. No imports are opened and no code is executed.
pub mod ast;
mod lexer;
mod parser;
pub use ast::*;

/// A finite recursion budget also bounds recursive AST destruction.
pub const MAX_NESTING: usize = 128;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DiagnosticKind {
    Syntax,
    UnsupportedSyntax,
    InvalidValue,
    NestingLimit,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Diagnostic {
    pub kind: DiagnosticKind,
    pub message: String,
    pub span: Span,
    /// One-based line and Unicode-scalar column. CRLF is one line break.
    pub line: usize,
    pub column: usize,
}

impl std::fmt::Display for Diagnostic {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}: {}", self.line, self.column, self.message)
    }
}
impl std::error::Error for Diagnostic {}

pub(crate) fn diagnostic(
    source: &str,
    span: Span,
    kind: DiagnosticKind,
    message: impl Into<String>,
) -> Diagnostic {
    let (mut line, mut column, mut cr) = (1, 1, false);
    for c in source[..span.byte_start].chars() {
        match c {
            '\r' => {
                line += 1;
                column = 1;
            }
            '\n' if cr => {}
            '\n' => {
                line += 1;
                column = 1;
            }
            _ => column += 1,
        }
        cr = c == '\r';
    }
    Diagnostic {
        kind,
        message: message.into(),
        span,
        line,
        column,
    }
}

/// Parse the entire UTF-8 input. The first syntax error is returned; no partial AST
/// is presented as success. Backend validation is a separate operation.
pub fn parse(source: &str) -> Result<UbnfFile, Diagnostic> {
    parser::parse(source, lexer::lex(source)?)
}
