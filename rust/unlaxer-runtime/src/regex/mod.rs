//! A bounded, independent implementation of the Java 21 Pattern core.
//!
//! This is not a replacement for all of java.util.regex. Unsupported constructs
//! are compile errors, rather than another regex dialect's interpretation.
//! See docs/regex-core.md and spec-corpus/regex-core/capabilities.tsv.
mod parse;
mod sets;
mod vm;

use sets::Set;
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CompileErrorKind {
    Syntax,
    Unsupported,
    ResourceLimit,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompileError {
    pub kind: CompileErrorKind,
    /// Unicode-scalar offset in the pattern, not UTF-8 or UTF-16.
    pub offset: usize,
    pub message: &'static str,
}
impl fmt::Display for CompileError {
    fn fmt(&self, out: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            out,
            "regex {:?} at CP {}: {}",
            self.kind, self.offset, self.message
        )
    }
}
impl std::error::Error for CompileError {}

#[derive(Debug, Clone, Copy)]
pub struct CompileLimits {
    pub max_pattern_codepoints: usize,
    pub max_nodes: usize,
    /// Capped at 64, including when a caller requests a larger value.
    pub max_depth: usize,
    pub max_instructions: usize,
    /// Cumulative interval work during character-class set operations.
    pub max_set_work: usize,
}
impl Default for CompileLimits {
    fn default() -> Self {
        Self {
            max_pattern_codepoints: 16_384,
            max_nodes: 8_192,
            max_depth: 64,
            max_instructions: 32_768,
            max_set_work: 1_000_000,
        }
    }
}
#[derive(Debug, Clone, Copy)]
pub struct MatchLimits {
    pub max_steps: usize,
    pub max_backtracking_states: usize,
    pub max_input_codepoints: usize,
}
impl Default for MatchLimits {
    fn default() -> Self {
        Self {
            max_steps: 1_000_000,
            max_backtracking_states: 4_096,
            max_input_codepoints: 1_000_000,
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatchError {
    StepLimit,
    BacktrackingLimit,
    InputLimit,
}
impl fmt::Display for MatchError {
    fn fmt(&self, out: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(out, "regex resource_limit: {self:?}")
    }
}
impl std::error::Error for MatchError {}

/// End coordinates relative to the supplied remaining-input slice.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Match {
    pub end_cp: usize,
    pub end_utf16: usize,
    pub end_utf8: usize,
}
impl Match {
    fn at(input: &[char], end_cp: usize) -> Self {
        Self {
            end_cp,
            end_utf16: input[..end_cp].iter().map(|c| c.len_utf16()).sum(),
            end_utf8: input[..end_cp].iter().map(|c| c.len_utf8()).sum(),
        }
    }
}

/// Immutable compiled pattern. Every execution owns its counters and backtracking state.
#[derive(Debug, Clone)]
pub struct Regex {
    program: Vec<vm::Instruction>,
    start: usize,
}
impl Regex {
    pub fn compile(pattern: &str) -> Result<Self, CompileError> {
        Self::compile_with_limits(pattern, CompileLimits::default())
    }
    pub fn compile_with_limits(pattern: &str, limits: CompileLimits) -> Result<Self, CompileError> {
        let node = parse::parse(pattern, limits)?;
        vm::compile(node, limits)
    }
    /// Java Pattern.matcher(remaining_input).lookingAt(), including zero-width matches.
    pub fn looking_at(&self, remaining_input: &str) -> Result<Option<Match>, MatchError> {
        self.looking_at_with_limits(remaining_input, MatchLimits::default())
    }
    pub fn looking_at_with_limits(
        &self,
        input: &str,
        limits: MatchLimits,
    ) -> Result<Option<Match>, MatchError> {
        let chars: Vec<_> = input
            .chars()
            .take(limits.max_input_codepoints.saturating_add(1))
            .collect();
        if chars.len() > limits.max_input_codepoints {
            return Err(MatchError::InputLimit);
        }
        vm::execute(self, &chars, limits).map(|end| end.map(|end| Match::at(&chars, end)))
    }
    /// RegexTokenParser's nonempty-token rule, independent of token propagation.
    /// At EOF neither mode returns a token. Inversion advances one scalar only
    /// when the original expression fails; a successful zero-width match is not failure.
    pub fn token_prefix(
        &self,
        remaining_input: &str,
        invert: bool,
    ) -> Result<Option<Match>, MatchError> {
        self.token_prefix_with_limits(remaining_input, invert, MatchLimits::default())
    }
    pub fn token_prefix_with_limits(
        &self,
        remaining_input: &str,
        invert: bool,
        limits: MatchLimits,
    ) -> Result<Option<Match>, MatchError> {
        if remaining_input.is_empty() {
            return Ok(None);
        }
        match self.looking_at_with_limits(remaining_input, limits)? {
            None if invert => {
                let first = remaining_input.chars().next().unwrap();
                Ok(Some(Match {
                    end_cp: 1,
                    end_utf16: first.len_utf16(),
                    end_utf8: first.len_utf8(),
                }))
            }
            Some(found) if !invert && found.end_cp > 0 => Ok(Some(found)),
            _ => Ok(None),
        }
    }
}

#[derive(Debug, Clone, Copy, Default)]
struct Flags {
    insensitive: bool,
    unix: bool,
    multiline: bool,
    dotall: bool,
    comments: bool,
}
#[derive(Debug, Clone, Copy)]
enum Anchor {
    Begin,
    End,
    AbsoluteBegin,
    AbsoluteEnd,
    FinalEnd,
}
#[derive(Debug)]
enum Node {
    Empty,
    Set(Set),
    Anchor(Anchor, Flags),
    Sequence(Vec<Node>),
    Choice(Vec<Node>),
    Repeat {
        body: Box<Node>,
        min: usize,
        max: Option<usize>,
        reluctant: bool,
    },
    Atomic(Box<Node>),
}
fn terminator(c: char, unix: bool) -> bool {
    c == '\n' || !unix && matches!(c, '\r' | '\u{85}' | '\u{2028}' | '\u{2029}')
}
fn ascii_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{b}' | '\u{c}')
}
