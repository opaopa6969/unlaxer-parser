use unlaxer_runtime::{
    token::{TokenExpr, TokenKind},
    token_trace::{Error, Limits},
    *,
};
fn decode(s: &str) -> String {
    if s == "~" {
        String::new()
    } else {
        s.replace("\\\\", "\0")
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace('\0', "\\")
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
fn word(s: &'static str) -> Expr {
    Expr::Literal(s)
}
fn grammar(op: &str, input: &str) -> Vec<Rule> {
    let seq = || Expr::Sequence(vec![word("a"), word("b")]);
    let e = match op {
        "word" => word(Box::leak(input.to_owned().into_boxed_str())),
        "a" => word("a"),
        "sequence" => seq(),
        "choice" => Expr::Choice(vec![Expr::Sequence(vec![word("a"), word("x")]), word("ab")]),
        "longest" => Expr::LongestChoice(vec![word("a"), word("ab"), seq()]),
        "longest-tie" => Expr::LongestChoice(vec![seq(), word("ab"), word("a")]),
        "predictive" => Expr::PredictiveChoice {
            alternatives: vec![word("x"), word("ab")],
            predictors: vec![Predictor::Literal("x"), Predictor::Literal("ab")],
        },
        "predictive-fallback" => Expr::PredictiveChoice {
            alternatives: vec![word("x"), word("ab")],
            predictors: vec![Predictor::Literal("a"), Predictor::Literal("x")],
        },
        "lookahead" => Expr::Sequence(vec![
            Expr::JavaLookahead {
                pattern: "a",
                positive: true,
            },
            word("a"),
        ]),
        "lookahead-empty" => Expr::Sequence(vec![
            Expr::JavaLookahead {
                pattern: "ab",
                positive: true,
            },
            Expr::JavaOptional(Box::new(Expr::Sequence(vec![word("x")]))),
            word("ab"),
        ]),
        "negative-lookahead" => Expr::Sequence(vec![
            Expr::Lookahead {
                child: Box::new(word("b")),
                positive: false,
            },
            word("a"),
        ]),
        "eof" => Expr::Sequence(vec![word("a"), Expr::Eof]),
        "eof-empty" => Expr::Eof,
        "optional" => Expr::Sequence(vec![Expr::JavaOptional(Box::new(word("b"))), word("a")]),
        "repeat" => Expr::Sequence(vec![
            Expr::JavaRepeat {
                child: Box::new(word("b")),
                min: 0,
                max: None,
            },
            word("a"),
        ]),
        "number" => Expr::Number,
        "identifier" => Expr::Identifier,
        "quoted" => Expr::Quoted('\''),
        "double-quoted" => Expr::Quoted('"'),
        "memo" => Expr::Choice(vec![Expr::Rule(1), Expr::Rule(1), word("ab")]),
        _ => word("a"),
    };
    vec![
        Rule {
            name: "Root",
            expression: e,
        },
        Rule {
            name: "Miss",
            expression: Expr::Sequence(vec![word("a"), word("x")]),
        },
    ]
}
fn leaves(context: &ParseContext<'_>) -> String {
    let leaves = context.token_trace().unwrap().unwrap();
    if leaves.is_empty() {
        return "~".into();
    }
    leaves
        .iter()
        .map(|leaf| {
            let span = leaf.span();
            let raw = leaf.text(context.source()).unwrap();
            let text = if raw.is_empty() {
                "~".into()
            } else {
                raw.replace('\\', "\\\\")
                    .replace('\r', "\\r")
                    .replace('\n', "\\n")
            };
            format!(
                "{},{},{},{},{}",
                leaf.label,
                kind_name(leaf.kind),
                span.start,
                span.end,
                text
            )
        })
        .collect::<Vec<_>>()
        .join(";")
}
#[test]
fn independent_java_rust_terminal_oracle() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for repeat in 0..2 {
            for line in include_str!("../../../conformance/token-trace/leaves.tsv")
                .lines()
                .filter(|l| !l.starts_with('#'))
            {
                let c: Vec<_> = line.split('\t').collect();
                let input = decode(c[1]);
                let options =
                    ParseOptions::with_memoization(memo).with_token_trace(Limits::default());
                let mut context = ParseContext::with_options(&input, options);
                if c[2] == "prefix-virtual" {
                    context
                        .parse_token(
                            &TokenExpr::Word { rule: 0, text: "a" },
                            TokenKind::MatchOnly,
                            false,
                        )
                        .unwrap();
                }
                let result = if c[3] != "consumed"
                    || c[4] == "true"
                    || matches!(c[2], "consume" | "stop-invert" | "invert")
                {
                    let atom = TokenExpr::Word {
                        rule: 0,
                        text: if c[2] == "prefix-virtual" { "b" } else { "a" },
                    };
                    let atom = match c[2] {
                        "consume" => TokenExpr::StopConsume(Box::new(atom)),
                        "stop-invert" => TokenExpr::StopInvert(Box::new(atom)),
                        "invert" => TokenExpr::Invert(Box::new(atom)),
                        _ => atom,
                    };
                    context.parse_token(&atom, kind(c[3]), c[4] == "true")
                } else {
                    context.parse_grammar(grammar(c[2], &input), 0, false)
                };
                assert_eq!(result.is_ok(), c[5] == "true", "{}", c[0]);
                assert_eq!(
                    context.position(),
                    c[6].parse::<usize>().unwrap(),
                    "{}",
                    c[0]
                );
                assert_eq!(
                    context.matched_position(),
                    c[7].parse::<usize>().unwrap(),
                    "{}",
                    c[0]
                );
                // Compare raw text after fixture escaping, not a backend-generated expected tree.
                let actual = leaves(&context);
                let expected = c[8]
                    .split(';')
                    .map(|leaf| {
                        let mut fields = leaf.splitn(5, ',').map(str::to_owned).collect::<Vec<_>>();
                        if fields.len() == 5 {
                            let raw = decode(&fields[4]);
                            fields[4] = if raw.is_empty() {
                                "~".into()
                            } else {
                                raw.replace('\\', "\\\\")
                                    .replace('\r', "\\r")
                                    .replace('\n', "\\n")
                            };
                        }
                        fields.join(",")
                    })
                    .collect::<Vec<_>>()
                    .join(";");
                let cursors = context
                    .token_trace()
                    .unwrap()
                    .unwrap()
                    .iter()
                    .map(|leaf| format!("{}:{}", leaf.consumed, leaf.matched))
                    .collect::<Vec<_>>()
                    .join(";");
                assert_eq!(
                    if cursors.is_empty() { "~" } else { &cursors },
                    c[9],
                    "{} leaf cursors",
                    c[0]
                );
                if c[2] == "memo" {
                    assert_eq!(
                        context.memoized_failure_hits(),
                        usize::from(memo == Memoization::SafeFailures)
                    );
                }
                assert_eq!(actual, expected, "{} {memo:?} rerun {repeat}", c[0]);
                if repeat == 0 {
                    println!(
                        "TRACE\trust\t{}\t{memo:?}\t{}\t{}\t{}\t{}",
                        c[0],
                        result.is_ok(),
                        context.position(),
                        context.matched_position(),
                        actual
                    );
                }
            }
        }
    }
}
#[test]
fn trace_is_opt_in_bounded_transactional_owned_and_cst_neutral() {
    let rules = grammar("longest", "ab");
    let normal = parse(&rules, 0, false, "ab").unwrap();
    assert!(normal.token_trace().is_none());
    let traced = parse_with_options(
        &rules,
        0,
        false,
        "ab",
        ParseOptions::default().with_token_trace(Limits::default()),
    )
    .unwrap();
    assert_eq!(normal.nodes.len(), traced.nodes.len());
    assert_eq!(format!("{normal:?}"), format!("{traced:?}"));
    assert_eq!(format!("{:?}", normal.nodes), format!("{:?}", traced.nodes));
    assert_eq!(traced.token_trace().unwrap().unwrap().len(), 1);
    let mut context = ParseContext::with_options(
        "ab",
        ParseOptions::default().with_token_trace(Limits {
            leaves: 1,
            source_bytes: 2,
        }),
    );
    let r = context
        .parse_grammar(
            vec![Rule {
                name: "Root",
                expression: Expr::Choice(vec![
                    Expr::Sequence(vec![word("a"), word("b"), word("x")]),
                    word("ab"),
                ]),
            }],
            0,
            false,
        )
        .unwrap();
    let tree = context.tree(r.root_node().unwrap()).unwrap();
    assert_eq!(tree.token_trace().unwrap().unwrap().len(), 1);
    assert!(context
        .transaction::<()>(|c| {
            c.parse(&Expr::Eof)?;
            Err(c.error("rollback"))
        })
        .is_err());
    assert_eq!(context.token_trace().unwrap().unwrap().len(), 1);
    context.parse(&Expr::Eof).unwrap();
    assert_eq!(context.token_trace().unwrap(), Err(Error::Limit));
    assert_eq!(tree.token_trace().unwrap().unwrap().len(), 1);
    let mut too_small = ParseContext::with_options(
        "😀",
        ParseOptions::default().with_token_trace(Limits {
            leaves: 9,
            source_bytes: 3,
        }),
    );
    assert!(too_small.parse(&word("😀")).is_ok());
    assert_eq!(too_small.token_trace().unwrap(), Err(Error::Limit));
    let mut peek = ParseContext::with_options(
        "ab",
        ParseOptions::default().with_token_trace(Limits::default()),
    );
    peek.parse(&Expr::Lookahead {
        child: Box::new(word("a")),
        positive: true,
    })
    .unwrap();
    assert!(peek.token_trace().unwrap().unwrap().is_empty());
}

