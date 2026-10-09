use unlaxer_runtime::token::{TokenExpr, TokenKind, TokenSource};
use unlaxer_runtime::{
    Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult, Rule, Span,
};

fn decode(s: &str) -> String {
    if s == "~" {
        String::new()
    } else {
        s.replace("\\r", "\r").replace("\\n", "\n")
    }
}
fn kind(s: &str) -> TokenKind {
    match s {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("unknown fixture kind {s}"),
    }
}
fn word(text: &'static str) -> TokenExpr {
    TokenExpr::Word { rule: 0, text }
}
fn wrapper(child: TokenExpr, name: &str) -> TokenExpr {
    match name {
        "sequence" => TokenExpr::Sequence(vec![child, word("b")]),
        "choice" => TokenExpr::Choice(vec![child, word("b")]),
        "choice-rollback" => TokenExpr::Choice(vec![
            TokenExpr::Sequence(vec![child.clone(), word("x")]),
            TokenExpr::Sequence(vec![child, word("b")]),
        ]),
        "consume" => TokenExpr::StopConsume(Box::new(child)),
        "invert" => TokenExpr::StopInvert(Box::new(child)),
        "not" => TokenExpr::Invert(Box::new(child)),
        "double-not" => TokenExpr::Invert(Box::new(TokenExpr::Invert(Box::new(child)))),
        "not-stop-not" => TokenExpr::Invert(Box::new(TokenExpr::StopInvert(Box::new(
            TokenExpr::Invert(Box::new(child)),
        )))),
        _ => child,
    }
}

#[test]
fn independent_shared_word_oracle() {
    assert_eq!(
        include_str!("../../../conformance/token-metadata/words.tsv")
            .lines()
            .filter(|line| !line.starts_with('#'))
            .count(),
        32
    );
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
            for line in include_str!("../../../conformance/token-metadata/words.tsv").lines() {
                if line.starts_with('#') {
                    continue;
                }
                let c: Vec<_> = line.split('\t').collect();
                assert_eq!(c.len(), 16);
                let input = decode(c[1]);
                let literal: &'static str = Box::leak(decode(c[2]).into_boxed_str());
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                if c[6] == "consume-a" {
                    context
                        .parse_token(&word("a"), TokenKind::Consumed, false)
                        .unwrap();
                }
                if c[6] == "match-a" {
                    context
                        .parse_token(&word("a"), TokenKind::MatchOnly, false)
                        .unwrap();
                }
                let result =
                    context.parse_token(&wrapper(word(literal), c[5]), kind(c[3]), c[4] == "true");
                assert_eq!(result.is_ok(), c[7] == "true", "{}", c[0]);
                assert_eq!(
                    context.position(),
                    c[8].parse::<usize>().unwrap(),
                    "{}",
                    c[0]
                );
                assert_eq!(
                    context.matched_position(),
                    c[9].parse::<usize>().unwrap(),
                    "{}",
                    c[0]
                );
                if result.is_err() {
                    assert_eq!(
                        context.failure().offset,
                        if diagnostics == Diagnostics::DetailedOnFailure {
                            0
                        } else {
                            c[14].parse().unwrap()
                        },
                        "{}",
                        c[0]
                    );
                }
                if let Ok(result) = result {
                    let spans = result
                        .nodes
                        .iter()
                        .map(|id| {
                            let span = context.node(*id).unwrap().span;
                            format!("{}:{}", span.start, span.end)
                        })
                        .collect::<Vec<_>>()
                        .join(";");
                    assert_eq!(spans, c[15], "{}", c[0]);
                    let id = context.token_node_id(result.nodes[0]).unwrap();
                    assert_eq!(
                        context.token_info(id).unwrap().kind,
                        kind(c[10]),
                        "{}",
                        c[0]
                    );
                    assert_eq!(
                        context.node(id.index()).unwrap().span,
                        Span {
                            start: c[11].parse().unwrap(),
                            end: c[12].parse().unwrap()
                        },
                        "{}",
                        c[0]
                    );
                    assert_eq!(
                        context.token_text(id),
                        Some(decode(c[13]).as_str()),
                        "{}",
                        c[0]
                    );
                }
            }
        }
    }
}

