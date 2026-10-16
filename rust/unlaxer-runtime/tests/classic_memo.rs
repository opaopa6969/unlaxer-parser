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
#[derive(Clone)]
struct Case(Vec<String>);
fn scenario(name: &str) -> Expr {
    match name {
        "capture" => Expr::sequence([w("a").capture("field"), w("b").capture("field")]),
        "nonordered" => Expr::non_ordered([w("b"), w("a")]),
        "projection" => Expr::sequence([w("b"), w("b")]),
        "number" => Expr::Number,
        "identifier" => Expr::Identifier,
        "quoted" => Expr::Quoted('\''),
        "word" => Expr::literal("a"),
        "word-b" => Expr::literal("b"),
        "match-chain" => Expr::sequence([w("a"), w("b")]).match_only(),
        "consume" => w("a").stop_consume(),
        "stop-invert" => w("a").stop_invert(),
        "double-invert" => w("a").invert_match().invert_match(),
        "optional" => w("a").optional_java(),
        "repeat" => w("a").repeat_java(0, None),
        "family" => w("👨‍👩‍👧‍👦"),
        "eof" => Expr::Eof,
        "zero" => w("x").repeat_java(0, Some(0)),
        _ => panic!("{name}"),
    }
}
fn retry(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    context.declare_complete_token_trace();
    let Case(f) = context.state::<Case>("case").unwrap().clone();
    let enabled = *context.state::<bool>("enabled").unwrap();
    let traced = *context.state::<bool>("traced").unwrap();
    let n = |i: usize| f[i].parse::<usize>().unwrap();
    let snapshot =
        unlaxer_runtime::source::Snapshot::new("classic-memo", 1, context.source()).unwrap();
    let mut old_ids = Vec::new();
    let mut observations = Vec::new();
    for attempt in 0..3 {
        let mut id = None;
        let _: Result<(), unlaxer_runtime::ParseError> = context.transaction(|context| {
            context.set_state("rollback", 99_i32);
            context.scopes_mut().declare("outer", 0);
            let parsed = context.parse_with_token_mode(&Expr::Rule(1), kind(&f[3]), f[4] == "true");
            assert_eq!(parsed.is_ok(), f[8] == "true", "{}", f[0]);
            assert!(context.scopes().is_declared("outer"));
            if f[8] == "false" {
                assert_eq!(
                    context.failure().offset,
                    if *context.state::<Diagnostics>("diagnostics").unwrap()
                        == Diagnostics::DetailedOnFailure
                    {
                        0
                    } else {
                        n(23)
                    },
                    "{}",
                    f[0]
                );
            }
            if f[2] == "capture" {
                assert_eq!(
                    context.capture_spans("field"),
                    &[
                        unlaxer_runtime::Span { start: 0, end: 1 },
                        unlaxer_runtime::Span { start: 1, end: 2 }
                    ]
                );
            }
            assert_eq!(
                (context.position(), context.matched_position()),
                (n(9), n(10)),
                "{}",
                f[0]
            );
            assert_eq!(
                (
                    snapshot.utf16(context.position()).unwrap(),
                    snapshot.utf8(context.position()).unwrap()
                ),
                (n(15), n(16)),
                "{}",
                f[0]
            );
            assert_eq!(
                (
                    snapshot.utf16(context.matched_position()).unwrap(),
                    snapshot.utf8(context.matched_position()).unwrap()
                ),
                (n(17), n(18)),
                "{}",
                f[0]
            );
            let mut structure = String::new();
            let mut root_summary = String::from("-");
            let mut original = String::from("-");
            if let Ok(parsed) = parsed {
                let root = parsed.root_node().unwrap();
                let token = context.token_node_id(root).unwrap();
                for &old in &old_ids {
                    assert!(context.token_info(old).is_none());
                    assert_ne!(old, token);
                }
                let info = context.token_info(token).unwrap();
                assert_eq!(info.kind, kind(&f[3]));
                let span = context.node(root).unwrap().span;
                assert_eq!(
                    (span.start, span.end - span.start),
                    (n(11), n(12)),
                    "{}",
                    f[0]
                );
                assert_eq!(
                    encode(context.token_text(token).unwrap()).replace("~", "-"),
                    f[13],
                    "{}",
                    f[0]
                );
                assert_eq!(
                    (
                        snapshot.utf16(span.start).unwrap(),
                        snapshot.utf8(span.start).unwrap()
                    ),
                    (n(19), n(20)),
                    "{}",
                    f[0]
                );
                assert_eq!(
                    (
                        snapshot.utf16(span.end).unwrap(),
                        snapshot.utf8(span.end).unwrap()
                    ),
                    (n(21), n(22)),
                    "{}",
                    f[0]
                );
                root_summary = signature(context, root);
                let choice = context.node(root).unwrap().children[0];
                let choice_id = context.token_node_id(choice).unwrap();
                let raw = context
                    .token_info(choice_id)
                    .unwrap()
                    .original_tokens()
                    .unwrap()
                    .to_vec();
                let text = raw
                    .iter()
                    .map(|&node| {
                        let id = context.token_node_id(node).unwrap();
                        context.token_text(id).unwrap().to_owned()
                    })
                    .collect::<String>();
                original = encode(&text).replace("~", "-");
                assert_eq!(original, f[14], "{}", f[0]);
                let tree = context.tree(root).unwrap();
                structure = format!(
                    "{:?}/{:?}",
                    tree.nodes,
                    (0..tree.nodes.len())
                        .map(|n| tree
                            .token_node_id(n)
                            .and_then(|id| tree.token_info(id))
                            .map(|v| format!("{v:?}")))
                        .collect::<Vec<_>>()
                );
                assert!(context
                    .token_info(token)
                    .unwrap()
                    .extra("changed")
                    .is_none());
                context.put_token_extra(token, "changed", "caller-owned");
                id = Some(token);
            }
            observations.push((
                context.position(),
                context.matched_position(),
                context.failure(),
                structure,
                root_summary,
                original,
                format!("{:?}", context.token_trace()),
            ));
            Err(unlaxer_runtime::ParseError {
                offset: 0,
                expected: vec![],
            })
        });
        assert_eq!(
            (context.position(), context.matched_position()),
            (n(5), n(6))
        );
        assert_eq!(context.state::<i32>("rollback"), Some(&7));
        assert!(!context.scopes().is_declared("outer"));
        assert!(context.capture_spans("field").is_empty());
        if let Some(id) = id {
            assert!(context.token_info(id).is_none());
            old_ids.push(id);
        }
        if enabled {
            assert_eq!(
                context.memoized_success_hits(),
                if !traced && f[8] == "true" {
                    attempt
                } else {
                    0
                },
                "{}",
                f[0]
            );
            assert_eq!(
                context.memoized_failure_hits(),
                if f[8] == "false" { attempt } else { 0 },
                "{}",
                f[0]
            );
        }
    }
    assert_eq!(observations[0], observations[1], "{}", f[0]);
    assert_eq!(observations[1], observations[2], "{}", f[0]);
    context.set_state("observation", format!("{:?}", observations[0]));
    Ok(unlaxer_runtime::ParseMatch::empty(unlaxer_runtime::Span {
        start: n(5),
        end: n(5),
    }))
}
#[test]
fn shared_classic_oracle_uses_real_success_and_failure_hits_and_owned_replay() {
    use unlaxer_runtime::{share_grammar, Rule};
    let rows = include_str!("../../../conformance/classic-memo/cases.tsv")
        .lines()
        .filter(|l| !l.starts_with('#'))
        .collect::<Vec<_>>();
    for row in rows {
        let f = row.split('\t').map(str::to_owned).collect::<Vec<_>>();
        let input = decode(&f[1].replace("-", "~"));
        for traced in [false, true] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let mut off = None;
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    let mut options =
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                    if traced {
                        options = options.with_token_trace(Default::default());
                    }
                    let mut context = ParseContext::with_options(&input, options);
                    context.set_reset_matched_with_consumed(false);
                    let c = f[5].parse::<usize>().unwrap();
                    let m = f[6].parse::<usize>().unwrap();
                    context.advance(c);
                    for _ in c..m {
                        context
                            .parse_with_token_mode(&Expr::Any, TokenKind::MatchOnly, false)
                            .unwrap();
                    }
                    context.set_reset_matched_with_consumed(f[7] == "true");
                    context.set_state("case", Case(f.clone()));
                    context.set_state("diagnostics", diagnostics);
                    context.set_state("enabled", memo == Memoization::SafeFailures);
                    context.set_state("traced", traced);
                    context.set_state("rollback", 7_i32);
                    let grammar = share_grammar(vec![
                        Rule {
                            name: "driver",
                            expression: Expr::Custom(retry),
                        },
                        Rule {
                            name: "pure",
                            expression: Expr::choice([scenario(&f[2])]),
                        },
                    ]);
                    context.parse_shared_grammar(&grammar, 0, false).unwrap();
                    let value = context.state::<String>("observation").unwrap().clone();
                    if memo == Memoization::Off {
                        off = Some(value);
                    } else {
                        assert_eq!(
                            off.as_ref(),
                            Some(&value),
                            "{} / {diagnostics:?} / trace={traced}",
                            f[0]
                        );
                    }
                    println!("CLASSIC\t{}\t{memo:?}\t{diagnostics:?}\ttrace={traced}\t3\tsuccessHits={}\tfailureHits={}",f[0],context.memoized_success_hits(),context.memoized_failure_hits());
                }
            }
        }
    }
}

