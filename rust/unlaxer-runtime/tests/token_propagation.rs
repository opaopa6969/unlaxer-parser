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
fn generated(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    context.declare_complete_token_trace();
    let id = context
        .add_token_node(
            unlaxer_runtime::TOKEN_LEAF_RULE,
            context.token_mode().kind,
            TokenSource::Generated {
                anchor: 0,
                text: "x".into(),
            },
        )
        .unwrap();
    Ok(unlaxer_runtime::ParseMatch {
        span: unlaxer_runtime::Span { start: 0, end: 0 },
        nodes: vec![id.index()],
        captures: vec![],
    })
}
fn forced_generated(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    context.declare_complete_token_trace();
    let id = context
        .add_token_node(
            unlaxer_runtime::TOKEN_LEAF_RULE,
            TokenKind::VirtualMatchOnly,
            TokenSource::Generated {
                anchor: 0,
                text: "x".into(),
            },
        )
        .unwrap();
    Ok(unlaxer_runtime::ParseMatch {
        span: unlaxer_runtime::Span { start: 0, end: 0 },
        nodes: vec![id.index()],
        captures: vec![],
    })
}
fn parser(s: &str) -> Expr {
    match s {
        "generated" => Expr::sequence([Expr::Custom(generated)]),
        "match-generated" => Expr::Custom(forced_generated).match_only(),
        "lookahead" => Expr::JavaLookahead {
            pattern: "a",
            positive: true,
        },
        "negative" => Expr::JavaLookahead {
            pattern: "a",
            positive: false,
        },
        "until" => Expr::JavaUntil(";"),
        "longest" => Expr::longest_choice([w("a"), w("ab")]),
        "unique-longest" => Expr::unique_longest_choice([w("a"), w("ab")]),
        "unique-tie" => Expr::unique_longest_choice([w("a"), w("a")]),
        "any" => Expr::Any,
        "grapheme" => Expr::Grapheme,
        "unicode-ci" => Expr::CaseInsensitive("k"),
        "range" => Expr::CharRange('a', 'c'),
        "eof" => Expr::Eof,
        "java-empty" => Expr::JavaEmpty,
        "word-a" => w("a"),
        "word-b" => w("b"),
        "word-x" => w("x"),
        "word-ab" => w("ab"),
        "empty-word" => w(""),
        "match-word" => w("a").match_only(),
        "match-b" => w("b").match_only(),
        "match-emoji" => w("😀").match_only(),
        "match-crlf" => w("a\r\n😀").match_only(),
        "consume-word" => w("a").stop_consume(),
        "stop-invert-word" => w("a").stop_invert(),
        "not-word" => w("a").invert_match(),
        "double-not" => w("a").invert_match().invert_match(),
        "not-stop-not" => w("a").invert_match().stop_invert().invert_match(),
        "match-chain" => Expr::sequence([w("a"), w("b")]).match_only(),
        "chain-match" => Expr::sequence([w("a").match_only(), w("a")]),
        "match-consume" => w("a").stop_consume().match_only(),
        "nested-consume" => Expr::sequence([w("a"), w("a").stop_consume(), w("b")]).match_only(),
        "after-match" => Expr::sequence([w("a"), w("b").match_only()]),
        "chain-bb" => Expr::sequence([w("b"), w("b")]),
        "choice-failure" => Expr::choice([w("x"), w("y")]),
        "choice-rollback" => Expr::choice([
            Expr::sequence([w("a"), w("x")]),
            Expr::sequence([w("a"), w("b")]),
        ]),
        "match-nonordered" => Expr::non_ordered([w("b"), w("a")]).match_only(),
        "match-empty-chain" => Expr::sequence([]).match_only(),
        "number" => Expr::Number,
        "match-number" => Expr::Number.match_only(),
        "identifier" => Expr::Identifier,
        "match-identifier" => Expr::Identifier.match_only(),
        "match-quoted" => Expr::Quoted('\'').match_only(),
        "quoted" => Expr::Quoted('\''),
        "double-quoted" => Expr::Quoted('"'),
        "stop-invert-identifier" => Expr::Identifier.stop_invert(),
        "stop-consume-number" => Expr::Number.stop_consume(),
        "optional-a" => w("a").optional_java(),
        "match-optional-a" => w("a").optional_java().match_only(),
        "repeat-a" => w("a").repeat_java(0, None),
        "match-repeat-a" => w("a").repeat_java(0, None).match_only(),
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
        "WordParser"
            | "NumberParser"
            | "IdentifierParser"
            | "SingleQuotedParser"
            | "DoubleQuotedParser"
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
fn verify_classic_oracle(traced: bool, trivia: u8) {
    let mut failures = Vec::new();
    let corpus = if trivia != 0 {
        include_str!("../../../conformance/token-propagation/atomic-trivia.tsv")
    } else {
        include_str!("../../../conformance/token-propagation/cases.tsv")
    };
    let rows: Vec<_> = corpus
        .lines()
        .filter(|l| !l.starts_with('#') && !l.is_empty())
        .collect();
    assert_eq!(rows.len(), if trivia != 0 { 52 } else { 166 });
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
                let parsed = context.with_trivia(trivia == 1, |context| {
                    let expression = if trivia == 2 {
                        parser(c[2]).lexical_trivia_scope(atomic_trivia())
                    } else {
                        parser(c[2])
                    };
                    context.parse_with_token_mode(&expression, kind(c[3]), c[4] == "true")
                });
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
                if traced && trivia != 0 {
                    let trace = context.token_trace().unwrap().unwrap();
                    let actual = trace
                        .iter()
                        .map(|leaf| leaf.text(&input).unwrap())
                        .collect::<String>();
                    let expected_text = if c[14] == "~" {
                        String::new()
                    } else {
                        c[14]
                            .split(';')
                            .map(|sig| decode(sig.rsplit(':').next().unwrap()))
                            .collect::<String>()
                    };
                    assert_eq!(actual, expected_text, "retained trace source {}", c[0]);
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
    verify_classic_oracle(false, 0);
}
#[test]
fn independent_classic_trace_oracle() {
    verify_classic_oracle(true, 0);
}

thread_local! {static FRESH:std::cell::Cell<Option<unlaxer_runtime::token::NodeId>>=const{std::cell::Cell::new(None)};}
fn mutate(c: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    c.declare_complete_token_trace();
    c.set_state("value", 99u32);
    let existing = *c
        .state::<unlaxer_runtime::token::NodeId>("existing")
        .unwrap();
    c.put_token_extra(existing, "label", "during");
    c.scopes_mut().enter();
    c.scopes_mut().declare("during", 1);
    c.scopes_mut().add_reference("unknown", 1, 1);
    c.scopes_mut()
        .add_diagnostic("during", 1, 1, unlaxer_runtime::Severity::Warning);
    let result = c.parse(&w("😀"));
    if let Ok(matched) = &result {
        let node = c.token_node_id(matched.nodes[0]).unwrap();
        FRESH.with(|fresh| fresh.set(Some(node)));
        c.put_related_token(existing, "new", node);
    }
    let scenario = c.state::<String>("scenario").unwrap().clone();
    if scenario.ends_with("exception") {
        return Err(c.error("callback failure"));
    }
    result
}
fn raw_consume(c: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    c.set_state("value", 99u32);
    let existing = *c
        .state::<unlaxer_runtime::token::NodeId>("existing")
        .unwrap();
    c.put_token_extra(existing, "label", "during");
    let start = c.position();
    assert!(c.advance(1));
    Ok(unlaxer_runtime::ParseMatch {
        span: unlaxer_runtime::Span { start, end: start },
        nodes: vec![],
        captures: vec![],
    })
}
#[test]
fn independent_owned_state_oracle() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            for row in include_str!("../../../conformance/token-propagation/state.tsv")
                .lines()
                .filter(|row| !row.starts_with('#') && !row.is_empty())
            {
                let data: Vec<_> = row.split('\t').collect();
                let input = decode(data[2]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                c.set_reset_matched_with_consumed(false);
                let prior = c
                    .parse_with_token_mode(&w("p"), TokenKind::Consumed, false)
                    .unwrap();
                let existing = c.token_node_id(prior.nodes[0]).unwrap();
                c.put_token_extra(existing, "label", "before");
                c.set_state("value", 7u32);
                c.set_state("existing", existing);
                c.set_state("scenario", data[1].to_owned());
                c.scopes_mut().enter();
                c.scopes_mut().declare("before", 0);
                FRESH.with(|fresh| fresh.set(None));
                let callback = Expr::Custom(mutate);
                let child = match data[1] {
                    "choice" => Expr::choice([Expr::sequence([callback, w("x")]), w("😀")]),
                    "raw-consume" => Expr::Custom(raw_consume),
                    "resource" => {
                        let mut nested = w("x");
                        for _ in 0..1500 {
                            nested = nested.match_only();
                        }
                        Expr::sequence([callback, nested])
                    }
                    "recovery" | "consume-recovery" => {
                        let child = w("ok").recover(
                            unlaxer_runtime::RecoveryMode::Sync,
                            vec![";"],
                            "syntax error: skipped to sync point",
                        );
                        if data[1] == "consume-recovery" {
                            child.stop_consume()
                        } else {
                            child
                        }
                    }
                    _ => callback,
                };
                let parser = child.match_only();
                let mut original = 0;
                let mut roots = 1;
                let perform = |context: &mut ParseContext<'_>, original: &mut usize| {
                    let parsed = context.parse_with_token_mode(&parser, TokenKind::Consumed, false);
                    if let Ok(parsed) = &parsed {
                        let id = context.token_node_id(parsed.nodes[0]).unwrap();
                        *original = context
                            .token_info(id)
                            .unwrap()
                            .original_tokens()
                            .unwrap_or(&[])
                            .len();
                    }
                    parsed
                };
                if data[1] == "retry" {
                    let ignored: Result<(), _> = c.transaction(|context| {
                        perform(context, &mut original)?;
                        Err(context.error("rollback retry"))
                    });
                    assert!(ignored.is_err());
                }
                let parsed = if data[1] == "outer-rollback" {
                    let mut saved = None;
                    let rollback: Result<(), _> = c.transaction(|context| {
                        saved = Some(perform(context, &mut original)?);
                        Err(context.error("outer rollback"))
                    });
                    assert!(rollback.is_err());
                    Ok(saved.unwrap())
                } else {
                    perform(&mut c, &mut original)
                };
                if parsed.is_ok() && data[1] != "outer-rollback" {
                    roots += 1;
                }
                if data[1] == "raw-consume" {
                    assert_eq!(
                        signatures(&mut c, &parsed.as_ref().unwrap().nodes),
                        "matchOnly@2:0:~"
                    );
                }
                match data[3] {
                    "success" => assert!(parsed.is_ok(), "{}: {parsed:?}", data[0]),
                    _ => assert!(parsed.is_err(), "{}", data[0]),
                }
                if data[3] == "resource_limit" {
                    let error = parsed.as_ref().unwrap_err();
                    assert!(error.expected[0].contains("below"));
                    assert_eq!(&c.parse(&w("p")).unwrap_err(), error);
                }
                // Rust callback failures use ParseError. Panicking callbacks retain the
                // established discard-context contract; they are not converted to success.
                if data[3] == "callback_exception" {
                    if c.options().diagnostics == Diagnostics::Detailed {
                        assert!(parsed
                            .as_ref()
                            .unwrap_err()
                            .expected
                            .iter()
                            .any(|name| name == "callback failure"));
                    }
                    let rolled: Result<(), _> = c.transaction(|context| {
                        context.parse_with_token_mode(&w("😀"), TokenKind::Consumed, false)?;
                        Err(context.error("discard retry"))
                    });
                    assert!(rolled.is_err());
                }
                let values = vec![
                    c.position().to_string(),
                    c.matched_position().to_string(),
                    c.state::<u32>("value").unwrap().to_string(),
                    c.token_info(existing)
                        .unwrap()
                        .extra("label")
                        .unwrap()
                        .into(),
                    c.token_info(existing)
                        .unwrap()
                        .related("new")
                        .is_some()
                        .to_string(),
                    FRESH
                        .with(|fresh| fresh.get())
                        .is_some_and(|id| c.token_info(id).is_some())
                        .to_string(),
                    roots.to_string(),
                    original.to_string(),
                    c.recoveries().len().to_string(),
                    c.scopes().current_scope_depth().to_string(),
                    c.scopes().all_declarations().len().to_string(),
                    c.scopes().all_references().len().to_string(),
                    c.scopes().diagnostics().len().to_string(),
                ];
                assert_eq!(values, data[4..], "{}/{memo:?}/{diagnostics:?}", data[0]);
            }
        }
    }
}