#[test]
fn longest_choice_and_lookahead_keep_only_the_selected_trace_budget() {
    for (first_split, limited) in [(false, false), (true, true)] {
        let split = Expr::Sequence(vec![word("a"), word("b")]);
        let whole = word("ab");
        let alternatives = if first_split {
            vec![split, whole]
        } else {
            vec![whole, split]
        };
        let mut context = ParseContext::with_options(
            "ab",
            ParseOptions::default().with_token_trace(Limits {
                leaves: 1,
                source_bytes: 2,
            }),
        );
        context.parse(&Expr::LongestChoice(alternatives)).unwrap();
        assert_eq!(context.token_trace().unwrap().is_err(), limited);
        if limited {
            assert_eq!(context.token_trace().unwrap(), Err(Error::Limit));
        } else {
            assert_eq!(
                context.token_trace().unwrap().unwrap()[0].text("ab"),
                Some("ab")
            );
        }
    }
    let mut context = ParseContext::with_options(
        "ab",
        ParseOptions::default().with_token_trace(Limits {
            leaves: 1,
            source_bytes: 2,
        }),
    );
    context
        .parse(&Expr::Sequence(vec![word("a"), word("b")]).ahead())
        .unwrap();
    context.parse(&word("ab")).unwrap();
    assert_eq!(context.token_trace().unwrap().unwrap().len(), 1);
}

