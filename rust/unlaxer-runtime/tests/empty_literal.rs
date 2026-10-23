//! Empty literals (#713): handwritten PEG `Literal("")` versus the two Java parsers
//! generated for UBNF `''`. The generated grammars are compared in the shared corpus.
use unlaxer_runtime::token::{TokenKind, TokenSource};
use unlaxer_runtime::token_trace::Limits;
use unlaxer_runtime::{
    parse_detailed_with_options, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Rule,
};

const MODES: [(TokenKind, bool); 4] = [
    (TokenKind::Consumed, false),
    (TokenKind::Consumed, true),
    (TokenKind::MatchOnly, false),
    (TokenKind::MatchOnly, true),
];

fn cursors(input: &str, expression: &Expr) -> (bool, usize, usize) {
    let mut context = ParseContext::new(input);
    let accepted = context.parse(expression).is_ok();
    (accepted, context.position(), context.matched_position())
}

fn classic(input: &str, expression: &Expr) -> Vec<(bool, usize, usize)> {
    MODES
        .iter()
        .map(|&(kind, invert)| {
            let mut context = ParseContext::new(input);
            let accepted = context
                .parse_with_token_mode(expression, kind, invert)
                .is_ok();
            (accepted, context.position(), context.matched_position())
        })
        .collect()
}

#[test]
fn handwritten_empty_literal_keeps_peg_success() {
    for input in ["", "x", "😀"] {
        assert_eq!(cursors(input, &Expr::literal("")), (true, 0, 0));
        assert_eq!(cursors(input, &Expr::literal("").optional()), (true, 0, 0));
        // The PEG progress guard rejects an unbounded zero-width repetition.
        assert_eq!(
            cursors(input, &Expr::literal("").repeat(0, None)),
            (false, 0, 0)
        );
    }
}

#[test]
fn java_empty_word_never_matches_and_expects_the_empty_word() {
    for input in ["", "x", "😀"] {
        assert_eq!(cursors(input, &Expr::JavaEmptyWord), (false, 0, 0));
        assert_eq!(classic(input, &Expr::JavaEmptyWord), vec![(false, 0, 0); 4]);
    }
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let failure = parse_detailed_with_options(
                &[Rule {
                    name: "root",
                    expression: Expr::literal("😀").then(Expr::JavaEmptyWord),
                }],
                0,
                false,
                "😀b",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            )
            .unwrap_err();
            assert_eq!((failure.kind, failure.offset), ("syntax", 1));
            assert_eq!(failure.expected, vec![String::new()]);
        }
    }
    // Occurs stops at the first failed attempt instead of looping without progress.
    let repeated = Expr::literal("a").then(Expr::JavaEmptyWord.repeat_java(0, None));
    assert_eq!(cursors("ab", &repeated), (true, 1, 1));
    assert!(cursors("ab", &Expr::JavaEmptyWord.repeat_java(1, None)) == (false, 0, 0));
}

#[test]
fn failed_java_empty_word_resets_the_match_cursor_like_a_failed_word() {
    let lookahead = Expr::JavaLookahead {
        pattern: "b",
        positive: true,
    };
    for word in [Expr::JavaEmptyWord, Expr::literal("z")] {
        let expression = Expr::literal("a")
            .then(lookahead.clone())
            .then(word.optional_java());
        assert_eq!(cursors("ab", &expression), (true, 1, 1));
        assert_eq!(
            classic("ab", &expression),
            vec![(true, 1, 1), (false, 0, 0), (true, 0, 2), (false, 0, 0)]
        );
    }
}

#[test]
fn java_empty_literal_is_zero_width_and_fails_only_when_inverted() {
    for input in ["", "x", "😀"] {
        assert_eq!(cursors(input, &Expr::JavaEmptyLiteral), (true, 0, 0));
        assert_eq!(
            classic(input, &Expr::JavaEmptyLiteral),
            vec![(true, 0, 0), (false, 0, 0), (true, 0, 0), (false, 0, 0)]
        );
        assert_eq!(
            cursors(input, &Expr::JavaEmptyLiteral.repeat_java(0, None)),
            (true, 0, 0)
        );
    }
}