#[test]
fn shared_declarative_lexical_atom_modes() {
    use unlaxer_runtime::lexical::{LexicalExpression as L, Op};
    let range = L {
        op: Op::RANGE,
        text: "",
        min: 'a' as i32,
        max: 'c' as i32,
        children: vec![],
    };
    let parser = Expr::Lexical(
        "range",
        L {
            op: Op::REPEAT,
            text: "",
            min: 1,
            max: -1,
            children: vec![range],
        },
    );
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            for row in include_str!("../../../conformance/token-propagation/lexical.tsv")
                .lines()
                .filter(|line| !line.starts_with('#'))
            {
                let columns: Vec<_> = row.split('\t').collect();
                assert_eq!(
                    columns[4], "false",
                    "fixture priorMatched must be implemented before extension"
                );
                let input = decode(columns[1]);
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                let result =
                    context.parse_with_token_mode(&parser, kind(columns[2]), columns[3] == "true");
                assert_eq!(result.is_ok(), columns[5] == "true", "{}", columns[0]);
                assert_eq!(context.position().to_string(), columns[6]);
                assert_eq!(context.matched_position().to_string(), columns[7]);
                if let Ok(result) = result {
                    let expected = format!(
                        "{}@{}:{}:{}",
                        columns[2], columns[8], columns[9], columns[10]
                    );
                    assert_eq!(
                        signatures(&mut context, &result.nodes),
                        expected,
                        "{}",
                        columns[0]
                    );
                }
            }
        }
    }
}
#[test]
fn wrappers_activate_prior_terminals_and_restore_parent_modes() {
    let mut c = ParseContext::new("ab");
    let result = c
        .parse(&Expr::sequence([w("a"), w("b").match_only()]))
        .unwrap();
    assert_eq!((c.position(), c.matched_position()), (1, 2));
    assert_eq!(signatures(&mut c, &result.nodes), "consumed@0:1:61");
    assert_eq!(c.token_mode(), Default::default());
    let mut c = ParseContext::new("a");
    assert!(c
        .parse_with_token_mode(
            &Expr::sequence([w("a"), w("z")]),
            TokenKind::VirtualMatchOnly,
            true
        )
        .is_err());
    assert_eq!(c.token_mode(), Default::default());
}
#[test]
fn projected_sources_are_shared_and_cannot_be_replayed_as_other_input() {
    use unlaxer_runtime::Span;
    let mut c = ParseContext::new("abc");
    c.set_reset_matched_with_consumed(false);
    c.parse_with_token_mode(&w("a"), TokenKind::MatchOnly, false)
        .unwrap();
    let projected = Expr::sequence([w("b"), w("b")]);
    let matched = c
        .parse_with_token_mode(&projected, TokenKind::VirtualConsumed, false)
        .unwrap();
    let id = c.token_node_id(matched.nodes[0]).unwrap();
    assert_eq!(c.token_text(id), Some("bb"));
    assert_eq!(c.text(Span { start: 1, end: 3 }), Some("bc"));
    assert!(matches!(
        c.token_info(id).unwrap().source,
        TokenSource::Projected { .. }
    ));
    let mut c = ParseContext::new("abc");
    c.set_reset_matched_with_consumed(false);
    c.parse_with_token_mode(&w("a"), TokenKind::MatchOnly, false)
        .unwrap();
    let error = c
        .parse_with_token_mode(
            &projected.capture("value"),
            TokenKind::VirtualConsumed,
            false,
        )
        .unwrap_err();
    assert!(error
        .expected
        .iter()
        .any(|name| name.contains("AST field capture requires original input source")));
    assert_eq!((c.position(), c.matched_position()), (0, 1));
    assert!(c.capture_spans("value").is_empty());
}
#[test]
fn mode_trace_budget_never_publishes_a_partial_success() {
    let options = ParseOptions::default().with_token_trace(unlaxer_runtime::token_trace::Limits {
        leaves: 1,
        source_bytes: 8,
    });
    let mut c = ParseContext::with_options("aa", options);
    c.parse_with_token_mode(
        &Expr::sequence([w("a"), w("a")]),
        TokenKind::MatchOnly,
        false,
    )
    .unwrap();
    assert!(matches!(
        c.token_trace(),
        Some(Err(unlaxer_runtime::token_trace::Error::Limit))
    ));
}