#[test]
fn explicit_custom_contract_and_generated_sources_never_hide_missing_trace() {
    fn partial(context: &mut ParseContext<'_>) -> ParseResult {
        let matched = context.parse(&Expr::Literal("a"))?;
        assert!(context.advance(1));
        Ok(matched)
    }
    fn complete(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        context.parse(&Expr::Sequence(vec![word("a"), word("b")]))
    }
    fn outer(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        context.parse(&Expr::Custom(partial))
    }
    for parser in [partial as fn(&mut ParseContext<'_>) -> ParseResult, outer] {
        let mut context = ParseContext::with_options(
            "ab",
            ParseOptions::default().with_token_trace(Limits::default()),
        );
        assert!(context.parse(&Expr::Custom(parser)).is_ok());
        assert!(matches!(
            context.token_trace().unwrap(),
            Err(Error::Unsupported(_))
        ));
    }
    let mut context = ParseContext::with_options(
        "ab",
        ParseOptions::default().with_token_trace(Limits::default()),
    );
    assert!(context.parse(&Expr::Custom(complete)).is_ok());
    assert_eq!(context.token_trace().unwrap().unwrap().len(), 2);
    let retained = context.token_trace().unwrap().unwrap().to_vec();
    let root = context
        .add_token_node(
            0,
            TokenKind::VirtualConsumed,
            unlaxer_runtime::token::TokenSource::Generated {
                anchor: 2,
                text: "😀repair".into(),
            },
        )
        .unwrap();
    let tree = context.tree(root.index()).unwrap();
    let generated = &tree.token_trace().unwrap().unwrap()[2];
    assert_eq!(
        generated.source,
        unlaxer_runtime::token::TokenSource::Generated {
            anchor: 2,
            text: "😀repair".into()
        }
    );
    assert_eq!(generated.kind, TokenKind::VirtualConsumed);
    assert_eq!(generated.span(), Span { start: 2, end: 2 });
    assert_eq!(generated.text("ab"), Some("😀repair"));
    assert_eq!((generated.consumed, generated.matched), (2, 2));
    assert_eq!(retained.len(), 2);
    fn send_sync<T: Send + Sync>() {}
    send_sync::<Tree>();
}

#[test]
fn generated_sources_share_java_oracle() {
    use unlaxer_runtime::token::TokenSource;
    for line in include_str!("../../../conformance/token-trace/generated.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let c: Vec<_> = line.split('\t').collect();
        let input = decode(c[0]);
        let text = decode(c[2]);
        let anchor = c[1].parse().unwrap();
        let mut context = ParseContext::with_options(
            &input,
            ParseOptions::default().with_token_trace(Limits::default()),
        );
        context
            .parse(&Expr::Literal(Box::leak(input.clone().into_boxed_str())))
            .unwrap();
        context
            .add_token_node(
                0,
                kind(c[3]),
                TokenSource::Generated {
                    anchor,
                    text: text.clone(),
                },
            )
            .unwrap();
        let trace = context.token_trace().unwrap().unwrap();
        let leaf = trace.last().unwrap();
        assert_eq!(
            leaf.span(),
            Span {
                start: anchor,
                end: anchor
            }
        );
        assert_eq!(leaf.text(&input), Some(text.as_str()));
        assert_eq!(leaf.source, TokenSource::Generated { anchor, text });
        assert_eq!(leaf.kind, kind(c[3]));
        assert_eq!(leaf.consumed, c[4].parse::<usize>().unwrap());
        assert_eq!(leaf.matched, c[5].parse::<usize>().unwrap());
    }
}

#[test]
fn trace_and_semantic_payloads_share_choice_and_lookahead_transactions() {
    fn record(context: &mut ParseContext<'_>, text: &'static str, value: u32) -> ParseResult {
        context.declare_complete_token_trace();
        let matched = context.parse(&Expr::Literal(text).capture("value"))?;
        context.set_state("count", value);
        context.scopes_mut().declare("chosen", 0);
        context.scopes_mut().add_reference("chosen", 0, text.len());
        context
            .scopes_mut()
            .add_diagnostic(text, 0, text.len(), Severity::Info);
        Ok(matched)
    }
    fn a(context: &mut ParseContext<'_>) -> ParseResult {
        record(context, "a", 1)
    }
    fn ab(context: &mut ParseContext<'_>) -> ParseResult {
        record(context, "ab", 2)
    }
    let cases = [
        (
            "failed choice",
            Expr::Custom(a).then(word("x")).or(word("ab")),
            false,
        ),
        (
            "longest winner",
            Expr::LongestChoice(vec![Expr::Custom(a), Expr::Custom(ab)]),
            true,
        ),
        (
            "longest loser",
            Expr::LongestChoice(vec![Expr::Custom(a), word("ab")]),
            false,
        ),
        (
            "lookahead",
            Expr::Custom(ab).ahead().then(word("ab")),
            false,
        ),
    ];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for (name, expression, committed) in &cases {
            let mut normal = None;
            for trace in [false, true] {
                let mut options = ParseOptions::with_memoization(memo);
                if trace {
                    options = options.with_token_trace(Limits::default());
                }
                let mut context = ParseContext::with_options("ab", options);
                context.set_state("count", 7u32);
                context.scopes_mut().declare("before", 0);
                context.scopes_mut().add_reference("before", 0, 1);
                context
                    .scopes_mut()
                    .add_diagnostic("before", 0, 1, Severity::Warning);
                let result = context
                    .parse_grammar(
                        vec![Rule {
                            name: "Root",
                            expression: expression.clone(),
                        }],
                        0,
                        false,
                    )
                    .unwrap();
                assert_eq!(
                    (context.position(), context.matched_position()),
                    (2, 2),
                    "{name}"
                );
                assert_eq!(
                    context.state::<u32>("count"),
                    Some(if *committed { &2 } else { &7 }),
                    "{name}"
                );
                assert_eq!(
                    context.captured("value"),
                    if *committed { Some("ab") } else { None },
                    "{name}"
                );
                assert_eq!(
                    context.scopes().all_declarations().len(),
                    1 + usize::from(*committed),
                    "{name}"
                );
                assert_eq!(
                    context.scopes().all_references().len(),
                    1 + usize::from(*committed),
                    "{name}"
                );
                assert_eq!(
                    context.scopes().diagnostics().len(),
                    1 + usize::from(*committed),
                    "{name}"
                );
                let tree = context.tree(result.root_node().unwrap()).unwrap();
                let snapshot = (
                    format!("{tree:?}"),
                    context.scopes().clone(),
                    context.failure(),
                );
                if trace {
                    assert_eq!(normal.as_ref(), Some(&snapshot), "{name}");
                    let leaves = context.token_trace().unwrap().unwrap();
                    assert_eq!(leaves.len(), 1, "{name}");
                    assert_eq!(leaves[0].text("ab"), Some("ab"));
                    assert_eq!(leaves[0].span(), Span { start: 0, end: 2 });
                } else {
                    assert!(tree.token_trace().is_none());
                    normal = Some(snapshot);
                }
            }
        }
    }
}

