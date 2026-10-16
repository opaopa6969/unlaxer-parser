use std::fmt::Write;
use unlaxer_runtime::{
    token::{TokenKind, TokenSource},
    Diagnostics, Expr, Memoization, ParseContext, ParseOptions,
};
fn decode(s: &str) -> String {
    if s == "~" {
        String::new()
    } else {
        String::from_utf8(
            s.as_bytes()
                .chunks_exact(2)
                .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
                .collect(),
        )
        .unwrap()
    }
}
fn encode(s: &str) -> String {
    if s.is_empty() {
        "~".into()
    } else {
        s.as_bytes().iter().fold(String::new(), |mut encoded, cp| {
            write!(encoded, "{cp:02x}").unwrap();
            encoded
        })
    }
}
fn kind(s: &str) -> TokenKind {
    match s {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("{s}"),
    }
}
fn kind_name(k: TokenKind) -> &'static str {
    match k {
        TokenKind::Consumed => "consumed",
        TokenKind::MatchOnly => "matchOnly",
        TokenKind::VirtualConsumed => "virtualTokenConsumed",
        TokenKind::VirtualMatchOnly => "virtualTokenMatchOnly",
    }
}
fn w(s: &'static str) -> Expr {
    Expr::literal(s)
}
fn parser(s: &str) -> Expr {
    let (mode, pattern) = s.split_once('/').unwrap();
    let regex = Expr::regex(&decode(pattern)).unwrap();
    match mode {
        "raw" => regex,
        "match" => regex.match_only(),
        "consume" => regex.stop_consume(),
        "stopinvert" => regex.stop_invert(),
        "not" => regex.invert_match(),
        "matchconsume" => regex.stop_consume().match_only(),
        _ => panic!("{s}"),
    }
}
fn signature(c: &mut ParseContext<'_>, index: usize) -> String {
    let id = c.token_node_id(index).unwrap();
    let info = c.token_info(id).unwrap();
    let (start, len) = match &info.source {
        TokenSource::Input(s) | TokenSource::Projected { span: s, .. } => {
            (s.start, s.end - s.start)
        }
        TokenSource::Generated { anchor, text } => (*anchor, text.chars().count()),
    };
    format!(
        "{}@{}:{}:{}",
        kind_name(info.kind),
        start,
        len,
        encode(c.token_text(id).unwrap())
    )
}
fn signatures(c: &mut ParseContext<'_>, nodes: &[usize]) -> String {
    if nodes.is_empty() {
        "~".into()
    } else {
        nodes
            .iter()
            .map(|n| signature(c, *n))
            .collect::<Vec<_>>()
            .join(";")
    }
}
fn label(c: &mut ParseContext<'_>, index: usize) -> String {
    let id = c.token_node_id(index).unwrap();
    c.token_info(id)
        .unwrap()
        .parser_label()
        .unwrap_or("")
        .into()
}
fn leaves(c: &mut ParseContext<'_>, index: usize, target: &mut Vec<usize>) {
    let atomic = matches!(
        label(c, index).as_str(),
        "RegexTokenParser"
            | "WordParser"
            | "NumberParser"
            | "IdentifierParser"
            | "SingleQuotedParser"
    );
    let children = c.node(index).unwrap().children.clone();
    if atomic || children.is_empty() {
        target.push(index)
    } else {
        for child in children {
            leaves(c, child, target)
        }
    }
}
fn verify_classic_oracle(traced: bool) {
    let mut failures = Vec::new();
    let rows: Vec<_> = include_str!("../../../spec-corpus/regex-integration/modes.tsv")
        .lines()
        .filter(|l| !l.starts_with('#') && !l.is_empty())
        .collect();
    assert_eq!(rows.len(), 109);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diag in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            for row in &rows {
                let c: Vec<_> = row.split('\t').collect();
                let input = decode(c[1]);
                let options = ParseOptions::with_memoization(memo).with_diagnostics(diag);
                let options = if traced {
                    options.with_token_trace(Default::default())
                } else {
                    options
                };
                let mut context = ParseContext::with_options(&input, options);
                context.set_reset_matched_with_consumed(false);
                let mut committed = Vec::new();
                for (column, k) in [(5, TokenKind::Consumed), (6, TokenKind::MatchOnly)] {
                    if c[column] != "~" {
                        let text: &'static str = Box::leak(decode(c[column]).into_boxed_str());
                        committed.extend(
                            context
                                .parse_with_token_mode(&w(text), k, false)
                                .unwrap()
                                .nodes,
                        );
                    }
                }
                context.set_reset_matched_with_consumed(c[7] == "true");
                let parsed =
                    context.parse_with_token_mode(&parser(c[2]), kind(c[3]), c[4] == "true");
                let mut values = vec![
                    parsed.is_ok().to_string(),
                    context.position().to_string(),
                    context.matched_position().to_string(),
                    context.failure().offset.to_string(),
                    (!context.failure().expected.is_empty()).to_string(),
                ];
                let mut originals = Vec::new();
                if let Ok(parsed) = parsed {
                    let builtin = c[2].contains("number")
                        || c[2].contains("identifier")
                        || c[2].contains("quoted");
                    for &node in &parsed.nodes {
                        let id = context.token_node_id(node).unwrap();
                        if !builtin {
                            if let Some(raw) = context.token_info(id).unwrap().original_tokens() {
                                originals.extend(raw);
                            } else {
                                originals.push(node);
                            }
                        } else {
                            originals.push(node);
                        }
                    }
                    committed.extend(parsed.nodes);
                }
                values.push(signatures(&mut context, &originals));
                values.push(signatures(&mut context, &committed));
                let mut terminal = Vec::new();
                for node in committed {
                    leaves(&mut context, node, &mut terminal);
                }
                values.push(signatures(&mut context, &terminal));
                let mut expected = c[8..].iter().map(|s| s.to_string()).collect::<Vec<_>>();
                if context.options().diagnostics == Diagnostics::DetailedOnFailure {
                    expected[3] = "0".into();
                    expected[4] = "false".into();
                }
                if traced
                    && !(c[2].contains("number")
                        || c[2].contains("identifier")
                        || c[2].contains("quoted"))
                {
                    let trace = context.token_trace().unwrap().unwrap();
                    let signatures = trace
                        .iter()
                        .map(|leaf| {
                            let span = leaf.span();
                            format!(
                                "{}@{}:{}:{}",
                                kind_name(leaf.kind),
                                span.start,
                                match &leaf.source {
                                    TokenSource::Generated { text, .. } => text.chars().count(),
                                    _ => span.end - span.start,
                                },
                                encode(leaf.text(&input).unwrap())
                            )
                        })
                        .collect::<Vec<_>>();
                    let actual = if signatures.is_empty() {
                        "~".to_owned()
                    } else {
                        signatures.join(";")
                    };
                    assert_eq!(actual, c[15], "trace {}", c[0]);
                }
                if values != expected {
                    failures.push(format!(
                        "{}/{memo:?}/{diag:?}\n expected {expected:?}\n actual   {values:?}",
                        c[0]
                    ));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}
#[test]
fn independent_classic_oracle() {
    verify_classic_oracle(false);
}
#[test]
fn independent_classic_trace_oracle() {
    verify_classic_oracle(true);
}

fn mutate_before_regex_budget(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    context.set_state("value", 99u32);
    context.scopes_mut().enter();
    context.scopes_mut().declare("during", 1);
    let id = *context
        .state::<unlaxer_runtime::token::NodeId>("existing")
        .unwrap();
    context.put_token_extra(id, "label", "during");
    context.parse(
        &Expr::regex_with_limits(
            "(a+)+$",
            unlaxer_runtime::regex::MatchLimits {
                max_steps: 64,
                ..Default::default()
            },
        )
        .unwrap()
        .capture("item"),
    )
}
#[test]
fn regex_resource_failure_is_sticky_and_rolls_back_owned_values_in_every_mode() {
    let input = format!("p{}!", "a".repeat(32));
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for mode in [
                None,
                Some(TokenKind::Consumed),
                Some(TokenKind::MatchOnly),
                Some(TokenKind::VirtualConsumed),
                Some(TokenKind::VirtualMatchOnly),
            ] {
                let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                let mut context = ParseContext::with_options(&input, options);
                let prefix = context
                    .parse_with_token_mode(&w("p"), TokenKind::Consumed, false)
                    .unwrap();
                let existing = context.token_node_id(prefix.nodes[0]).unwrap();
                context.set_state("existing", existing);
                context.set_state("value", 7u32);
                context.put_token_extra(existing, "label", "before");
                let parser = Expr::choice([Expr::Custom(mutate_before_regex_budget), Expr::Any]);
                let error = match mode {
                    None => context.parse(&parser),
                    Some(kind) => context.parse_with_token_mode(&parser, kind, false),
                }
                .unwrap_err();
                assert_eq!(error.offset, 1);
                assert!(
                    error.expected[0].contains("regex resource_limit: StepLimit"),
                    "{error:?}"
                );
                assert_eq!((context.position(), context.matched_position()), (1, 1));
                assert_eq!(*context.state::<u32>("value").unwrap(), 7);
                assert_eq!(context.scopes().current_scope_depth(), 0);
                assert!(context.scopes().all_declarations().is_empty());
                assert_eq!(
                    context.token_info(existing).unwrap().extra("label"),
                    Some("before")
                );
                assert!(context.captured("item").is_none());
                assert!(context.node(prefix.nodes[0] + 1).is_none());
                assert_eq!(context.parse(&Expr::Any).unwrap_err(), error);
                let mut fresh = ParseContext::new("aaa");
                assert!(fresh.parse(&Expr::regex("(a+)+$").unwrap()).is_ok());
            }
        }
    }
    let rules = [unlaxer_runtime::Rule {
        name: "Root",
        expression: Expr::choice([
            Expr::regex_with_limits(
                "(a+)+$",
                unlaxer_runtime::regex::MatchLimits {
                    max_steps: 64,
                    ..Default::default()
                },
            )
            .unwrap(),
            Expr::Any.repeat(0, None),
        ]),
    }];
    let diagnostic = unlaxer_runtime::parse_detailed(&rules, 0, false, &input[1..]).unwrap_err();
    assert_eq!(diagnostic.kind, "resource_limit");
}
#[test]
fn shared_compiled_regex_preserves_default_mode_capture_and_failure_memo_diagnostics() {
    use unlaxer_runtime::{share_grammar, Rule};
    let regex = Expr::regex("😀a+").unwrap();
    let cloned = regex.clone();
    match (&regex, &cloned) {
        (Expr::Regex { pattern: a, .. }, Expr::Regex { pattern: b, .. }) => {
            assert!(std::sync::Arc::ptr_eq(a, b))
        }
        _ => unreachable!(),
    }
    let grammar = share_grammar(vec![
        Rule {
            name: "Root",
            expression: Expr::choice([Expr::Rule(1), Expr::Rule(1)]),
        },
        Rule {
            name: "Item",
            expression: regex.capture("item").then(Expr::literal("!")),
        },
    ]);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut success = ParseContext::with_options("😀aa!", options);
            assert!(success.parse_shared_grammar(&grammar, 0, false).is_ok());
            assert_eq!(success.position(), 4);
            let tree = unlaxer_runtime::parse_detailed_shared_with_options(
                &grammar, 0, false, "😀aa!", options,
            )
            .unwrap();
            assert!(tree
                .nodes
                .iter()
                .flat_map(|node| &node.captures)
                .any(|capture| capture.name == "item"
                    && capture.span == unlaxer_runtime::Span { start: 0, end: 3 }));
            let mut failed = ParseContext::with_options("😀aa?", options);
            let first = failed.parse_shared_grammar(&grammar, 0, false).unwrap_err();
            assert_eq!(
                failed.parse_shared_grammar(&grammar, 0, false).unwrap_err(),
                first
            );
            assert_eq!((failed.position(), failed.matched_position()), (0, 0));
            assert!(failed.node(0).is_none());
            let full = unlaxer_runtime::parse_detailed_shared_with_options(
                &grammar, 0, false, "😀aa?", options,
            )
            .unwrap_err();
            assert_eq!(full.farthest.offset, 3);
            assert_eq!(full.farthest.expected, vec!["!"]);
        }
    }
}

