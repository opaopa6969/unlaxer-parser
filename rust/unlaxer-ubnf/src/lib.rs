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

/// Source and AST owned by one parse, independent of subsequent parser activity.
/// Imports are recorded but never opened. Offsets are those of this single input.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SourceSnapshot {
    source: String,
    ast: UbnfFile,
}

impl SourceSnapshot {
    pub fn source(&self) -> &str {
        &self.source
    }
    pub fn ast(&self) -> &UbnfFile {
        &self.ast
    }
    /// Slice using a span's UTF-8 byte bounds, rejecting invalid character boundaries.
    /// Use codepoint_start/end to compare positions with the Java frontend.
    pub fn slice(&self, span: Span) -> Option<&str> {
        self.source.get(span.byte_start..span.byte_end)
    }
}

/// Parse once while retaining the input, without changing the ordinary parse API.
pub fn parse_with_source(source: impl Into<String>) -> Result<SourceSnapshot, Diagnostic> {
    let source = source.into();
    let ast = parse(&source)?;
    Ok(SourceSnapshot { source, ast })
}