fn budget_parser(op: &str) -> Expr {
    let tail = || Expr::Sequence(vec![word("|"), word("x")]).match_only();
    match op {
        "matchonly-tail" => Expr::Sequence(vec![word("a"), tail()]),
        "optional-matchonly" => Expr::Sequence(vec![word("a"), tail().optional_java()]),
        "stopconsume-tail" => Expr::Sequence(vec![
            word("a"),
            Expr::Sequence(vec![word("|"), word("x")]).stop_consume(),
        ]),
        "matchonly-empty" => {
            Expr::Sequence(vec![word("a"), word("b").optional_java().match_only()])
        }
        "zero-width" => Expr::Sequence(vec![word("b").optional_java(), word("a"), tail()]),
        "choice-loser" => Expr::Choice(vec![
            Expr::Sequence(vec![word("a"), word("y").match_only()]),
            Expr::Sequence(vec![word("a"), tail()]),
        ]),
        "not-tail" => Expr::Sequence(vec![
            Expr::JavaLookahead {
                pattern: "b",
                positive: false,
            },
            word("a"),
            tail(),
        ]),
        "lookahead-tail" => Expr::Sequence(vec![word("a").match_only(), word("a"), tail()]),
        "outer-failure" => Expr::Sequence(vec![Expr::Sequence(vec![word("a"), tail()]), word("z")]),
        "unicode-crlf" => Expr::Sequence(vec![
            word("😀"),
            Expr::Sequence(vec![word("\r\n"), word("x")]).match_only(),
        ]),
        "interleave" => Expr::Sequence(vec![
            word("a"),
            word("|").match_only(),
            word("|"),
            word("x").match_only(),
            word("x"),
        ]),
        "number-tail" => Expr::Sequence(vec![Expr::Number, word("|").match_only()]),
        "invert-stop" => Expr::Sequence(vec![word("a"), tail()]).stop_invert(),
        "invert-matchonly" => Expr::Sequence(vec![word("a"), word("|").match_only()]),
        "matchonly-root" => Expr::Sequence(vec![word("a"), word("|")]).match_only(),
        "closed-nesting" => Expr::Sequence(vec![
            word("a"),
            Expr::Sequence(vec![word("|"), word("x")])
                .stop_consume()
                .match_only(),
            word("|"),
        ]),
        _ => panic!("{op}"),
    }
}

