use std::cell::Cell;
use unlaxer_runtime::{
    token::{TokenKind, TokenSource},
    Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
};
thread_local! { static CALLS:Cell<usize>=const{Cell::new(0)}; }
fn kind(s: &str) -> TokenKind {
    match s {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("{s}"),
    }
}
fn baseline(c: &mut ParseContext<'_>) {
    c.set_state("value", 7u32);
    c.parse_with_token_mode(
        &Expr::Literal("p").capture_owned("before"),
        TokenKind::Consumed,
        false,
    )
    .unwrap();
    c.parse_with_token_mode(&Expr::Literal("😀"), TokenKind::MatchOnly, false)
        .unwrap();
}
fn child(c: &mut ParseContext<'_>) -> ParseResult {
    CALLS.with(|v| v.set(v.get() + 1));
    c.set_state("value", 99u32);
    match c.state::<String>("child").unwrap().as_str() {
        "throw" => panic!("child must not run"),
        "recursive" => c.parse(&Expr::Custom(child)),
        "success" => c.parse(&Expr::Literal("😀")),
        _ => Err(c.error("child failure")),
    }
}
#[test]
fn maximum_zero_does_not_attempt_its_matching_child() {
    let mut c = ParseContext::new("a");
    assert!(c.parse(&Expr::Literal("a").repeat_java(0, Some(0))).is_ok());
    assert_eq!(c.position(), 0);
}