#[test]
fn original_source_cache_is_reused_across_tokens_without_using_projected_sources() {
    let mut context = ParseContext::new("abc");
    context.set_reset_matched_with_consumed(false);
    context
        .parse_with_token_mode(&Expr::regex("a").unwrap(), TokenKind::MatchOnly, false)
        .unwrap();
    let projected = context
        .parse_with_token_mode(
            &Expr::sequence([w("b"), w("b")]),
            TokenKind::VirtualConsumed,
            false,
        )
        .unwrap();
    assert_eq!((context.position(), context.matched_position()), (2, 2));
    let id = context.token_node_id(projected.nodes[0]).unwrap();
    assert_eq!(context.token_text(id), Some("bb"));
    let result = context
        .parse_with_token_mode(&Expr::regex("c").unwrap(), TokenKind::Consumed, false)
        .unwrap();
    let id = context.token_node_id(result.nodes[0]).unwrap();
    assert_eq!(context.token_text(id), Some("c"));
    assert_eq!(result.span, unlaxer_runtime::Span { start: 2, end: 3 });
    assert_eq!((context.position(), context.matched_position()), (3, 3));
    // Every token receives a suffix of one cached scalar source, not a fresh
    // allocation/scanning of its entire remaining source on each repetition.
    let source = "😀".repeat(20_000);
    let mut large = ParseContext::new(&source);
    assert!(large
        .parse(&Expr::regex("😀").unwrap().repeat(20_000, Some(20_000)))
        .is_ok());
    assert_eq!(large.position(), 20_000);
}
#[test]
fn input_resource_limit_counts_the_entire_remaining_source_even_for_a_short_prefix() {
    let limits = unlaxer_runtime::regex::MatchLimits {
        max_input_codepoints: 2,
        ..Default::default()
    };
    let parser = Expr::regex_with_limits("a", limits).unwrap();
    let mut context = ParseContext::new("aaa");
    let error = context.parse(&parser).unwrap_err();
    assert!(error.expected[0].contains("InputLimit"));
    assert_eq!(context.position(), 0);
    assert_eq!(context.parse(&Expr::Any).unwrap_err(), error);
    let mut suffix = ParseContext::new("pa😀");
    suffix.parse(&w("p")).unwrap();
    suffix.parse(&parser).unwrap();
    assert_eq!(suffix.position(), 2);
    // As in the Java adapter, EOF rejects a token before running its matcher.
    let mut eof = ParseContext::new("");
    assert!(eof
        .parse(
            &Expr::regex_with_limits(
                "a*",
                unlaxer_runtime::regex::MatchLimits {
                    max_steps: 0,
                    max_input_codepoints: 0,
                    ..Default::default()
                }
            )
            .unwrap()
        )
        .is_err());
    assert!(eof.parse(&Expr::Empty).is_ok());
}