fn format_leaves(leaves: &[token_trace::Leaf], source: &str) -> String {
    if leaves.is_empty() {
        return "~".into();
    }
    leaves
        .iter()
        .map(|leaf| {
            let span = leaf.span();
            let raw = leaf.text(source).unwrap();
            let text = if raw.is_empty() {
                "~".into()
            } else {
                raw.replace('\\', "\\\\")
                    .replace('\r', "\\r")
                    .replace('\n', "\\n")
            };
            format!(
                "{},{},{},{},{}",
                leaf.label,
                kind_name(leaf.kind),
                span.start,
                span.end,
                text
            )
        })
        .collect::<Vec<_>>()
        .join(";")
}
fn format_cursors(leaves: &[token_trace::Leaf]) -> String {
    if leaves.is_empty() {
        return "~".into();
    }
    leaves
        .iter()
        .map(|leaf| format!("{}:{}", leaf.consumed, leaf.matched))
        .collect::<Vec<_>>()
        .join(";")
}
/// Everything a trace budget must leave unchanged, plus the trace itself.
struct Run {
    accepted: bool,
    consumed: usize,
    matched: usize,
    /// Result, CST, captures and state.
    syntax: String,
    trace: Result<Vec<token_trace::Leaf>, Error>,
    leaves: String,
    cursors: String,
    bytes: usize,
}
fn observe(context: &ParseContext<'_>, result: &ParseResult) -> Run {
    let tree = result
        .as_ref()
        .ok()
        .and_then(ParseMatch::root_node)
        .and_then(|root| context.tree(root))
        .map(|tree| format!("{tree:?}"));
    let trace = context
        .token_trace()
        .expect("explicitly enabled")
        .map(<[_]>::to_vec);
    let (leaves, cursors, bytes) = match &trace {
        Ok(leaves) => (
            format_leaves(leaves, context.source()),
            format_cursors(leaves),
            leaves
                .iter()
                .map(|leaf| leaf.text(context.source()).unwrap().len())
                .sum(),
        ),
        Err(_) => (String::new(), String::new(), 0),
    };
    Run {
        accepted: result.is_ok(),
        consumed: context.position(),
        matched: context.matched_position(),
        syntax: format!(
            "{result:?} {tree:?} {:?} {:?}",
            context.captured("head"),
            context.state::<u32>("seen")
        ),
        trace,
        leaves,
        cursors,
        bytes,
    }
}
/// n-1/n/n+1 for both retained leaves and their source UTF-8 bytes, around the
/// trace an ample budget retains. Only a really exceeded retained trace is a Limit,
/// and no budget changes acceptance, either cursor, CST, captures or state.
fn assert_budget_boundaries(name: &str, run: &dyn Fn(Limits) -> Run) -> Run {
    let ample = run(Limits::default());
    let leaves = ample
        .trace
        .clone()
        .unwrap_or_else(|error| panic!("{name}: {error}"));
    let (n, b) = (leaves.len(), ample.bytes);
    let mut cases = vec![(n, b, true), (n + 1, b + 1, true)];
    if n > 0 {
        cases.push((n - 1, b, false));
    }
    if b > 0 {
        cases.push((n, b - 1, false));
    }
    for (limit, bytes, fits) in cases {
        let limited = run(Limits {
            leaves: limit,
            source_bytes: bytes,
        });
        let at = format!("{name} leaves={limit} bytes={bytes}");
        assert_eq!(limited.accepted, ample.accepted, "{at}");
        assert_eq!(
            (limited.consumed, limited.matched),
            (ample.consumed, ample.matched),
            "{at}"
        );
        assert_eq!(limited.syntax, ample.syntax, "{at}");
        let expected = if fits {
            Ok(leaves.clone())
        } else {
            Err(Error::Limit)
        };
        assert_eq!(limited.trace, expected, "{at}");
    }
    ample
}
fn expected_leaves(field: &str) -> String {
    field
        .split(';')
        .map(|leaf| {
            let mut fields = leaf.splitn(5, ',').map(str::to_owned).collect::<Vec<_>>();
            if fields.len() == 5 {
                let raw = decode(&fields[4]);
                fields[4] = if raw.is_empty() {
                    "~".into()
                } else {
                    raw.replace('\\', "\\\\")
                        .replace('\r', "\\r")
                        .replace('\n', "\\n")
                };
            }
            fields.join(",")
        })
        .collect::<Vec<_>>()
        .join(";")
}