fn cross_keys(c: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    let mut success = 0;
    let mut failures = 0;
    for kind in [
        TokenKind::Consumed,
        TokenKind::MatchOnly,
        TokenKind::VirtualConsumed,
        TokenKind::VirtualMatchOnly,
    ] {
        for invert in [false, true] {
            for reset in [false, true] {
                c.set_reset_matched_with_consumed(reset);
                for repeated in [false, true] {
                    let _: Result<(), unlaxer_runtime::ParseError> = c.transaction(|c| {
                        let result = c.parse_with_token_mode(&Expr::Rule(1), kind, invert);
                        assert_eq!(result.is_ok(), !invert);
                        if let Ok(result) = result {
                            let root = result.root_node().unwrap();
                            assert_eq!(c.node(root).unwrap().children.len(), 1);
                        }
                        Err(unlaxer_runtime::ParseError {
                            offset: 0,
                            expected: vec![],
                        })
                    });
                    if repeated {
                        if invert {
                            failures += 1;
                        } else {
                            success += 1;
                        }
                    }
                    assert_eq!(
                        c.memoized_success_hits(),
                        success,
                        "{kind:?}/{invert}/{reset}/{repeated}"
                    );
                    assert_eq!(
                        c.memoized_failure_hits(),
                        failures,
                        "{kind:?}/{invert}/{reset}/{repeated}"
                    );
                }
            }
        }
    }
    // A normal-mode entry must not alias the explicit Consumed entry.
    c.set_reset_matched_with_consumed(true);
    let parsed = c.parse(&Expr::Rule(1)).unwrap();
    assert_eq!(
        c.node(parsed.root_node().unwrap()).unwrap().children.len(),
        0
    );
    assert_eq!(c.memoized_success_hits(), success);
    Ok(parsed)
}
#[test]
fn all_mode_invert_and_reset_keys_remain_distinct_with_actual_control_hits() {
    let grammar = unlaxer_runtime::share_grammar(vec![
        unlaxer_runtime::Rule {
            name: "driver",
            expression: Expr::Custom(cross_keys),
        },
        unlaxer_runtime::Rule {
            name: "child",
            expression: Expr::choice([Expr::literal("a")]),
        },
    ]);
    let mut c = ParseContext::with_options(
        "a?",
        ParseOptions::with_memoization(Memoization::SafeFailures),
    );
    c.parse_shared_grammar(&grammar, 0, false).unwrap();
    assert_eq!(
        (c.memoized_success_hits(), c.memoized_failure_hits()),
        (8, 8)
    );
}

