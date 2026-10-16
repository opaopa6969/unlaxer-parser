use unlaxer_runtime::{
    share_grammar, Declaration, Diagnostics, Expr, Memoization, ParseContext, ParseError,
    ParseMatch, ParseOptions, ParseResult, Rule, RuleEffects, ScopeMode, Severity, Span,
};
#[derive(Clone)]
struct Case {
    id: String,
    actions: String,
    hits: Vec<usize>,
    counts: [usize; 4],
    enabled: bool,
}
fn abort() -> ParseError {
    ParseError {
        offset: 0,
        expected: vec![],
    }
}
fn actions(
    c: &mut ParseContext<'_>,
    input: &[u8],
    at: &mut usize,
    case: &Case,
    failures: &mut usize,
) {
    while *at < input.len() {
        let op = input[*at];
        *at += 1;
        match op {
            b')' | b']' => return,
            b'(' | b'[' => {
                let version = c.memoization_state_version();
                let before = c.scopes().clone();
                let result: Result<(), ParseError> = c.transaction(|c| {
                    actions(c, input, at, case, failures);
                    if op == b'(' {
                        Err(abort())
                    } else {
                        Ok(())
                    }
                });
                assert_eq!(result.is_err(), op == b'(');
                if op == b'(' {
                    assert_eq!(c.memoization_state_version(), version);
                    assert_eq!(c.scopes(), &before);
                }
            }
            b'F' => {
                let version = c.memoization_state_version();
                let before = c.scopes().clone();
                let before_cst = format!("{:?}", c.tree(0).map(|tree| tree.nodes));
                let failure = c.parse(&Expr::Rule(1)).unwrap_err();
                assert_eq!(
                    failure.offset,
                    if c.options().diagnostics == Diagnostics::DetailedOnFailure {
                        0
                    } else {
                        1
                    },
                    "{} failure span",
                    case.id
                );
                assert_eq!((c.position(), c.matched_position()), (0, 0));
                assert_eq!(
                    c.memoization_state_version(),
                    version,
                    "{} failed effects rollback",
                    case.id
                );
                assert_eq!(c.scopes(), &before, "{} scope/semantic events", case.id);
                assert_eq!(c.captured("name"), None);
                assert_eq!(
                    format!("{:?}", c.tree(0).map(|tree| tree.nodes)),
                    before_cst,
                    "{} failed rule must not commit CST",
                    case.id
                );
                assert_eq!(
                    c.memoized_failure_hits(),
                    if case.enabled {
                        case.hits[*failures]
                    } else {
                        0
                    },
                    "{} F{}",
                    case.id,
                    failures
                );
                assert_eq!(
                    c.memoized_success_hits(),
                    0,
                    "stateful success is not replayed"
                );
                *failures += 1;
            }
            b'E' => c.scopes_mut().enter(),
            b'L' => c.scopes_mut().leave(),
            b'D' => c.scopes_mut().declare("😀", 0),
            b'Z' => c.scopes_mut().declare("", 0),
            b'R' => c.scopes_mut().add_reference("😀", 1, 1),
            b'A' => c
                .scopes_mut()
                .add_diagnostic("kept", 1, 1, Severity::Warning),
            b'C' => c.scopes_mut().clear_diagnostics(),
            b'B' | b'S' => {
                let binding = Expr::literal("😀").ahead().capture_owned("owned");
                c.parse(&if op == b'S' {
                    binding.capture_scope()
                } else {
                    binding
                })
                .unwrap();
                assert_eq!((c.position(), c.matched_position()), (0, 0));
                assert_eq!(
                    c.captured_text("owned"),
                    if op == b'S' { None } else { Some("") }
                );
            }
            b'M' => {
                c.set_state("user", 99_u32);
                c.mark_memoization_state_changed();
            }
            _ => panic!("unknown operation {}", op as char),
        }
    }
}
fn driver(c: &mut ParseContext<'_>) -> ParseResult {
    let case = c.state::<Case>("case").unwrap().clone();
    let mut at = 0;
    let mut failures = 0;
    actions(c, case.actions.as_bytes(), &mut at, &case, &mut failures);
    assert_eq!(failures, case.hits.len());
    assert_eq!(c.state::<u32>("user"), Some(&7));
    assert_eq!(c.captured_text("owned"), None);
    let s = c.scopes();
    assert_eq!(
        [
            s.current_scope_depth(),
            s.all_declarations().len(),
            s.all_references().len(),
            s.diagnostics().len()
        ],
        case.counts,
        "{} final scope",
        case.id
    );
    for d in s.diagnostics() {
        assert_eq!((&*d.message, d.offset, d.length), ("kept", 1, 1));
    }
    for r in s.all_references() {
        assert_eq!((&*r.name, r.offset, r.length), ("😀", 1, 1));
    }
    Ok(ParseMatch::empty(Span { start: 0, end: 0 }))
}
#[test]
fn shared_epochs_rollback_and_write_only_diagnostics_have_real_failure_hits() {
    let mut count = 0;
    for row in include_str!("../../../conformance/stateful-failure-memo/cases.tsv")
        .lines()
        .filter(|r| !r.starts_with('#') && !r.is_empty())
    {
        let p: Vec<_> = row.split('\t').collect();
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
                Diagnostics::Auto,
            ] {
                let case = Case {
                    id: p[0].into(),
                    actions: p[1].into(),
                    hits: p[2].split(',').map(|x| x.parse().unwrap()).collect(),
                    counts: std::array::from_fn(|i| p[i + 3].parse().unwrap()),
                    enabled: memo == Memoization::SafeFailures,
                };
                let effects = Expr::RuleEffects {
                    child: Box::new(Expr::literal("😀").capture("name")),
                    effects: RuleEffects {
                        scope_mode: Some(ScopeMode::Lexical),
                        declares: Some(Declaration {
                            symbol_capture: "name",
                            description: None,
                        }),
                        backref: Some("name"),
                    },
                };
                let grammar = share_grammar(vec![
                    Rule {
                        name: "driver",
                        expression: Expr::Custom(driver),
                    },
                    Rule {
                        name: "target",
                        expression: effects.then(Expr::literal("!")),
                    },
                ]);
                let mut c = ParseContext::with_options(
                    "😀?",
                    ParseOptions {
                        memoization: memo,
                        diagnostics,
                        ..ParseOptions::default()
                    },
                );
                c.set_state("case", case);
                c.set_state("user", 7_u32);
                c.parse_shared_grammar(&grammar, 0, false).unwrap();
                println!("STATEFUL\t{}\t{memo:?}\t{diagnostics:?}", p[0]);
                count += 1;
            }
        }
    }
    assert_eq!(count, 102);
}

static LOSER_EPOCH: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
fn winning_candidate(c: &mut ParseContext<'_>) -> ParseResult {
    c.mark_memoization_state_changed();
    c.parse(&Expr::literal("ab"))
}
fn losing_candidate(c: &mut ParseContext<'_>) -> ParseResult {
    c.mark_memoization_state_changed();
    c.mark_memoization_state_changed();
    LOSER_EPOCH.store(
        c.memoization_state_version().unwrap(),
        std::sync::atomic::Ordering::Relaxed,
    );
    c.parse(&Expr::literal("a"))
}
#[test]
fn longest_winner_keeps_allocator_epochs_from_discarded_candidates() {
    let mut c = ParseContext::new("ab");
    c.parse(&Expr::LongestChoice(vec![
        Expr::Custom(winning_candidate),
        Expr::Custom(losing_candidate),
    ]))
    .unwrap();
    assert_eq!(c.position(), 2);
    let winner = c.memoization_state_version().unwrap();
    let loser = LOSER_EPOCH.load(std::sync::atomic::Ordering::Relaxed);
    assert!(winner < loser);
    c.mark_memoization_state_changed();
    assert!(
        c.memoization_state_version().unwrap() > loser,
        "winner restore must not rewind allocator"
    );
}