#[test]
fn classic_collector_budget_counts_only_retained_leaves_of_java_oracle() {
    let rows = include_str!("../../../conformance/token-trace/budget.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
        .collect::<Vec<_>>();
    assert_eq!(rows.len(), 23);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for line in &rows {
            let c: Vec<_> = line.split('\t').collect();
            let input = decode(c[1]);
            let expression = budget_parser(c[2]);
            let (kind, invert, reset) = (kind(c[3]), c[4] == "true", c[5] == "true");
            let options = |limits| ParseOptions::with_memoization(memo).with_token_trace(limits);
            let direct = |limits| {
                let mut context = ParseContext::with_options(&input, options(limits));
                context.set_reset_matched_with_consumed(reset);
                let result = context.parse_with_token_mode(&expression, kind, invert);
                observe(&context, &result)
            };
            let ample = assert_budget_boundaries(c[0], &direct);
            assert_eq!(ample.accepted, c[6] == "true", "{}", c[0]);
            assert_eq!(ample.consumed, c[7].parse::<usize>().unwrap(), "{}", c[0]);
            assert_eq!(ample.matched, c[8].parse::<usize>().unwrap(), "{}", c[0]);
            assert_eq!(ample.leaves, expected_leaves(c[9]), "{} {memo:?}", c[0]);
            assert_eq!(ample.cursors, c[10], "{} leaf cursors", c[0]);
            // Generated parsers enter through rules and wrap AST fields in value boundaries.
            if kind == TokenKind::Consumed && !invert {
                let wrapped = [
                    Expr::Rule(1),
                    Expr::Rule(1).text_value(),
                    Expr::Rule(1).value_boundary(),
                ];
                for (index, root) in wrapped.into_iter().enumerate() {
                    let grammar = |limits| {
                        let mut context = ParseContext::with_options(&input, options(limits));
                        context.set_reset_matched_with_consumed(reset);
                        let result = context.parse_grammar(
                            vec![
                                Rule {
                                    name: "Root",
                                    expression: root.clone(),
                                },
                                Rule {
                                    name: "Body",
                                    expression: expression.clone(),
                                },
                            ],
                            0,
                            false,
                        );
                        observe(&context, &result)
                    };
                    let rule =
                        assert_budget_boundaries(&format!("{} rule {index}", c[0]), &grammar);
                    assert_eq!(rule.accepted, ample.accepted, "{} rule {index}", c[0]);
                    // A rule is a consumed collector. A root keeping other kinds is left to
                    // the existing Rule boundary (a Java consumed Chain keeps a zero-width leaf).
                    let leaves = ample.trace.as_ref().unwrap();
                    if leaves.iter().all(|leaf| leaf.kind == TokenKind::Consumed) {
                        assert_eq!(rule.leaves, ample.leaves, "{} rule {index}", c[0]);
                    }
                }
            }
            if memo == Memoization::Off {
                println!(
                    "BUDGET\trust\t{}\t{}\t{}\t{}\t{}\t{}\tleaves={}\tbytes={}",
                    c[0],
                    ample.accepted,
                    ample.consumed,
                    ample.matched,
                    ample.leaves,
                    ample.cursors,
                    ample.trace.as_ref().map_or(0, Vec::len),
                    ample.bytes
                );
            }
        }
    }
}

