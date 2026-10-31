//! Java Usage003 palindrome recipes ported through the public owned-capture API (#1017).
//! Rust has no MatchedTokenParser: the explicit adapter selects the latest owned binding,
//! applies the Java Slicer/effect projection on code points and matches it at the cursor.
//! `capture_scope` bounds the binding's lifetime, and rollback discards failed attempts.
use unlaxer_runtime::{
    share_grammar,
    token::{TokenKind, TokenSource},
    Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult, Rule,
    Span, TOKEN_LEAF_RULE,
};

/// Java Slicer.get: begin/end are code point indexes, a negative step walks backwards.
fn slice(word: &[char], begin: usize, end: usize, step: isize) -> String {
    match step {
        1 => word[begin..end].iter().collect(),
        0 => String::new(),
        step if step < 0 => (begin..end)
            .rev()
            .step_by(step.unsigned_abs())
            .map(|i| word[i])
            .collect(),
        step => (begin..end)
            .step_by(step as usize)
            .map(|i| word[i])
            .collect(),
    }
}
fn half(word: &[char]) -> usize {
    (word.len() - word.len() % 2) / 2
}
/// sliceWithWord / sliceWithSlicer first step: `word[0..half]`.
fn first_half(word: &[char]) -> String {
    slice(word, 0, half(word), 1)
}
/// Second step: the pivot of an odd word, otherwise Slicer.invalidate (empty).
fn pivot(word: &[char]) -> String {
    if word.len() % 2 == 1 {
        slice(word, half(word), half(word) + 1, 1)
    } else {
        slice(word, 0, 0, 1)
    }
}
/// Third step: `slice(range, reverse=true)` and `end(half).step(-1)` give the same text.
fn reversed_half(word: &[char]) -> String {
    slice(word, 0, half(word), -1)
}
/// effectReverse: a detached reversed copy.
fn effect_reverse(word: &[char]) -> String {
    word.iter().rev().collect()
}
/// sliceReverse: `step(-1)` over the whole word.
fn slice_reverse(word: &[char]) -> String {
    slice(word, 0, word.len(), -1)
}
/// sliceReverseWithPythonian: Java Slicer.pythonian("::-1").
fn pythonian_reverse(word: &[char]) -> String {
    let parts: Vec<&str> = "::-1".splitn(3, ':').collect();
    let step = parts
        .get(2)
        .filter(|s| !s.is_empty())
        .map_or(1, |s| s.parse().unwrap());
    let index = |text: &str, default: usize| {
        if text.is_empty() {
            return default;
        }
        let value: isize = text.parse().unwrap();
        if value < 0 {
            (value + word.len() as isize) as usize
        } else {
            value as usize
        }
    };
    slice(
        word,
        index(parts[0], 0),
        index(parts.get(1).copied().unwrap_or(""), word.len()),
        step,
    )
}