#[test]
fn explicit_zero_checks_preserve_sticky_resource_failure_on_a_standard_stack() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            for memo in [Memoization::Off, Memoization::SafeFailures] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    let mut expression = Expr::Literal("x");
                    for _ in 0..1500 {
                        expression = expression.zero_match();
                    }
                    let mut context = ParseContext::with_options(
                        "x",
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    let error = context.parse(&expression).unwrap_err();
                    assert_eq!(error.expected, ["expression nesting below 1024"]);
                    assert_eq!((context.position(), context.matched_position()), (0, 0));
                    assert_eq!(context.parse(&Expr::Literal("x")).unwrap_err(), error);
                    // Runtime budgets do not cover recursive destruction of hand-built trees.
                    std::mem::forget(expression);
                }
            }
        })
        .unwrap()
        .join()
        .unwrap();
}
#[test]
fn shared_bounded_zero_oracle_skips_children_and_retains_both_cursors() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for native in [false, true] {
                for row in include_str!("../../../conformance/zero-repeat/bounded.tsv")
                    .lines()
                    .filter(|r| !r.starts_with('#'))
                {
                    let e: Vec<_> = row.split('\t').collect();
                    let mut c = ParseContext::with_options(
                        "p😀x",
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    baseline(&mut c);
                    c.set_state("child", e[1].to_owned());
                    c.set_reset_matched_with_consumed(e[4] == "true");
                    CALLS.with(|v| v.set(0));
                    let parser = if native {
                        Expr::Custom(child)
                            .repeat(e[2].parse().unwrap(), Some(e[3].parse().unwrap()))
                    } else {
                        Expr::Custom(child)
                            .repeat_java(e[2].parse().unwrap(), Some(e[3].parse().unwrap()))
                    };
                    let result = c.parse_with_token_mode(&parser, kind(e[5]), e[6] == "true");
                    assert_eq!(result.is_ok(), e[7] == "true", "{}", e[0]);
                    assert_eq!(c.position().to_string(), e[8], "{}", e[0]);
                    assert_eq!(c.matched_position().to_string(), e[9], "{}", e[0]);
                    assert_eq!(c.state::<u32>("value"), Some(&7), "{}", e[0]);
                    assert_eq!(c.captured_text("before"), Some("p"));
                    assert_eq!(c.reset_matched_with_consumed(), e[4] == "true");
                    assert_eq!(CALLS.with(Cell::get), 0, "{}", e[0]);
                    if let Ok(matched) = result {
                        let id = c.token_node_id(matched.nodes[0]).unwrap();
                        let info = c.token_info(id).unwrap();
                        assert_eq!(info.kind, kind(e[5]));
                        assert_eq!(c.token_text(id), Some(""));
                        let start = match info.source {
                            TokenSource::Input(s) | TokenSource::Projected { span: s, .. } => {
                                s.start
                            }
                            TokenSource::Generated { anchor, .. } => anchor,
                        };
                        assert_eq!(start.to_string(), e[10], "{}", e[0]);
                        assert!(info.original_tokens().unwrap().is_empty());
                        assert!(c.failure().expected.is_empty());
                    } else {
                        let diagnostic = c.failure();
                        let deferred = diagnostics == Diagnostics::DetailedOnFailure;
                        assert_eq!(diagnostic.offset, if deferred { 0 } else { 2 }, "{}", e[0]);
                        assert_eq!(!diagnostic.expected.is_empty(), !deferred, "{}", e[0]);
                    }
                }
            }
        }
    }
}
fn wrapped(parser: Expr, wrapper: &str) -> Expr {
    match wrapper {
        "none" => parser,
        "consume" => parser.stop_consume(),
        "invert" => parser.stop_invert(),
        "match" => parser.match_only(),
        _ => panic!("{wrapper}"),
    }
}
#[test]
fn shared_historical_zero_and_eof_oracle_preserves_modes_stoppers_and_diagnostics() {
    let rows: Vec<Vec<_>> = include_str!("../../../conformance/zero-repeat/legacy-zero.tsv")
        .lines()
        .filter(|r| !r.starts_with('#'))
        .map(|r| r.split('\t').collect())
        .collect();
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for variant in ["eof", "zero"] {
                for wrapper in ["none", "consume", "invert", "match"] {
                    for input in &rows {
                        let effective_kind = match wrapper {
                            "consume" => "consumed",
                            "match" => "matchOnly",
                            _ => input[2],
                        };
                        let invert = if wrapper == "invert" {
                            "false"
                        } else {
                            input[3]
                        };
                        let e = rows
                            .iter()
                            .find(|e| {
                                e[0] == input[0]
                                    && e[1] == input[1]
                                    && e[2] == effective_kind
                                    && e[3] == invert
                            })
                            .unwrap();
                        let mut c = ParseContext::with_options(
                            match input[0] {
                                "ahead" => "a😀",
                                "start" => "😀",
                                _ => "",
                            },
                            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                        );
                        if input[0] == "ahead" {
                            c.parse_with_token_mode(
                                &Expr::Literal("a"),
                                TokenKind::Consumed,
                                false,
                            )
                            .unwrap();
                            c.parse_with_token_mode(
                                &Expr::Literal("😀"),
                                TokenKind::MatchOnly,
                                false,
                            )
                            .unwrap();
                        }
                        c.set_reset_matched_with_consumed(input[1] == "true");
                        let parser = wrapped(
                            if variant == "eof" {
                                Expr::Eof
                            } else {
                                Expr::Any.zero_match()
                            },
                            wrapper,
                        );
                        let result =
                            c.parse_with_token_mode(&parser, kind(input[2]), input[3] == "true");
                        let label =
                            format!("{variant}/{wrapper}/{input:?}/{memo:?}/{diagnostics:?}");
                        assert_eq!(result.is_ok(), e[4] == "true", "{label}");
                        assert_eq!(c.position().to_string(), e[5], "{label}");
                        assert_eq!(c.matched_position().to_string(), e[6], "{label}");
                        let d = c.failure();
                        let deferred = diagnostics == Diagnostics::DetailedOnFailure;
                        assert_eq!(
                            d.offset,
                            if deferred { 0 } else { e[7].parse().unwrap() },
                            "{label}"
                        );
                        assert_eq!(!d.expected.is_empty(), !deferred, "{label}");
                        if let Ok(matched) = result {
                            let id = c.token_node_id(matched.nodes[0]).unwrap();
                            let info = c.token_info(id).unwrap();
                            assert_eq!(info.kind, kind(effective_kind), "{label}");
                            assert_eq!(c.token_text(id), Some(""));
                            let start = match info.source {
                                TokenSource::Input(s) | TokenSource::Projected { span: s, .. } => {
                                    s.start
                                }
                                TokenSource::Generated { anchor, .. } => anchor,
                            };
                            assert_eq!(start.to_string(), e[8], "{label}");
                        }
                    }
                }
            }
        }
    }
}