#[test]
fn native_repeat_progress_uses_the_inherited_cursor() {
    let mut c = ParseContext::new("aab");
    let result = c
        .parse_with_token_mode(&w("a").repeat(0, None), TokenKind::MatchOnly, false)
        .unwrap();
    assert_eq!((c.position(), c.matched_position()), (0, 2));
    assert_eq!(signatures(&mut c, &result.nodes), "matchOnly@0:2:6161");
}
#[test]
fn unsupported_native_scanner_is_not_a_resource_abort() {
    let mut c = ParseContext::new("```java:Demo\n");
    let error = c
        .parse_with_token_mode(&Expr::CodeStart, TokenKind::MatchOnly, false)
        .unwrap_err();
    assert!(error
        .expected
        .iter()
        .any(|hint| hint.contains("mode-aware parser")));
    assert_eq!((c.position(), c.matched_position()), (0, 0));
    assert!(c.parse(&Expr::CodeStart).is_ok());
}

#[test]
fn classic_unique_choice_limit_retains_the_structured_diagnostic_kind() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            let grammar = vec![unlaxer_runtime::Rule {
                name: "root",
                expression: Expr::unique_longest_choice([w("a").match_only()]),
            }];
            let error = unlaxer_runtime::parse_detailed_with_options(
                &grammar,
                0,
                false,
                "a",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            )
            .unwrap_err();
            assert_eq!(error.kind, "choice_limit");
        }
    }
}

