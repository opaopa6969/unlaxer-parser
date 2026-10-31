//! LSP-only replay, called after the ordinary frontend has failed. It never
//! changes that frontend's first-error contract or adds work to successful parses.
use crate::text;
use serde_json::{json, Value};
use unlaxer_ubnf::{parse, DiagnosticKind};

pub struct Replay {
    pub consumed: usize,
    pub complete: bool,
    pub root_diagnostic: Option<Value>,
}

fn trivia(source: &str, mut at: usize) -> usize {
    loop {
        let before = at;
        while let Some(c) = source[at..]
            .chars()
            .next()
            .filter(|c| text::regex_space(*c))
        {
            at += c.len_utf8();
        }
        if source[at..].starts_with("//") {
            at += source[at..].find(['\r', '\n']).unwrap_or(source.len() - at);
        }
        if before == at {
            return at;
        }
    }
}

/// Find a grammar's closing brace without treating quoted/comment text as syntax.
/// This scan does not authorize a prefix: the ordinary frontend checks it below.
fn grammar_end(source: &str, start: usize) -> Option<usize> {
    let mut at = start;
    let mut braces = 0usize;
    while at < source.len() {
        let c = source[at..].chars().next()?;
        if source[at..].starts_with("//") {
            at += source[at..].find(['\r', '\n']).unwrap_or(source.len() - at);
            continue;
        }
        at += c.len_utf8();
        match c {
            '\'' => loop {
                let c = source[at..].chars().next()?;
                at += c.len_utf8();
                if c == '\\' {
                    at += source[at..].chars().next()?.len_utf8();
                } else if c == '\'' {
                    break;
                }
            },
            '{' => braces += 1,
            '}' if braces > 0 => {
                braces -= 1;
                if braces == 0 {
                    return Some(at);
                }
            }
            _ => {}
        }
    }
    None
}

pub fn replay(source: &str) -> Replay {
    let mut consumed = 0;
    let mut at = trivia(source, 0);
    while source[at..].starts_with("grammar") {
        let Some(end) = grammar_end(source, at) else {
            break;
        };
        // Values and native AST limits are checked after whole-file syntax
        // (#983/#996). They do not make a syntactically complete root fail.
        if let Err(error) = parse(&source[at..end]) {
            if !matches!(
                error.kind,
                DiagnosticKind::InvalidValue | DiagnosticKind::NestingLimit
            ) {
                break;
            }
        }
        at = trivia(source, end);
        consumed = at;
    }
    let complete = consumed > 0 && consumed == source.len();
    // This is the exact bootstrap UBNFFile -> GrammarDecl -> WordParser
    // failure, including the depth-two hints of failed parent combinators.
    // Failures inside GrammarDecl need speculative provenance (#1024); retain
    // their native diagnostic instead of guessing/normalizing a farthest offset.
    let root_diagnostic = (!complete && !source[at..].starts_with("grammar")).then(|| {
        let end = at + source[at..].chars().next().map_or(0, char::len_utf8);
        let pos = text::position(source, at);
        // Failed Chain parents roll back before recording their own cursor.
        // With leading trivia their depth-two brace hints are behind the
        // keyword failure. A new attempt after a successful grammar starts at
        // the consumed cursor, because the preceding grammar owns its trivia.
        let expected = if at == consumed {
            vec!["' '", "'//'", "'grammar'", "'{'", "'}'"]
        } else {
            vec!["' '", "'//'", "'grammar'"]
        };
        let message = format!("Expected {} (in WordParser)", expected.join(" or "));
        json!({
            "range": text::range(source, at, end), "severity": 1,
            "code": "ULX-PARSE-001", "source": "unlaxer",
            "message": message,
            "data": {
                "schemaVersion": 1, "code": "ULX-PARSE-001", "kind": "syntax",
                "offset": source[..at].encode_utf16().count(),
                "line": pos["line"], "character": pos["character"],
                "consumedLength": source[..consumed].encode_utf16().count(),
                "totalLength": source.encode_utf16().count(),
                "expectedTokens": expected, "expectedHints": expected,
                "expectedParsers": expected, "deepestMatchedRule": "WordParser"
            }
        })
    });
    Replay {
        consumed: source[..consumed].encode_utf16().count(),
        complete,
        root_diagnostic,
    }
}