fn matched(context: &mut ParseContext<'_>, projection: fn(&[char]) -> String) -> ParseResult {
    // The word is input-backed; a projection is never recorded as an input span.
    let Some(TokenSource::Input(word)) = context.captured_source("word").cloned() else {
        return Err(context.error("matched word"));
    };
    let word: Vec<char> = context.text(word).unwrap().chars().collect();
    let projected = projection(&word);
    let start = context.position();
    if !context.remaining().starts_with(projected.as_str()) {
        return Err(context.error("matched projection"));
    }
    // Like Java's empty projection, an empty text matches zero width at the cursor.
    context.advance(projected.chars().count());
    let span = Span {
        start,
        end: context.position(),
    };
    // Java WordParser publishes the matched input, not the projection, as a consumed token.
    let id = context
        .add_token_node(
            TOKEN_LEAF_RULE,
            TokenKind::Consumed,
            TokenSource::Input(span),
        )
        .unwrap();
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// Consumed input-backed adapter leaves in source order, as Java's flattened CST tokens.
fn leaves(context: &mut ParseContext<'_>, node: usize, out: &mut Vec<String>) {
    let current = context.node(node).unwrap().clone();
    if current.rule == TOKEN_LEAF_RULE {
        let id = context.token_node_id(node).unwrap();
        let info = context.token_info(id).unwrap();
        assert_eq!(info.kind, TokenKind::Consumed);
        assert_eq!(info.source, TokenSource::Input(current.span));
        // The '😀' / 'a' literals are Classic WordParser leaves; adapter leaves are unlabeled.
        match info.parser_label() {
            None => out.push(format!("{}-{}", current.span.start, current.span.end)),
            label => assert_eq!(label, Some("WordParser")),
        }
    }
    for child in current.children {
        leaves(context, child, out);
    }
}
fn step_first_half(c: &mut ParseContext<'_>) -> ParseResult {
    matched(c, first_half)
}
fn step_pivot(c: &mut ParseContext<'_>) -> ParseResult {
    matched(c, pivot)
}
fn step_reversed_half(c: &mut ParseContext<'_>) -> ParseResult {
    matched(c, reversed_half)
}
fn step_effect_reverse(c: &mut ParseContext<'_>) -> ParseResult {
    matched(c, effect_reverse)
}
fn step_slice_reverse(c: &mut ParseContext<'_>) -> ParseResult {
    matched(c, slice_reverse)
}
fn step_pythonian_reverse(c: &mut ParseContext<'_>) -> ParseResult {
    matched(c, pythonian_reverse)
}

/// Java recipe order: sliceWithWord, sliceWithSlicer, effectReverse, sliceReverse, pythonian.
fn recipe(index: usize) -> Expr {
    let steps: Vec<fn(&mut ParseContext<'_>) -> ParseResult> = match index {
        0 | 1 => vec![step_first_half, step_pivot, step_reversed_half],
        2 => vec![step_effect_reverse],
        3 => vec![step_slice_reverse],
        _ => vec![step_pythonian_reverse],
    };
    // Java MatchOnly(OneOrMore(AsciiParser)): U+0000..U+007F, greedy.
    let word = Expr::CharRange('\0', '\u{7f}')
        .one_or_more()
        .match_only()
        .capture_owned("word");
    Expr::sequence(
        std::iter::once(word).chain(
            steps
                .into_iter()
                .map(|step| Expr::Custom(step).capture("segment")),
        ),
    )
    .capture_scope()
}

#[test]
fn palindrome_sample_matches_shared_fixture() {
    let mut rows = 0;
    let mut observations = 0;
    for recipe_index in 0..5 {
        let grammars = [
            ("single", Expr::Rule(1)),
            (
                "reuse",
                Expr::sequence([Expr::Rule(1), Expr::literal("😀"), Expr::Rule(1)]),
            ),
            (
                "retry",
                Expr::choice([
                    Expr::sequence([Expr::literal("a"), Expr::Rule(1)]),
                    Expr::Rule(1),
                ]),
            ),
        ]
        .map(|(name, top)| {
            (
                name,
                share_grammar(vec![
                    Rule {
                        name: "Top",
                        expression: top,
                    },
                    Rule {
                        name: "Palindrome",
                        expression: recipe(recipe_index),
                    },
                ]),
            )
        });
        rows = 0;
        for line in include_str!("../../../conformance/samples/palindrome.tsv").lines() {
            if line.starts_with('#') || line.is_empty() {
                continue;
            }
            let row = line.split('\t').collect::<Vec<_>>();
            assert_eq!(row.len(), 7, "{line}");
            rows += 1;
            let grammar = &grammars.iter().find(|(name, _)| *name == row[0]).unwrap().1;
            let input = row[1].replace("\\r", "\r").replace("\\n", "\n");
            let expected = if recipe_index < 2 { row[5] } else { row[6] };
            for memoization in [Memoization::Off, Memoization::SafeFailures] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    let mut context = ParseContext::with_options(
                        &input,
                        ParseOptions {
                            memoization,
                            diagnostics,
                            ..ParseOptions::default()
                        },
                    );
                    let label = format!("{recipe_index} {line} {memoization:?} {diagnostics:?}");
                    let parsed = context.parse_shared_grammar(grammar, 0, false);
                    assert_eq!(parsed.is_ok(), row[2] == "true", "{label}");
                    assert_eq!(
                        context.position(),
                        row[3].parse::<usize>().unwrap(),
                        "{label}"
                    );
                    assert_eq!(
                        context.matched_position(),
                        row[4].parse::<usize>().unwrap(),
                        "{label}"
                    );
                    // Bindings are scoped to one palindrome; nothing leaks after the parse.
                    assert!(context.capture_sources("word").is_empty(), "{label}");
                    let Ok(parsed) = parsed else {
                        assert_eq!(expected, "-", "{label}");
                        assert!(context.capture_spans("segment").is_empty(), "{label}");
                        continue;
                    };
                    let consumed: String = input.chars().take(context.position()).collect();
                    assert_eq!(context.text(parsed.span).unwrap(), consumed, "{label}");
                    let segments = context
                        .capture_spans("segment")
                        .iter()
                        .map(|span| format!("{}-{}", span.start, span.end))
                        .collect::<Vec<_>>()
                        .join(",");
                    assert_eq!(segments, expected, "{label}");
                    let mut cst = Vec::new();
                    for node in parsed.nodes {
                        leaves(&mut context, node, &mut cst);
                    }
                    assert_eq!(cst.join(","), expected, "{label}");
                    observations += 1;
                }
            }
        }
    }
    assert_eq!(rows, 26);
    // 5 recipes x 13 succeeding rows x 2 memo x 3 diagnostics, as in the Java test.
    assert_eq!(observations, 390);
}

#[test]
fn projections_follow_java_slicer_on_code_points() {
    let word = |text: &str| text.chars().collect::<Vec<_>>();
    assert_eq!(first_half(&word("abcba")), "ab");
    assert_eq!(pivot(&word("abcba")), "c");
    assert_eq!(pivot(&word("abba")), "");
    assert_eq!(reversed_half(&word("abccba")), "cba");
    assert_eq!(first_half(&word("a")), "");
    // Non-BMP scalars stay whole: these helpers count code points, not UTF-16 units.
    assert_eq!(effect_reverse(&word("a😀b")), "b😀a");
    assert_eq!(slice_reverse(&word("a😀b")), "b😀a");
    assert_eq!(pythonian_reverse(&word("a😀b")), "b😀a");
    assert_eq!(slice(&word("abcde"), 0, 5, 2), "ace");
    assert_eq!(slice(&word("abcde"), 0, 5, -2), "eca");
    assert_eq!(slice(&word("abc"), 0, 3, 0), "");
}
