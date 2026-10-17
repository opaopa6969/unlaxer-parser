use unlaxer_runtime::source::Snapshot;

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

struct Index {
    starts: Vec<usize>,
    ends: Vec<usize>,
    boundaries: Vec<bool>,
}
impl Index {
    // O(source + tokens), including overlapping tokens: never rescan a source prefix per token.
    fn new(text: &str) -> Self {
        let mut result = Self {
            starts: vec![0],
            ends: vec![],
            boundaries: vec![true],
        };
        let mut chars = text.chars().peekable();
        let mut units = 0;
        while let Some(scalar) = chars.next() {
            if scalar == '\r' || scalar == '\n' {
                result.ends.push(units);
                units += 1;
                result.boundaries.push(true);
                if scalar == '\r' && chars.peek() == Some(&'\n') {
                    result.boundaries[units] = false;
                    chars.next();
                    units += 1;
                    result.boundaries.push(true);
                }
                result.starts.push(units);
            } else {
                if scalar.len_utf16() == 2 {
                    result.boundaries.push(false);
                }
                units += scalar.len_utf16();
                result.boundaries.push(true);
            }
        }
        result.ends.push(units);
        result
    }
}

pub(crate) fn validate(
    snapshot: &Snapshot,
    legend: &SemanticTokensLegend,
    data: &[u32],
    multiline: bool,
    overlapping: bool,
) -> Result<(), &'static str> {
    if data.len() % 5 != 0 || data.iter().any(|value| *value > i32::MAX as u32) {
        return Err("invalid semantic token encoding");
    }
    if data.is_empty() {
        return Ok(());
    }
    let allowed_modifiers = if legend.token_modifiers.len() >= 31 {
        i32::MAX as u32
    } else {
        (1 << legend.token_modifiers.len()) - 1
    };
    let index = Index::new(&snapshot.text);
    let (mut line, mut start, mut previous_end) = (0usize, 0usize, 0usize);
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
        if token[3] >= 65536
            || token[3] as usize >= legend.token_types.len()
            || token[4] & !allowed_modifiers != 0
        {
            return Err("invalid semantic token length or legend index");
        }
        let line_end = *index
            .ends
            .get(line)
            .ok_or("semantic token outside document")?;
        let absolute = index.starts[line]
            .checked_add(start)
            .ok_or("semantic token overflow")?;
        if absolute > line_end {
            return Err("semantic token outside line");
        }
        let raw_end = absolute
            .checked_add(token[2] as usize)
            .ok_or("semantic token overflow")?;
        // LSP 3.17: clients without multiline support interpret excess length at line end.
        let end = if multiline {
            raw_end
        } else {
            raw_end.min(line_end)
        };
        if index.boundaries.get(absolute) != Some(&true)
            || index.boundaries.get(end) != Some(&true)
            || !overlapping && end > absolute && absolute < previous_end
        {
            return Err("invalid semantic token position or overlap");
        }
        previous_end = previous_end.max(end);
    }
    Ok(())
}