#[test]
fn regex_at_eof_reports_the_required_token_at_the_current_scalar_offset() {
    let rules = [unlaxer_runtime::Rule {
        name: "Root",
        expression: Expr::literal("😀").then(Expr::regex("[a-z]+").unwrap()),
    }];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let failure = unlaxer_runtime::parse_detailed_with_options(
                &rules,
                0,
                false,
                "😀",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            )
            .unwrap_err();
            assert_eq!(failure.offset, 1);
            assert_eq!(failure.farthest.offset, 1);
            assert_eq!(failure.farthest.expected, vec!["REGEX"]);
        }
    }
}

#[test]
fn optional_and_repeat_cannot_turn_regex_resource_failure_into_success() {
    let atom = Expr::regex_with_limits(
        "a|b",
        unlaxer_runtime::regex::MatchLimits {
            max_steps: 0,
            ..Default::default()
        },
    )
    .unwrap();
    for expression in [
        atom.clone().optional(),
        atom.clone().optional_java(),
        atom.clone().repeat(0, None),
        atom.repeat_java(0, None),
    ] {
        let mut context = ParseContext::new("a");
        let failure = context.parse(&expression).unwrap_err();
        assert!(failure.expected[0].contains("StepLimit"));
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert_eq!(context.parse(&Expr::Any).unwrap_err(), failure);
    }
    let mut virtual_kind = ParseContext::new("a😀x");
    virtual_kind
        .parse_with_token_mode(&w("a"), TokenKind::MatchOnly, false)
        .unwrap();
    let failure = virtual_kind
        .parse_with_token_mode(
            &Expr::regex_with_limits(
                "😀",
                unlaxer_runtime::regex::MatchLimits {
                    max_input_codepoints: 1,
                    ..Default::default()
                },
            )
            .unwrap(),
            TokenKind::VirtualConsumed,
            false,
        )
        .unwrap_err();
    assert_eq!(failure.offset, 1);
    assert!(failure.expected[0].contains("InputLimit"));
    assert_eq!(
        (virtual_kind.position(), virtual_kind.matched_position()),
        (0, 1)
    );
}
