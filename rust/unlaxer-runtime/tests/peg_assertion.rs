use unlaxer_runtime::token::{TokenExpr, TokenKind};
use unlaxer_runtime::{Diagnostics, Expr, Memoization, ParseContext, ParseOptions};

fn text(value: &str) -> String {
    if value == "~" {
        String::new()
    } else {
        value.replace("\\r", "\r").replace("\\n", "\n")
    }
}
fn literal(value: &str) -> Expr {
    Expr::literal(Box::leak(text(value).into_boxed_str()))
}
fn child(value: &str) -> Expr {
    match value {
        "hint" => Expr::sequence([literal("a"), literal("b"), literal("!").optional()]),
        "failure" => Expr::sequence([literal("a"), literal("b"), literal("!")]),
        "nested" => Expr::sequence([literal("a").ahead(), literal("b")]),
        "~" => Expr::Empty,
        _ => literal(value),
    }
}
#[test]
fn shared_independent_oracle() {
    let rows: Vec<_> = include_str!("../../../conformance/peg-assertion/cases.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
        .collect();
    assert_eq!(rows.len(), 22);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in &rows {
                let c: Vec<_> = row.split('\t').collect();
                assert_eq!(c.len(), 13);
                let input = text(c[1]);
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                if c[2] != "~" {
                    context.parse(&literal(c[2])).unwrap();
                }
                if c[3] != "~" {
                    context
                        .parse_token(
                            &TokenExpr::Word {
                                rule: 0,
                                text: Box::leak(text(c[3]).into_boxed_str()),
                            },
                            TokenKind::MatchOnly,
                            false,
                        )
                        .unwrap();
                }
                let probe = if c[5] == "true" {
                    child(c[4]).ahead()
                } else {
                    child(c[4]).not_ahead()
                };
                let parser = if c[6] == "~" {
                    probe
                } else {
                    Expr::sequence([probe, literal(c[6])])
                };
                let result = context.parse(&parser);
                let label = format!("{}/{memo:?}/{diagnostics:?}", c[0]);
                assert_eq!(result.is_ok(), c[7] == "true", "{label}");
                assert_eq!(
                    context.position(),
                    c[8].parse::<usize>().unwrap(),
                    "{label}"
                );
                assert_eq!(
                    context.matched_position(),
                    c[9].parse::<usize>().unwrap(),
                    "{label}"
                );
                let expected_offset = if diagnostics == Diagnostics::DetailedOnFailure {
                    0
                } else {
                    c[11].parse::<usize>().unwrap()
                };
                assert_eq!(context.failure().offset, expected_offset, "{label}");
                assert_eq!(
                    !context.failure().expected.is_empty(),
                    diagnostics != Diagnostics::DetailedOnFailure && c[12] == "true",
                    "{label}"
                );
                if c[6] == "~" {
                    if let Ok(value) = result {
                        assert!(value.nodes.is_empty(), "{label}");
                        assert!(value.captures.is_empty(), "{label}");
                    }
                }
            }
        }
    }
}