#[test]
fn atomic_trivia_oracle() {
    for trivia in [1, 2] {
        verify_classic_oracle(false, trivia);
        verify_classic_oracle(true, trivia);
    }
}

fn atomic_trivia() -> unlaxer_runtime::lexical::LexicalExpression {
    use unlaxer_runtime::lexical::{LexicalExpression as L, Op};
    L {
        op: Op::CHOICE,
        text: "",
        min: 0,
        max: 0,
        children: [" ", "/*😀*/", "\r\n"]
            .into_iter()
            .map(|text| L {
                op: Op::LITERAL,
                text,
                min: 0,
                max: 0,
                children: vec![],
            })
            .collect(),
    }
}

#[test]
fn atomic_scope_restores_outer_trivia_after_success_failure_and_rollback() {
    for (source, first, second) in [
        ("A B", Expr::Identifier, "B"),
        ("1 2", Expr::Number, "2"),
        ("'a b' B", Expr::Quoted('\''), "B"),
        ("\"a b\" B", Expr::Quoted('"'), "B"),
    ] {
        for named in [false, true] {
            let expression = Expr::sequence([
                Expr::choice([
                    Expr::sequence([first.clone(), Expr::literal("missing")]),
                    first.clone(),
                ]),
                Expr::literal(second),
            ]);
            let expression = if named {
                expression.lexical_trivia_scope(atomic_trivia())
            } else {
                expression.trivia_scope(true)
            };
            let mut context = ParseContext::new(source);
            assert!(
                context
                    .parse_with_token_mode(&expression, TokenKind::Consumed, false)
                    .is_ok(),
                "{source}"
            );
            assert_eq!(
                (context.position(), context.matched_position()),
                (source.chars().count(), source.chars().count())
            );
        }
        // Ordinary scanner entry remains atomic too; it must not skip leading/trailing trivia.
        let mut native = ParseContext::new(source);
        let parsed = native.with_trivia(true, |c| c.parse(&first)).unwrap();
        assert_eq!(native.position(), source.chars().count() - 2);
        assert_eq!(parsed.span.end, native.position());
        assert!(native.parse(&Expr::literal(" ")).is_ok());
    }
    let mut context = ParseContext::new(" 1");
    context.with_trivia(true, |context| {
        assert!(context
            .parse_with_token_mode(&Expr::Number, TokenKind::Consumed, false)
            .is_err());
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert!(context
            .parse_with_token_mode(&Expr::sequence([Expr::Number]), TokenKind::Consumed, false)
            .is_ok());
        assert_eq!(context.position(), 2);
    });
}