#[test]
fn owned_metadata_relations_rollback_and_stale_handles() {
    assert_eq!(
        include_str!("../../../conformance/token-metadata/metadata.tsv")
            .lines()
            .filter(|line| !line.starts_with('#'))
            .count(),
        3
    );
    for line in include_str!("../../../conformance/token-metadata/metadata.tsv").lines() {
        if line.starts_with('#') {
            continue;
        }
        let c: Vec<_> = line.split('\t').collect();
        let input = decode(c[1]);
        let first_word = Box::leak(decode(c[2]).into_boxed_str());
        let second_word = Box::leak(decode(c[3]).into_boxed_str());
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let retained;
            let first;
            let second;
            {
                let mut context =
                    ParseContext::with_options(&input, ParseOptions::with_memoization(memo));
                let result = context
                    .parse_token(&word(first_word), TokenKind::Consumed, false)
                    .unwrap();
                first = context.token_node_id(result.nodes[0]).unwrap();
                assert!(context.put_token_extra(first, "label", decode(c[5]).as_str()));
                second = context
                    .add_token_node(
                        0,
                        kind(c[9]),
                        TokenSource::Generated {
                            anchor: c[7].parse().unwrap(),
                            text: decode(c[4]),
                        },
                    )
                    .unwrap();
                assert!(context.put_related_token(first, "repair", second));
                retained = context.tree(first.index()).unwrap();
                assert_eq!(
                    (context.position(), context.matched_position()),
                    (c[7].parse().unwrap(), c[7].parse().unwrap())
                );
                let mut stale = None;
                let result: Result<(), _> = context.transaction(|context| {
                    assert!(context.put_token_extra(first, "label", "changed"));
                    assert_eq!(context.remove_related_token(first, "repair"), Some(second));
                    let parsed =
                        context.parse_token(&word(second_word), TokenKind::Consumed, false)?;
                    let id = context.token_node_id(parsed.nodes[0]).unwrap();
                    stale = Some(id);
                    assert!(context.put_related_token(first, "failed", id));
                    Err(context.error("outer failure"))
                });
                assert!(result.is_err());
                assert_eq!(
                    context.token_info(first).unwrap().extra("label"),
                    Some(decode(c[5]).as_str())
                );
                assert_eq!(
                    context.token_info(first).unwrap().related("repair"),
                    Some(second)
                );
                assert_eq!(context.token_info(first).unwrap().related("failed"), None);
                assert!(context.token_info(stale.unwrap()).is_none());
                let parsed = context
                    .parse_token(&word(second_word), TokenKind::Consumed, false)
                    .unwrap();
                let replacement = context.token_node_id(parsed.nodes[0]).unwrap();
                assert_eq!(replacement.index(), stale.unwrap().index());
                assert_ne!(replacement, stale.unwrap());
                assert!(!context.put_related_token(first, "stale", stale.unwrap()));
                assert!(context.put_token_extra(first, "label", decode(c[6])));
                assert_eq!(
                    retained.token_info(first).unwrap().extra("label"),
                    Some(decode(c[5]).as_str())
                );
                assert_eq!(retained.token_text(second), Some(decode(c[4]).as_str()));
                assert_eq!(retained.token_info(second).unwrap().kind, kind(c[9]));
                assert_eq!(
                    retained.nodes[second.index()].span,
                    Span {
                        start: c[7].parse().unwrap(),
                        end: c[7].parse().unwrap()
                    }
                );
            }
            assert_eq!(
                retained.token_info(first).unwrap().related("repair"),
                Some(second)
            );
            let mut other = ParseContext::new("a");
            let own = other
                .add_token_node(
                    0,
                    TokenKind::Consumed,
                    TokenSource::Input(Span { start: 0, end: 1 }),
                )
                .unwrap();
            assert!(!other.put_related_token(own, "foreign", first));
            assert!(!other.put_token_extra(first, "foreign", "x"));
            assert!(other
                .add_token_node(
                    0,
                    TokenKind::Consumed,
                    TokenSource::Generated {
                        anchor: 0,
                        text: "x".into()
                    }
                )
                .is_none());
            assert!(other
                .add_token_node(
                    0,
                    kind(c[9]),
                    TokenSource::Generated {
                        anchor: 2,
                        text: "x".into()
                    }
                )
                .is_none());
            assert!(other
                .add_token_node(
                    0,
                    TokenKind::Consumed,
                    TokenSource::Input(Span { start: 0, end: 2 })
                )
                .is_none());
            // Immutable snapshots keep their historical Send + Sync contract.
            fn send_sync<T: Send + Sync>() {}
            send_sync::<unlaxer_runtime::Tree>();
        }
    }
}