fn mutate(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    let result = context.parse(&Expr::literal("a").capture("inside"))?;
    context.set_state("value", 99i32);
    context.scopes_mut().enter();
    context.scopes_mut().declare("inside", 0);
    context.scopes_mut().add_reference("inside", 0, 1);
    context
        .scopes_mut()
        .add_diagnostic("speculation", 0, 1, unlaxer_runtime::Severity::Warning);
    context.declare_complete_token_trace();
    Ok(result)
}
fn read(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    assert_eq!(context.state::<i32>("value"), Some(&99));
    assert!(context.scopes().is_declared("inside"));
    assert_eq!(context.captured("inside"), Some("a"));
    context.declare_complete_token_trace();
    context.parse(&Expr::literal(";"))
}
#[test]
fn shared_state_oracle() {
    let rows: Vec<_> = include_str!("../../../conformance/peg-assertion/state.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
        .collect();
    assert_eq!(rows.len(), 4);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in &rows {
                let c: Vec<_> = row.split('\t').collect();
                assert_eq!(c.len(), 13);
                let mut context = ParseContext::with_options(
                    "a;",
                    ParseOptions::with_memoization(memo)
                        .with_diagnostics(diagnostics)
                        .with_token_trace(Default::default()),
                );
                context.set_state("value", 7i32);
                context.scopes_mut().declare("before", 0);
                context.scopes_mut().add_reference("before", 0, 1);
                context.scopes_mut().add_diagnostic(
                    "baseline",
                    0,
                    1,
                    unlaxer_runtime::Severity::Info,
                );
                let mut body = Expr::sequence([Expr::Custom(mutate), Expr::Custom(read)]);
                if c[2] == "false" {
                    body = body.then(Expr::literal("!"));
                }
                let probe = if c[1] == "true" {
                    body.ahead()
                } else {
                    body.not_ahead()
                };
                for _ in 0..2 {
                    let result = context.parse(&probe);
                    assert_eq!(result.is_ok(), c[3] == "true", "{}", c[0]);
                    let actual = [
                        *context.state::<i32>("value").unwrap() as usize,
                        context.scopes().current_scope_depth(),
                        context.scopes().all_declarations().len(),
                        context.scopes().all_references().len(),
                        context.scopes().diagnostics().len(),
                        context.recoveries().len(),
                        context.position(),
                        context.matched_position(),
                        usize::from(context.node(0).is_some()),
                    ];
                    for (i, value) in actual.into_iter().enumerate() {
                        assert_eq!(value, c[i + 4].parse::<usize>().unwrap(), "{} / {i}", c[0]);
                    }
                    assert!(!context.scopes().is_declared("inside"));
                    assert_eq!(context.captured("inside"), None);
                    assert!(context.token_trace().unwrap().unwrap().is_empty());
                    if let Ok(value) = result {
                        assert!(value.nodes.is_empty());
                        assert!(value.captures.is_empty());
                    }
                }
            }
        }
    }
}
#[test]
fn recovery_does_not_escape_the_assertion() {
    for positive in [true, false] {
        let mut context = ParseContext::new("bad;");
        let body =
            Expr::literal("ok").recover(unlaxer_runtime::RecoveryMode::Sync, [";"], "skipped");
        let probe = if positive {
            body.ahead()
        } else {
            body.not_ahead()
        };
        assert_eq!(context.parse(&probe).is_ok(), positive);
        assert!(context.recoveries().is_empty());
        assert!(context.node(0).is_none());
        assert_eq!((context.position(), context.matched_position()), (0, 0));
    }
}
fn recurse(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    context.set_state("changed", true);
    context.parse(&Expr::Custom(recurse))
}
#[test]
fn resource_exhaustion_never_becomes_negative_success() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let mut context = ParseContext::with_options(
                    "a",
                    ParseOptions::with_memoization(Memoization::Off).with_diagnostics(diagnostics),
                );
                let error = context
                    .parse(&Expr::Custom(recurse).not_ahead())
                    .unwrap_err();
                assert!(error
                    .expected
                    .iter()
                    .any(|value| value.contains("below 256")));
                assert_eq!((context.position(), context.matched_position()), (0, 0));
                assert_eq!(context.state::<bool>("changed"), None);
                assert!(context.node(0).is_none());
                assert!(context.parse(&Expr::literal("a")).is_err());
            }
        })
        .unwrap()
        .join()
        .unwrap();
}
fn mutate_metadata(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    let id = context.token_node_id(0).unwrap();
    assert!(context.put_token_extra(id, "label", "inside"));
    context.parse(&Expr::literal("b"))
}
#[test]
fn portable_metadata_on_existing_tokens_is_restored() {
    let mut context = ParseContext::new("ab");
    let result = context
        .parse_token(
            &TokenExpr::Word { rule: 0, text: "a" },
            TokenKind::Consumed,
            false,
        )
        .unwrap();
    let id = context.token_node_id(result.nodes[0]).unwrap();
    assert!(context.put_token_extra(id, "label", "before"));
    for _ in 0..2 {
        context
            .parse(&Expr::Custom(mutate_metadata).ahead())
            .unwrap();
        assert_eq!(
            context.token_info(id).unwrap().extra("label"),
            Some("before")
        );
        assert_eq!((context.position(), context.matched_position()), (1, 1));
        assert!(context.node(1).is_none());
    }
}