#[test]
fn classic_outer_trivia_source_oracle() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            for traced in [false, true] {
                for row in
                    include_str!("../../../conformance/token-propagation/sequence-trivia.tsv")
                        .lines()
                        .filter(|s| !s.starts_with('#') && !s.is_empty())
                {
                    let c: Vec<_> = row.split('\t').collect();
                    let input = decode(c[1]);
                    let options =
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                    let options = if traced {
                        options.with_token_trace(Default::default())
                    } else {
                        options
                    };
                    let mut context = ParseContext::with_options(&input, options);
                    context.set_reset_matched_with_consumed(false);
                    let mut committed = vec![];
                    for (column, kind) in [(5, TokenKind::Consumed), (6, TokenKind::MatchOnly)] {
                        if c[column] != "~" {
                            let value = Box::leak(decode(c[column]).into_boxed_str());
                            committed.extend(
                                context
                                    .parse_with_token_mode(&w(value), kind, false)
                                    .unwrap()
                                    .nodes,
                            );
                        }
                    }
                    let first = match c[2] {
                        "stop-consume" | "named-stop-consume" => w("A").stop_consume(),
                        "match-first" => w("A").match_only(),
                        _ => w("A"),
                    };
                    let expression = Expr::sequence([first, w("B")]);
                    let expression = match c[2] {
                        "rollback" => Expr::choice([Expr::sequence([w("A"), w("X")]), expression]),
                        "recovery" => expression.recover(
                            unlaxer_runtime::RecoveryMode::Sync,
                            [";"],
                            "syntax error: skipped to sync point",
                        ),
                        _ => expression,
                    };
                    let expression = if c[2].starts_with("named-") {
                        expression.lexical_trivia_scope(
                            unlaxer_runtime::lexical::LexicalExpression {
                                op: unlaxer_runtime::lexical::Op::LITERAL,
                                text: "~",
                                min: 0,
                                max: 0,
                                children: vec![],
                            },
                        )
                    } else {
                        expression
                    };
                    let result = context.with_trivia(true, |context| {
                        context.parse_with_token_mode(&expression, kind(c[3]), c[4] == "true")
                    });
                    assert_eq!(
                        result.is_ok(),
                        c[7] == "true",
                        "{} {memo:?} {diagnostics:?} traced={traced}",
                        c[0]
                    );
                    assert_eq!(
                        (context.position(), context.matched_position()),
                        (c[8].parse().unwrap(), c[9].parse().unwrap()),
                        "{}",
                        c[0]
                    );
                    let root = if let Ok(parsed) = result {
                        let sig = signatures(&mut context, &parsed.nodes);
                        committed.extend(parsed.nodes);
                        sig
                    } else {
                        "~".into()
                    };
                    assert_eq!(root, c[10], "{}", c[0]);
                    assert_eq!(signatures(&mut context, &committed), c[11], "{}", c[0]);
                    if traced && c[2] == "recovery" {
                        assert!(context.token_trace().unwrap().is_err());
                        assert_eq!(context.recoveries().len(), 1);
                        assert_eq!(
                            context.recoveries()[0].span,
                            unlaxer_runtime::Span { start: 0, end: 5 }
                        );
                    } else if traced {
                        let trace = context.token_trace().unwrap().unwrap();
                        let text = trace
                            .iter()
                            .map(|leaf| leaf.text(&input).unwrap())
                            .collect::<String>();
                        let expected = if c[11] == "~" {
                            String::new()
                        } else {
                            c[11]
                                .split(';')
                                .map(|s| decode(s.rsplit(':').next().unwrap()))
                                .collect::<String>()
                        };
                        assert_eq!(text, expected, "trace {}", c[0]);
                    }
                }
            }
        }
    }
}

