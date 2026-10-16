use std::iter::Peekable;
use std::str::Chars;
use unlaxer_runtime::source::{Position, Snapshot};

/// Ordered indices used by the full semantic-token provider. No automatic coloring is implied.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SemanticTokensLegend {
    pub token_types: Vec<String>,
    pub token_modifiers: Vec<String>,
}
impl Default for SemanticTokensLegend {
    fn default() -> Self {
        Self {
            token_types: vec!["valid".into(), "invalid".into()],
            token_modifiers: vec![],
        }
    }
}

struct Cursor<'a> {
    chars: Peekable<Chars<'a>>,
    position: Position,
}
impl Cursor<'_> {
    // Tokens are ordered and non-overlapping, so each source scalar is visited at most once.
    fn advance(&mut self, target: Position) -> Result<(), &'static str> {
        while self.position != target {
            if self.position.line > target.line
                || self.position.line == target.line && self.position.character > target.character
            {
                return Err("invalid semantic token position");
            }
            let scalar = self.chars.next().ok_or("semantic token outside document")?;
            if scalar == '\r' || scalar == '\n' {
                if self.position.line == target.line {
                    return Err("semantic token crosses line boundary");
                }
                if scalar == '\r' && self.chars.peek() == Some(&'\n') {
                    self.chars.next();
                }
                self.position.line += 1;
                self.position.character = 0;
            } else {
                self.position.character += scalar.len_utf16();
            }
        }
        Ok(())
    }
}

pub(crate) fn validate(
    snapshot: &Snapshot,
    legend: &SemanticTokensLegend,
    data: &[u32],
) -> Result<(), &'static str> {
    if data.len() % 5 != 0 || data.iter().any(|value| *value > i32::MAX as u32) {
        return Err("invalid semantic token encoding");
    }
    let allowed_modifiers = if legend.token_modifiers.len() >= 31 {
        i32::MAX as u32
    } else {
        (1 << legend.token_modifiers.len()) - 1
    };
    let mut cursor = Cursor {
        chars: snapshot.text.chars().peekable(),
        position: Position {
            line: 0,
            character: 0,
        },
    };
    let (mut line, mut start) = (0usize, 0usize);
    for token in data.chunks_exact(5) {
        line = line
            .checked_add(token[0] as usize)
            .ok_or("semantic token overflow")?;
        start = if token[0] == 0 {
            start
                .checked_add(token[1] as usize)
                .ok_or("semantic token overflow")?
        } else {
            token[1] as usize
        };
        if token[2] == 0
            || token[3] as usize >= legend.token_types.len()
            || token[4] & !allowed_modifiers != 0
        {
            return Err("invalid semantic token length or legend index");
        }
        let end = start
            .checked_add(token[2] as usize)
            .ok_or("semantic token overflow")?;
        cursor.advance(Position {
            line,
            character: start,
        })?;
        cursor.advance(Position {
            line,
            character: end,
        })?;
    }
    Ok(())
}