fn budget_retry(c: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    assert!(c
        .parse_with_token_mode(&Expr::Rule(1), TokenKind::Consumed, false)
        .is_err());
    let mut deep = Expr::Rule(1);
    for _ in 0..1010 {
        deep = deep.optional();
    }
    c.set_state("changed", 99_i32);
    let result = c.parse_with_token_mode(&deep, TokenKind::Consumed, false);
    std::mem::forget(deep);
    result
}
#[test]
fn classic_cached_failures_cannot_bypass_sticky_resource_failure_on_two_mib_stack() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            for memo in [Memoization::Off, Memoization::SafeFailures] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    let mut child = Expr::literal("z");
                    for _ in 0..64 {
                        child = child.optional();
                    }
                    let grammar = unlaxer_runtime::share_grammar(vec![
                        unlaxer_runtime::Rule {
                            name: "driver",
                            expression: Expr::Custom(budget_retry),
                        },
                        unlaxer_runtime::Rule {
                            name: "failure",
                            expression: child.then(Expr::literal("!")),
                        },
                    ]);
                    let mut c = ParseContext::with_options(
                        "",
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    c.set_state("changed", 7_i32);
                    let error = c.parse_shared_grammar(&grammar, 0, false).unwrap_err();
                    assert_eq!(error.expected, ["expression nesting below 1024"]);
                    assert_eq!(c.state::<i32>("changed"), Some(&7));
                    assert_eq!((c.position(), c.matched_position()), (0, 0));
                    assert!(c.node(0).is_none());
                    assert_eq!(
                        c.parse_with_token_mode(&Expr::Empty, TokenKind::Consumed, false)
                            .unwrap_err(),
                        error
                    );
                }
            }
        })
        .unwrap()
        .join()
        .unwrap();
}