#[test]
fn classic_trivia_trace_limit_is_observational_and_failed_branch_restores_budget() {
    use unlaxer_runtime::token_trace::{Error, Limits};
    let options = ParseOptions::default().with_token_trace(Limits {
        leaves: 1,
        source_bytes: 3,
    });
    let mut limited = ParseContext::with_options("A B", options);
    let parsed = limited
        .with_trivia(true, |c| {
            c.parse_with_token_mode(
                &Expr::sequence([w("A"), w("B")]),
                TokenKind::Consumed,
                false,
            )
        })
        .unwrap();
    assert_eq!(
        signatures(&mut limited, &parsed.nodes),
        "consumed@0:3:412042"
    );
    assert_eq!((limited.position(), limited.matched_position()), (3, 3));
    assert_eq!(limited.token_trace().unwrap(), Err(Error::Limit));

    let mut rolled_back = ParseContext::with_options("A B", options);
    let expression = Expr::choice([Expr::sequence([w("A"), w("B"), w("missing")]), w("A B")]);
    let parsed = rolled_back
        .with_trivia(true, |c| {
            c.parse_with_token_mode(&expression, TokenKind::Consumed, false)
        })
        .unwrap();
    assert_eq!(
        signatures(&mut rolled_back, &parsed.nodes),
        "consumed@0:3:412042"
    );
    let trace = rolled_back.token_trace().unwrap().unwrap();
    assert_eq!(trace.len(), 1);
    assert_eq!(trace[0].text("A B").unwrap(), "A B");
}