#[test]
fn only_the_java_word_resets_the_match_cursor_inside_occurs() {
    // Values observed with Java Chain(InvertMatchPropagationStopper(Chain(WordParser("a"),
    // MatchOnly(WordParser("b")))), Optional(tail)) on "ab": a failed word calls
    // consume(0); Lexing.LiteralParser never does, and an inverted "z" consumes "b".
    for (tail, expected) in [
        (Expr::JavaEmptyWord, [(true, 1, 1), (true, 1, 1)]),
        (Expr::JavaEmptyLiteral, [(true, 1, 2), (true, 1, 2)]),
        (Expr::literal("z"), [(true, 1, 1), (true, 2, 2)]),
    ] {
        let expression = Expr::StopInvert(Box::new(Expr::literal("a").then(Expr::JavaLookahead {
            pattern: "b",
            positive: true,
        })))
        .then(tail.clone().optional_java());
        for (invert, expected) in [false, true].into_iter().zip(expected) {
            let mut context = ParseContext::new("ab");
            let accepted = context
                .parse_with_token_mode(&expression, TokenKind::Consumed, invert)
                .is_ok();
            assert_eq!(
                (accepted, context.position(), context.matched_position()),
                expected,
                "{tail:?} invert={invert}"
            );
        }
    }
}

#[test]
fn token_trace_keeps_the_zero_width_java_literal_leaf() {
    // Java's LiteralParser("") is a LexicalTokenParser: on success it adds a zero-width token.
    let expression = Expr::literal("😀")
        .then(Expr::JavaEmptyLiteral)
        .then(Expr::literal("b"));
    let options = ParseOptions::default().with_token_trace(Limits::default());
    let mut context = ParseContext::with_options("😀b", options);
    assert!(context.parse(&expression).is_ok());
    let leaves = context.token_trace().unwrap().unwrap();
    let observed = leaves
        .iter()
        .map(|leaf| match &leaf.source {
            TokenSource::Input(span) => (leaf.label, span.start, span.end, leaf.consumed),
            other => panic!("{other:?}"),
        })
        .collect::<Vec<_>>();
    assert_eq!(
        observed,
        vec![
            ("WordParser", 0, 1, 1),
            ("LexicalTokenParser", 1, 1, 1),
            ("WordParser", 1, 2, 2),
        ]
    );
    // A failed WordParser("") leaves no leaf behind its rolled-back sequence.
    let failed = Expr::literal("😀").then(Expr::JavaEmptyWord).optional();
    let mut context = ParseContext::with_options("😀b", options);
    assert!(context.parse(&failed).is_ok());
    assert_eq!(context.token_trace().unwrap().unwrap(), vec![]);
}

/// `spec-corpus/empty-literal` Labeled: `CATCH('a' LABEL(L, ''), 'a' 'b', L) | 'c' LABEL(M, '')`.
fn labeled() -> Expr {
    Expr::literal("a")
        .then(Expr::JavaEmptyWord.label("L"))
        .catch_labels(Expr::literal("a").then(Expr::literal("b")), ["L"])
        .or(Expr::literal("c").then(Expr::JavaEmptyWord.label("M")))
}

#[test]
fn display_shows_the_empty_expected_candidate_as_java_quotes() {
    let failure = parse_detailed_with_options(
        &[Rule {
            name: "Root",
            expression: labeled(),
        }],
        0,
        false,
        "a😀",
        ParseOptions::default(),
    )
    .unwrap_err();
    // The contract value stays the empty string; only the human-readable text changes (#750).
    assert_eq!((failure.kind, failure.offset), ("syntax", 1));
    assert_eq!(failure.expected, vec![String::new(), "b".to_owned()]);
    assert_eq!(
        failure.farthest.expected,
        vec![String::new(), "b".to_owned()]
    );
    assert_eq!(
        failure.canonical_json(),
        r#"{"kind":"syntax","offset":1,"expected":["","b"],"farthestOffset":1,"farthestExpected":["","b"]}"#
    );
    assert_eq!(
        failure.to_string(),
        "syntax at code point 1: expected '', b"
    );
    assert_eq!(
        failure.farthest.to_string(),
        "at code point 1: expected '', b"
    );
    // Only the empty candidate is quoted; the others and the separator are unchanged.
    let only_empty = unlaxer_runtime::ParseError {
        offset: 0,
        expected: vec![String::new()],
    };
    assert_eq!(only_empty.to_string(), "at code point 0: expected ''");
    let plain = unlaxer_runtime::ParseError {
        offset: 2,
        expected: vec!["a".to_owned(), "end of input".to_owned()],
    };
    assert_eq!(
        plain.to_string(),
        "at code point 2: expected a, end of input"
    );
}