#[test]
fn custom_callbacks_generated_text_and_reuse_keep_exact_budget_boundaries() {
    fn undeclared_rollback(context: &mut ParseContext<'_>) -> ParseResult {
        Ok(ParseMatch::empty(Span {
            start: context.position(),
            end: context.position(),
        }))
    }
    fn hidden_with_rolled_back_unsupported(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        let mut matched = context.parse_with_token_mode(&word("|"), TokenKind::MatchOnly, false)?;
        // This candidate's Unsupported is discarded. It must not poison an earlier
        // Limit belonging only to the value node the enclosing collector removes.
        let fallback = context.parse(&Expr::Choice(vec![
            Expr::Sequence(vec![
                Expr::Custom(undeclared_rollback),
                Expr::Error("discard candidate"),
            ]),
            Expr::Sequence(vec![]),
        ]))?;
        matched.nodes.extend(fallback.nodes);
        Ok(matched)
    }
    fn foreign(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        context.parse_with_token_mode(&word("|"), TokenKind::MatchOnly, false)
    }
    // An AST value node takes a custom result's first node kind without filtering,
    // so this match-only leaf survives a consumed collector. Outer collectors must
    // not decide such a callback's leaves.
    fn mixed(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        let first = context.parse_with_token_mode(&word("|"), TokenKind::Consumed, false)?;
        let second = context.parse_with_token_mode(&word("x"), TokenKind::MatchOnly, false)?;
        Ok(ParseMatch {
            span: first.span,
            nodes: first.nodes.into_iter().chain(second.nodes).collect(),
            captures: vec![],
        })
    }
    fn head(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        context.set_state("seen", 1u32);
        context.parse(&word("a").capture("head"))
    }
    fn inner(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        context.parse(&Expr::Sequence(vec![
            word("a"),
            Expr::Sequence(vec![word("|"), word("x")]).match_only(),
        ]))
    }
    fn here(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        context.parse(&word("|"))
    }
    fn generated(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        let id = context
            .add_token_node(
                0,
                TokenKind::VirtualMatchOnly,
                unlaxer_runtime::token::TokenSource::Generated {
                    anchor: 1,
                    text: "😀".into(),
                },
            )
            .unwrap();
        Ok(ParseMatch {
            span: Span { start: 1, end: 1 },
            nodes: vec![id.index()],
            captures: vec![],
        })
    }
    let tail = || Expr::Sequence(vec![word("|"), word("x")]).match_only();
    let exact = [
        (
            "discarded Unsupported cannot poison a removed value node",
            Expr::Sequence(vec![
                word("a"),
                Expr::Custom(hidden_with_rolled_back_unsupported).text_value(),
            ]),
            "a|",
            "WordParser,consumed,0,1,a",
        ),
        (
            "custom mixed result under value node",
            Expr::Sequence(vec![word("a"), Expr::Custom(mixed).text_value()]),
            "a|x",
            "WordParser,consumed,0,1,a;WordParser,consumed,1,2,|;WordParser,matchOnly,2,3,x",
        ),
        (
            "collector removes a counted custom leaf",
            Expr::Sequence(vec![
                Expr::Sequence(vec![word("a"), Expr::Custom(foreign).text_value()]),
                word("|"),
            ]),
            "a|",
            "WordParser,consumed,0,1,a;WordParser,consumed,1,2,|",
        ),
        (
            // The callback's leaf is counted until the value node takes its match-only
            // kind, which the consumed collector removes; a Limit it raised is undone.
            "value node of a removed kind",
            Expr::Sequence(vec![word("a"), Expr::Custom(foreign).text_value()]),
            "a|",
            "WordParser,consumed,0,1,a",
        ),
        (
            // Removed when the value node completes, before the following leaf.
            "leaf after a removed value node",
            Expr::Sequence(vec![
                word("a"),
                Expr::Custom(foreign).text_value(),
                word("|"),
            ]),
            "a|",
            "WordParser,consumed,0,1,a;WordParser,consumed,1,2,|",
        ),
        (
            "generated text below a removed value node",
            Expr::Sequence(vec![word("a"), Expr::Custom(generated).value_boundary()]),
            "a",
            "WordParser,consumed,0,1,a",
        ),
        (
            // Below collectors of different kinds nothing survives: never counted.
            "custom below collectors of different kinds",
            Expr::Sequence(vec![
                word("a"),
                Expr::Sequence(vec![Expr::Custom(here).text_value()]).match_only(),
            ]),
            "a|",
            "WordParser,consumed,0,1,a",
        ),
        (
            "custom result to collector",
            Expr::Sequence(vec![word("a"), Expr::Custom(foreign)]),
            "a|",
            "WordParser,consumed,0,1,a",
        ),
        (
            "custom in lookahead",
            Expr::Sequence(vec![word("a"), Expr::Custom(inner).match_only(), word("a")]),
            "aa|x",
            "WordParser,consumed,0,1,a;WordParser,consumed,1,2,a",
        ),
        (
            "custom capture and state",
            Expr::Sequence(vec![Expr::Custom(head), tail()]),
            "a|x",
            "WordParser,consumed,0,1,a",
        ),
        (
            "collector inside custom",
            Expr::Custom(inner),
            "a|x",
            "WordParser,consumed,0,1,a",
        ),
    ];
    let run = |expression: &Expr, input: &str, limits| {
        let mut context =
            ParseContext::with_options(input, ParseOptions::default().with_token_trace(limits));
        let result = context.parse_with_token_mode(expression, TokenKind::Consumed, false);
        observe(&context, &result)
    };
    for (name, expression, input, leaves) in &exact {
        let ample = assert_budget_boundaries(name, &|limits| run(expression, input, limits));
        assert!(ample.accepted, "{name}");
        assert_eq!(ample.leaves, *leaves, "{name}");
    }
    // Capture and state do not depend on the budget either.
    let state = &exact
        .iter()
        .find(|case| case.0 == "custom capture and state")
        .unwrap()
        .1;
    assert_eq!(
        run(state, "a|x", Limits::default()).syntax,
        run(
            state,
            "a|x",
            Limits {
                leaves: 0,
                source_bytes: 0
            }
        )
        .syntax
    );
    // An Unsupported the Limit masked keeps that Limit: removing the counted leaves
    // must not turn an unsupported callback into a valid trace.
    fn undeclared(context: &mut ParseContext<'_>) -> ParseResult {
        context.parse(&Expr::Sequence(vec![]))
    }
    fn masks(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        let first = context.parse_with_token_mode(&word("|"), TokenKind::MatchOnly, false)?;
        context.parse(&Expr::Custom(undeclared))?;
        Ok(first)
    }
    let masked = Expr::Sequence(vec![word("a"), Expr::Custom(masks).text_value()]);
    let ample = run(&masked, "a|", Limits::default());
    assert!(ample.accepted);
    assert!(matches!(ample.trace, Err(Error::Unsupported(_))));
    let limited = run(
        &masked,
        "a|",
        Limits {
            leaves: 1,
            source_bytes: 1 << 20,
        },
    );
    assert_eq!(limited.syntax, ample.syntax);
    assert_eq!(limited.trace, Err(Error::Limit));
    // Below collectors of different kinds the callback's leaves are never counted,
    // so no Limit masks its Unsupported at any budget.
    let never_counted = Expr::Sequence(vec![
        word("a"),
        Expr::Sequence(vec![Expr::Custom(masks).text_value()]).match_only(),
    ]);
    let ample = run(&never_counted, "a|", Limits::default());
    assert!(ample.accepted);
    assert!(matches!(ample.trace, Err(Error::Unsupported(_))));
    let limited = run(
        &never_counted,
        "a|",
        Limits {
            leaves: 1,
            source_bytes: 1,
        },
    );
    assert_eq!(limited.syntax, ample.syntax);
    assert_eq!(limited.trace, ample.trace);

    // Rust-only conservative boundary: a callback can recognize terminals it does not
    // return. Their leaves are counted until a collector removes them, so the budget
    // can report Limit although the retained trace fits; never a partial trace.
    fn unreturned(context: &mut ParseContext<'_>) -> ParseResult {
        assert!(context.declare_complete_token_trace());
        context.parse(&word("b"))?;
        context.parse(&word("c"))
    }
    let conservative = Expr::Sequence(vec![word("a"), Expr::Custom(unreturned)]);
    let ample = run(&conservative, "abc", Limits::default());
    assert_eq!(
        ample.leaves,
        "WordParser,consumed,0,1,a;WordParser,consumed,2,3,c"
    );
    for (limit, fits) in [(2, false), (3, true)] {
        let limited = run(
            &conservative,
            "abc",
            Limits {
                leaves: limit,
                source_bytes: 1 << 20,
            },
        );
        assert_eq!(limited.syntax, ample.syntax);
        assert_eq!(
            limited.trace,
            if fits {
                ample.trace.clone()
            } else {
                Err(Error::Limit)
            }
        );
    }

    // The collector that removes such a leaf returns its count and bytes before the
    // following leaf: only the peak inside the callback is conservative.
    let after = Expr::Sequence(vec![
        Expr::Sequence(vec![word("a"), Expr::Custom(unreturned)]),
        word("d"),
    ]);
    let ample = run(&after, "abcd", Limits::default());
    assert_eq!(
        ample.leaves,
        "WordParser,consumed,0,1,a;WordParser,consumed,2,3,c;WordParser,consumed,3,4,d"
    );
    for (bytes, fits) in [(2, false), (3, true)] {
        let limited = run(
            &after,
            "abcd",
            Limits {
                leaves: 1 << 10,
                source_bytes: bytes,
            },
        );
        assert_eq!(limited.syntax, ample.syntax);
        assert_eq!(
            limited.trace,
            if fits {
                ample.trace.clone()
            } else {
                Err(Error::Limit)
            }
        );
    }

    // Generated text is checked against the byte budget before it is copied.
    let generated = |limits| {
        let mut context =
            ParseContext::with_options("a", ParseOptions::default().with_token_trace(limits));
        let result = context.parse(&word("a"));
        let token = context.add_token_node(
            0,
            TokenKind::VirtualConsumed,
            unlaxer_runtime::token::TokenSource::Generated {
                anchor: 1,
                text: "😀repair".into(),
            },
        );
        let mut observed = observe(&context, &result);
        observed.syntax = format!("{} {token:?}", observed.syntax);
        observed.bytes = observed.trace.as_ref().map_or(0, |leaves| {
            leaves
                .iter()
                .map(|leaf| leaf.text("a").unwrap().len())
                .sum()
        });
        observed
    };
    let ample = assert_budget_boundaries("generated", &generated);
    assert_eq!((ample.trace.unwrap().len(), ample.bytes), (2, 11));

    // One context reused after an outer failure and a rolled-back over-budget candidate.
    let mut context = ParseContext::with_options(
        "aa|x",
        ParseOptions::default().with_token_trace(Limits {
            leaves: 2,
            source_bytes: 2,
        }),
    );
    context
        .parse_with_token_mode(&word("a"), TokenKind::Consumed, false)
        .unwrap();
    let failed = Expr::Sequence(vec![word("a"), tail(), word("z")]);
    assert!(context
        .parse_with_token_mode(&failed, TokenKind::Consumed, false)
        .is_err());
    assert_eq!(context.position(), 1);
    let rolled_back = Expr::Choice(vec![
        Expr::Sequence(vec![word("a"), word("|"), word("x"), word("y")]),
        Expr::Sequence(vec![word("a"), tail()]),
    ]);
    context
        .parse_with_token_mode(&rolled_back, TokenKind::Consumed, false)
        .unwrap();
    assert_eq!(
        format_leaves(context.token_trace().unwrap().unwrap(), context.source()),
        "WordParser,consumed,0,1,a;WordParser,consumed,1,2,a"
    );
    assert_eq!((context.position(), context.matched_position()), (2, 4));
    context
        .parse_with_token_mode(&word("|"), TokenKind::Consumed, false)
        .unwrap();
    assert_eq!(context.token_trace().unwrap(), Err(Error::Limit));
}