fn short(context: &mut ParseContext<'_>) -> ParseResult {
    let first = context.token_node_id(0).unwrap();
    context.put_token_extra(first, "winner", "short");
    context.parse_token(&word("b"), TokenKind::Consumed, false)
}
fn long(context: &mut ParseContext<'_>) -> ParseResult {
    let first = context.token_node_id(0).unwrap();
    context.put_token_extra(first, "winner", "long");
    let parsed = context.parse_token(&word("bc"), TokenKind::Consumed, false)?;
    let related = context.token_node_id(parsed.nodes[0]).unwrap();
    context.put_related_token(first, "winner", related);
    Ok(parsed)
}

#[test]
fn choice_and_lookahead_restore_existing_metadata_and_longest_choice_commits_winner() {
    let mut context = ParseContext::new("abc");
    let first = context
        .parse_token(&word("a"), TokenKind::Consumed, false)
        .unwrap();
    let first = context.token_node_id(first.nodes[0]).unwrap();
    context.put_token_extra(first, "winner", "baseline");
    context.parse(&Expr::Custom(long).ahead()).unwrap();
    assert_eq!(
        context.token_info(first).unwrap().extra("winner"),
        Some("baseline")
    );
    assert_eq!(context.token_info(first).unwrap().related("winner"), None);
    assert_eq!(context.position(), 1);
    let failed = Expr::Custom(long).then(Expr::literal("x"));
    context.parse(&failed.or(Expr::Custom(short))).unwrap();
    assert_eq!(
        context.token_info(first).unwrap().extra("winner"),
        Some("short")
    );
    // New independent parse for the maximal winner after all candidate rollbacks.
    let mut context = ParseContext::new("abc");
    context
        .parse_token(&word("a"), TokenKind::Consumed, false)
        .unwrap();
    let first = context.token_node_id(0).unwrap();
    context
        .parse(&Expr::longest_choice([
            Expr::Custom(short),
            Expr::Custom(long),
        ]))
        .unwrap();
    assert_eq!(context.position(), 3);
    assert_eq!(
        context.token_info(first).unwrap().extra("winner"),
        Some("long")
    );
    let related = context
        .token_info(first)
        .unwrap()
        .related("winner")
        .unwrap();
    assert_eq!(context.token_text(related), Some("bc"));
}

fn replayable(context: &mut ParseContext<'_>) -> ParseResult {
    let parsed = context.parse_token(&word("a"), TokenKind::Consumed, false)?;
    let id = context.token_node_id(parsed.nodes[0]).unwrap();
    context.put_token_extra(id, "label", "stable");
    Ok(parsed)
}

#[test]
fn full_input_deferred_retry_preserves_metadata_and_failure_category() {
    let grammar = [Rule {
        name: "root",
        expression: Expr::CustomWith {
            parser: replayable,
            reads_diagnostics: false,
            replayable: true,
        }
        .then(Expr::Eof),
    }];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let tree =
                unlaxer_runtime::parse_with_options(&grammar, 0, false, "a", options).unwrap();
            let id = tree.token_node_id(0).unwrap();
            assert_eq!(tree.token_info(id).unwrap().extra("label"), Some("stable"));
            // Explicit token is retained even though the generated root is a normal node.
            assert_eq!(tree.nodes[0].span, Span { start: 0, end: 1 });
            let error =
                unlaxer_runtime::parse_detailed_with_options(&grammar, 0, false, "ab", options)
                    .unwrap_err();
            assert_eq!(error.kind, "syntax");
            assert_eq!(error.offset, 1);
        }
    }
}
