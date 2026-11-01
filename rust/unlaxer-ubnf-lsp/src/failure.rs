//! LSP-only replay, called after the ordinary frontend has failed. It never
//! changes that frontend's first-error contract or adds work to successful parses.
mod header;

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

/// ParseResult positions use DocumentSnapshot, where CR, LF and CRLF each end
/// a line. Module recovery ranges keep the LF-only text::position instead.
fn position(source: &str, at: usize) -> Value {
    let (mut line, mut character, mut previous) = (0, 0, '\0');
    for c in source[..at].chars() {
        match c {
            '\n' if previous == '\r' => {}
            '\r' | '\n' => (line, character) = (line + 1, 0),
            _ => character += c.len_utf16(),
        }
        previous = c;
    }
    json!({"line": line, "character": character})
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
    // Grammar headers are replayed below; body/unterminated-comment failures
    // retain their native diagnostic until the remaining #1024 work is done.
    let root_diagnostic = (!complete && !source[at..].starts_with("grammar")).then(|| {
        // Failed Chain parents roll back before recording their own cursor.
        // With leading trivia their depth-two brace hints are behind the
        // keyword failure. A new attempt after a successful grammar starts at
        // the consumed cursor, because the preceding grammar owns its trivia.
        // CPPComment is '//' plus a wildcard that requires a line terminator.
        // A whole-trivia input ending in such a comment fails at EOF inside it.
        let tail = &source[source.rfind(['\r', '\n']).map_or(0, |i| i + 1)..];
        let open_comment = consumed == 0
            && at == source.len()
            && tail.trim_start_matches(text::regex_space).starts_with("//");
        let (expected, deepest) = if open_comment {
            let wildcard = "WildCardCharacterParser";
            let line_ends = vec![
                "'\n'",
                "'\r\n'",
                "'\r'",
                "' '",
                "'//'",
                "'grammar'",
                wildcard,
            ];
            (line_ends, wildcard)
        } else if at == consumed {
            (vec!["' '", "'//'", "'grammar'", "'{'", "'}'"], "WordParser")
        } else {
            (vec!["' '", "'//'", "'grammar'"], "WordParser")
        };
        diagnostic(source, at, consumed, &expected, deepest)
    });
    let root_diagnostic = root_diagnostic.or_else(|| {
        if complete {
            None
        } else {
            header::replay(source, consumed, consumed)
        }
    });
    Replay {
        consumed: source[..consumed].encode_utf16().count(),
        complete,
        root_diagnostic,
    }
}

/// Bootstrap parsers do not implement GrammarRule: ruleStack,
/// ruleStackOmitted and relatedInformation are absent, not empty arrays.
fn diagnostic(
    source: &str,
    at: usize,
    consumed: usize,
    expected: &[impl AsRef<str>],
    deepest: &str,
) -> Value {
    let expected = expected.iter().map(AsRef::as_ref).collect::<Vec<_>>();
    let end = at + source[at..].chars().next().map_or(0, char::len_utf8);
    let pos = position(source, at);
    let message = format!(
        "Expected {} (in {deepest})",
        expected
            .iter()
            .map(AsRef::as_ref)
            .collect::<Vec<_>>()
            .join(" or ")
    );
    json!({
        "range": {"start": pos, "end": position(source, end)}, "severity": 1,
        "code": "ULX-PARSE-001", "source": "unlaxer", "message": message,
        "data": {
            "schemaVersion": 1, "code": "ULX-PARSE-001", "kind": "syntax",
            "offset": source[..at].encode_utf16().count(),
            "line": pos["line"], "character": pos["character"],
            "consumedLength": source[..consumed].encode_utf16().count(),
            "totalLength": source.encode_utf16().count(),
            "expectedTokens": expected, "expectedHints": expected,
            "expectedParsers": expected, "deepestMatchedRule": deepest
        }
    })
}
