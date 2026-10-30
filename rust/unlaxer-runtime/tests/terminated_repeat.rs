//! Java Occurs terminator oracle shared with `TerminatedRepeatParityTest` (#846).
use std::cell::Cell;
use std::fmt::Write;
use unlaxer_runtime::{
    editor_cst::literal_completions,
    grammar_allows_deferred_diagnostics,
    incremental::grammar_is_snapshot_cacheable,
    names, parse_detailed_shared_with_options, set_candidate_exclusion_for_current_thread,
    share_grammar,
    token::{TokenKind, TokenSource},
    token_trace::Limits,
    CandidateExclusion, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
    Parser, RaisedKind, Rule, SharedGrammar,
};

const CASES: &str = include_str!("../../../conformance/terminated-repeat/cases.tsv");
const STATE: &str = include_str!("../../../conformance/terminated-repeat/state.tsv");
const WRAPPERS: &str = include_str!("../../../conformance/terminated-repeat/wrappers.tsv");
const HOLDERS: &str = include_str!("../../../conformance/terminated-repeat/holders.tsv");
const RAISED_STATE: &str = include_str!("../../../conformance/terminated-repeat/raised-state.tsv");

fn rows(corpus: &str) -> Vec<Vec<&str>> {
    corpus
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
        .map(|line| line.split('\t').collect())
        .collect()
}
fn decode(hex: &str) -> String {
    if hex == "~" {
        return String::new();
    }
    let bytes = (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
        .collect();
    String::from_utf8(bytes).unwrap()
}
fn encode(text: &str) -> String {
    if text.is_empty() {
        "~".into()
    } else {
        text.bytes().fold(String::new(), |mut encoded, byte| {
            write!(encoded, "{byte:02x}").unwrap();
            encoded
        })
    }
}
fn kind(name: &str) -> TokenKind {
    match name {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("{name}"),
    }
}
fn kind_name(kind: TokenKind) -> &'static str {
    match kind {
        TokenKind::Consumed => "consumed",
        TokenKind::MatchOnly => "matchOnly",
        TokenKind::VirtualConsumed => "virtualTokenConsumed",
        TokenKind::VirtualMatchOnly => "virtualTokenMatchOnly",
    }
}
fn w(text: &'static str) -> Expr {
    Expr::literal(text)
}
fn semicolons() -> Expr {
    w("a").repeat_java_until(w(";"), 0, None)
}
/// The Rust port of each Java construction in the README table.
fn parser(scenario: &str) -> Expr {
    match scenario {
        "zom-word" | "lazy-zom" => semicolons(),
        "zom-match" => w("a").repeat_java_until(w(";").match_only(), 0, None),
        "oom-word" | "lazy-oom" => w("a").repeat_java_until(w(";"), 1, None),
        "opt-word" | "zoo-word" | "lazy-opt" | "lazy-zoo" => {
            w("a").repeat_java_until(w(";"), 0, Some(1))
        }
        "rep-2-3" | "lazy-rep" => w("a").repeat_java_until(w(";"), 2, Some(3)),
        "chain-term" => w("a").repeat_java_until(Expr::sequence([w(";"), w("x")]), 0, None),
        "choice-term" => {
            w("a").repeat_java_until(Expr::choice([w(";"), w("\r\n"), w("\n")]), 0, None)
        }
        "nullable-term" => w("a").repeat_java_until(w("z").optional_java(), 0, None),
        "nullable-term-oom" => w("a").repeat_java_until(w("z").optional_java(), 1, None),
        "nullable-body" => w("a").optional_java().repeat_java_until(w(";"), 0, None),
        "eof-term" => w("a").repeat_java_until(Expr::Eof, 0, None),
        "empty-term" => w("a").repeat_java_until(Expr::JavaEmpty, 0, None),
        "until" => Expr::Any.repeat_java_until(
            Expr::choice([w(";").match_only(), w("\r\n").match_only()]),
            0,
            None,
        ),
        "until-consumed" => Expr::Any.repeat_java_until(Expr::choice([w(";"), w("\r\n")]), 0, None),
        "until-parser" => Expr::Any.repeat_java_until(w(";").match_only(), 0, None),
        "until-parser-consumed" => Expr::Any.repeat_java_until(w(";"), 0, None),
        "until-named" => Expr::Any.repeat_java_until(Expr::choice([w(";").match_only()]), 0, None),
        "jdk-until" => Expr::JavaUntil(";"),
        "reuse" => Expr::sequence([semicolons(), semicolons()]),
        "retry" => Expr::choice([
            Expr::sequence([semicolons(), w("x")]),
            Expr::sequence([semicolons(), w("y")]),
        ]),
        "lookahead" => semicolons().match_only(),
        "nested" => semicolons().repeat_java_until(w("."), 0, None),
        "bounded-max0" => w("a").repeat_java_until(w(";"), 0, Some(0)),
        "bounded-invalid" => w("a").repeat_java_until(w(";"), 2, Some(1)),
        _ => panic!("{scenario}"),
    }
}
fn signature(context: &mut ParseContext<'_>, index: usize) -> String {
    let id = context.token_node_id(index).unwrap();
    let info = context.token_info(id).unwrap();
    let (start, length) = match &info.source {
        TokenSource::Input(span) | TokenSource::Projected { span, .. } => {
            (span.start, span.end - span.start)
        }
        TokenSource::Generated { anchor, text } => (*anchor, text.chars().count()),
    };
    let kind = kind_name(info.kind);
    format!(
        "{kind}@{start}:{length}:{}",
        encode(context.token_text(id).unwrap())
    )
}
fn tree(context: &mut ParseContext<'_>, index: usize) -> String {
    let id = context.token_node_id(index).unwrap();
    let word = context.token_info(id).unwrap().parser_label() == Some("WordParser");
    let children = context.node(index).unwrap().children.clone();
    let own = signature(context, index);
    if children.is_empty() || word {
        return own;
    }
    let children = children
        .iter()
        .map(|&child| tree(context, child))
        .collect::<Vec<_>>();
    format!("{own}[{}]", children.join(","))
}
fn joined(values: Vec<String>) -> String {
    if values.is_empty() {
        "~".into()
    } else {
        values.join(";")
    }
}
const MODES: [(Memoization, Diagnostics); 6] = [
    (Memoization::Off, Diagnostics::Auto),
    (Memoization::Off, Diagnostics::Detailed),
    (Memoization::Off, Diagnostics::DetailedOnFailure),
    (Memoization::SafeFailures, Diagnostics::Auto),
    (Memoization::SafeFailures, Diagnostics::Detailed),
    (Memoization::SafeFailures, Diagnostics::DetailedOnFailure),
];

/// One Classic parse of a corpus row; the column order of `cases.tsv` from `success`.
fn observe(expression: &Expr, row: &[&str], options: ParseOptions) -> Vec<String> {
    let input = decode(row[1]);
    let mut context = ParseContext::with_options(&input, options);
    // Prior roots use independent cursors, as in the token-propagation oracle.
    context.set_reset_matched_with_consumed(false);
    let mut committed = Vec::new();
    if row[4] != "~" {
        let prior: &'static str = Box::leak(decode(row[4]).into_boxed_str());
        committed.extend(
            context
                .parse_with_token_mode(&w(prior), TokenKind::MatchOnly, false)
                .unwrap()
                .nodes,
        );
    }
    context.set_reset_matched_with_consumed(row[5] == "true");
    let parsed = if row[6] == "default" {
        // Java parse(context) is consumed and not inverted. WildCardStringTerminatorParser
        // also disables the caller's matched-cursor reset before it begins.
        if row[0].contains("until") {
            context.set_reset_matched_with_consumed(false);
        }
        context.parse_with_token_mode(expression, TokenKind::Consumed, false)
    } else {
        context.parse_with_token_mode(expression, kind(row[2]), row[3] == "true")
    };
    let failure = context.failure();
    let mut values = vec![
        parsed.is_ok().to_string(),
        context.position().to_string(),
        context.matched_position().to_string(),
        failure.offset.to_string(),
        (!failure.expected.is_empty()).to_string(),
    ];
    let mut originals = Vec::new();
    if let Ok(parsed) = &parsed {
        for &node in &parsed.nodes {
            let id = context.token_node_id(node).unwrap();
            match context.token_info(id).unwrap().original_tokens() {
                Some(raw) => originals.extend(raw.to_vec()),
                None => originals.push(node),
            }
        }
        committed.extend(parsed.nodes.iter().copied());
    }
    let originals = originals
        .iter()
        .map(|&node| signature(&mut context, node))
        .collect();
    values.push(joined(originals));
    let committed = committed
        .iter()
        .map(|&node| tree(&mut context, node))
        .collect();
    values.push(joined(committed));
    // Rust has no token stack; every frame is closed when the call returns.
    values.push("1".into());
    values.push(context.reset_matched_with_consumed().to_string());
    values
}

#[test]
fn shared_terminated_repeat_oracle_in_every_memo_and_diagnostics_mode() {
    let rows = rows(CASES);
    assert_eq!(rows.len(), 732);
    // One immutable expression per scenario is reused by every row and mode.
    let mut parsers = std::collections::HashMap::new();
    let mut failures = Vec::new();
    for (memo, diagnostics) in MODES {
        for row in &rows {
            assert_eq!(row.len(), 16);
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let expression = parsers.entry(row[0]).or_insert_with(|| parser(row[0]));
            let actual = observe(expression, row, options);
            let mut expected = row[7..].iter().map(|v| v.to_string()).collect::<Vec<_>>();
            if diagnostics == Diagnostics::DetailedOnFailure {
                expected[3] = "0".into();
                expected[4] = "false".into();
            }
            if actual != expected {
                failures.push(format!(
                    "{:?}/{memo:?}/{diagnostics:?}\n expected {expected:?}\n actual   {actual:?}",
                    &row[..7]
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn ordinary_and_grammar_entries_follow_the_consumed_rows() {
    // The plain entry is Java's root `parse(context, consumed, false)` with reset enabled.
    let rows = rows(CASES);
    let mut checked = 0;
    for row in rows.iter().filter(|row| {
        row[2] == "consumed" && row[3] == "false" && row[4] == "~" && row[5] == "true"
    }) {
        if row[6] != "args" {
            continue;
        }
        let input = decode(row[1]);
        for (memo, diagnostics) in MODES {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options(&input, options);
            let direct = context.parse(&parser(row[0]));
            let direct = (
                direct.is_ok(),
                context.position(),
                context.matched_position(),
            );
            let mut grammar = ParseContext::with_options(&input, options);
            let rules = vec![Rule {
                name: "Root",
                expression: parser(row[0]),
            }];
            let shared = grammar.parse_grammar(rules, 0, false);
            let shared = (
                shared.is_ok(),
                grammar.position(),
                grammar.matched_position(),
            );
            let expected = (
                row[7] == "true",
                row[8].parse::<usize>().unwrap(),
                row[9].parse::<usize>().unwrap(),
            );
            assert_eq!(
                direct,
                expected,
                "direct {:?} {memo:?} {diagnostics:?}",
                &row[..7]
            );
            assert_eq!(
                shared,
                expected,
                "grammar {:?} {memo:?} {diagnostics:?}",
                &row[..7]
            );
            // The rule is never memoized: no safety review covers the terminator (#184).
            assert_eq!(grammar.memoized_failure_hits(), 0);
            assert_eq!(grammar.memoized_success_hits(), 0);
        }
        checked += 1;
    }
    assert_eq!(checked, 69);
}

thread_local! { static CALLS: Cell<usize> = const { Cell::new(0) }; }
fn mutating_semicolon(context: &mut ParseContext<'_>) -> ParseResult {
    CALLS.with(|calls| calls.set(calls.get() + 1));
    context.set_state("value", 99u32);
    context.parse(&Expr::Literal(";"))
}
fn state_parser(scenario: &str) -> Expr {
    let terminator = Expr::Custom(mutating_semicolon).capture_owned("t");
    let repeat = if scenario == "chain-partial" {
        w("a").repeat_java_until(Expr::sequence([terminator, w("x")]), 0, None)
    } else {
        w("a").repeat_java_until(terminator, 0, None)
    };
    match scenario {
        "plain" | "chain-partial" => repeat,
        "outer-fail" => Expr::sequence([repeat, w("z")]),
        "retry" => Expr::choice([
            Expr::sequence([repeat.clone(), w("x")]),
            Expr::sequence([repeat, w("y")]),
        ]),
        "lookahead" => repeat.match_only(),
        "reuse" => Expr::sequence([repeat.clone(), repeat]),
        _ => panic!("{scenario}"),
    }
}

#[test]
fn shared_terminator_state_capture_and_rollback_oracle() {
    let rows = rows(STATE);
    assert_eq!(rows.len(), 24);
    for (memo, diagnostics) in MODES {
        for row in &rows {
            let input = decode(row[1]);
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options(&input, options);
            context.set_state("value", 7u32);
            CALLS.with(|calls| calls.set(0));
            let result = context.parse_with_token_mode(&state_parser(row[0]), kind(row[2]), false);
            let capture = match context.capture_sources("t").last() {
                Some(TokenSource::Input(span)) => {
                    format!("{}:{}", span.start, encode(context.text(*span).unwrap()))
                }
                Some(other) => panic!("{other:?}"),
                None => "~".into(),
            };
            let deferred = diagnostics == Diagnostics::DetailedOnFailure;
            let actual = [
                result.is_ok().to_string(),
                context.position().to_string(),
                context.matched_position().to_string(),
                context.state::<u32>("value").unwrap().to_string(),
                capture,
                CALLS.with(Cell::get).to_string(),
                context.failure().offset.to_string(),
            ];
            let mut expected = row[3..].iter().map(|v| v.to_string()).collect::<Vec<_>>();
            if deferred {
                expected[6] = "0".into();
            }
            assert_eq!(
                actual.to_vec(),
                expected,
                "{row:?} {memo:?} {diagnostics:?}"
            );
        }
    }
}

#[test]
fn analyses_visit_the_terminator_without_approving_caches() {
    let repeat = w("a").repeat_java_until(w(";").match_only(), 0, None);
    let grammar = share_grammar(vec![Rule {
        name: "Root",
        expression: repeat.clone(),
    }]);
    // A terminator literal is a completion/sync candidate like any other literal.
    let plain = share_grammar(vec![Rule {
        name: "Root",
        expression: semicolons(),
    }]);
    assert_eq!(literal_completions(&plain), [";", "a"]);
    // Deferred diagnostics follow both operands: a custom terminator disables them.
    assert!(grammar_allows_deferred_diagnostics(&grammar));
    let custom = vec![Rule {
        name: "Root",
        expression: w("a").repeat_java_until(Expr::Custom(mutating_semicolon), 0, None),
    }];
    assert!(!grammar_allows_deferred_diagnostics(&custom));
    // A rule re-entered at the same position is never replayed from either memo.
    for (rule, input) in [
        (semicolons(), "a;y"),
        (w("a").repeat_java_until(w(";"), 2, None), "a;y"),
    ] {
        let retried = vec![
            Rule {
                name: "Root",
                expression: Expr::choice([
                    Expr::sequence([Expr::Rule(1), w("x")]),
                    Expr::sequence([Expr::Rule(1), w(";y")]),
                    Expr::sequence([Expr::Rule(1), w("y")]),
                ]),
            },
            Rule {
                name: "Terminated",
                expression: rule,
            },
        ];
        let options = ParseOptions::with_memoization(Memoization::SafeFailures)
            .with_diagnostics(Diagnostics::Detailed);
        let mut context = ParseContext::with_options(input, options);
        let _ = context.parse_grammar(retried, 0, false);
        assert_eq!(context.memoized_failure_hits(), 0, "{input}");
        assert_eq!(context.memoized_success_hits(), 0, "{input}");
    }
    // New expression kinds need an explicit snapshot-cache review (#184).
    assert!(!grammar_is_snapshot_cacheable(&grammar));
    // A MatchOnly terminator requires Classic mode, like a MatchOnly child.
    let mut context = ParseContext::new("a;");
    let parsed = context.parse(&repeat).unwrap();
    assert!(context.token_node_id(parsed.nodes[0]).is_some());
    let id = context.token_node_id(parsed.nodes[0]).unwrap();
    assert_eq!(
        context.token_info(id).unwrap().parser_label(),
        Some("ZeroOrMore")
    );
    assert_eq!((context.position(), context.matched_position()), (1, 2));
}

#[test]
fn first_set_exclusion_keeps_terminator_starts_and_every_outcome() {
    // Java FirstSets: body, then terminator. Skipping must never change an outcome, also
    // for a raising terminator where the body cannot start (#700); audit panics otherwise.
    let grammar = |alternatives: Vec<Expr>| {
        share_grammar(vec![Rule {
            name: "Root",
            expression: Expr::sequence([Expr::choice(alternatives), Expr::Eof]),
        }])
    };
    let grammars = [
        grammar(vec![
            w("a").repeat_java_until(w(";"), 1, None),
            w("b").repeat_java_until(w("!"), 2, Some(1)),
            w("c"),
            w("b").repeat_java_until(w(";"), 0, Some(0)),
            w("a").repeat_java_until(w("z").optional_java(), 0, None),
        ]),
        grammar(vec![
            w("d").repeat_java_until(Expr::sequence([w(";"), w("!")]).commit(), 1, None),
            w(";x"),
        ]),
    ];
    let modes = [
        CandidateExclusion::Off,
        CandidateExclusion::On,
        CandidateExclusion::Audit,
    ];
    for grammar in &grammars {
        for input in [";", "a;", "aa", "c", "b", "!", "", "😀", ";x", ";!", "d;!"] {
            for (memo, diagnostics) in MODES {
                let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                let outcomes = modes.map(|mode| {
                    set_candidate_exclusion_for_current_thread(Some(mode));
                    format!(
                        "{:?}",
                        parse_detailed_shared_with_options(grammar, 0, false, input, options)
                    )
                });
                set_candidate_exclusion_for_current_thread(None);
                assert_eq!(outcomes[0], outcomes[1], "{input:?} {options:?}");
                assert_eq!(outcomes[0], outcomes[2], "{input:?} {options:?}");
            }
        }
    }
}

#[test]
fn deeply_nested_terminators_fail_safely_on_a_standard_stack() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            for memo in [Memoization::Off, Memoization::SafeFailures] {
                let mut expression = w("x");
                for _ in 0..1500 {
                    expression = w("y").repeat_java_until(expression, 0, None);
                }
                let mut context =
                    ParseContext::with_options("x", ParseOptions::with_memoization(memo));
                let error = context.parse(&expression).unwrap_err();
                assert_eq!(error.expected, ["expression nesting below 1024"]);
                assert_eq!((context.position(), context.matched_position()), (0, 0));
                std::mem::forget(expression);
            }
        })
        .unwrap()
        .join()
        .unwrap();
}

/// A two-rule grammar whose root is the terminated repetition and whose rule 1 is the
/// terminator: the generated Java rule shape (`LazyChain`/`LazyChoice` + `GrammarRule`).
struct Grammar(SharedGrammar);
impl Parser for Grammar {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_shared_grammar(&self.0, 0, false)
    }
}
enum Wrapper {
    Expression(Expr),
    Grammar(Grammar),
}
fn semi_cut() -> Expr {
    w("a").repeat_java_until(Expr::sequence([w(";"), w("x").commit()]), 0, None)
}
fn rule_terminator(rule: Expr) -> Wrapper {
    Wrapper::Grammar(Grammar(share_grammar(vec![
        Rule {
            name: "Root",
            expression: w("a").repeat_java_until(Expr::Rule(1), 0, None),
        },
        Rule {
            name: "R1",
            expression: rule,
        },
    ])))
}
/// The Rust port of each Java construction of `wrappers.tsv` (see README).
fn wrapper_parser(scenario: &str) -> Wrapper {
    let until =
        |terminator: Expr| Wrapper::Expression(w("a").repeat_java_until(terminator, 0, None));
    let semi_x = || Expr::sequence([w(";"), w("x")]);
    match scenario {
        "commit-term" => until(semi_x().commit()),
        "commit-word-term" => until(w(";").commit()),
        "label-term" => until(semi_x().label("L")),
        "label-word-term" => until(w(";").label("L")),
        "stopconsume-term" => until(w(";").stop_consume()),
        "stopinvert-term" => until(w(";").stop_invert()),
        "invert-term" => until(w(";").invert_match()),
        "commit-inner-term" => Wrapper::Expression(semi_cut()),
        "label-inner-term" => until(Expr::sequence([w(";"), w("x").label("L")])),
        "commit-body" => Wrapper::Expression(
            Expr::sequence([w("a"), w("b").commit()]).repeat_java_until(w(";"), 0, None),
        ),
        "raise-choice" => Wrapper::Expression(Expr::choice([
            Expr::sequence([semi_cut(), w("!")]),
            w("a;y"),
        ])),
        "raise-matchonly" => Wrapper::Expression(semi_cut().match_only()),
        "capture-term" => until(semi_x().capture_owned("t")),
        "matchonly-chain-term" => until(semi_x().match_only()),
        "until-term" => until(Expr::JavaUntil(";")),
        "zom-term" => until(w(";").repeat_java(0, None)),
        "rule-chain-term" => rule_terminator(semi_x()),
        "rule-choice-term" => rule_terminator(Expr::choice([w(";"), w("\n")])),
        "not-term" => until(Expr::JavaLookahead {
            pattern: ";",
            positive: false,
        }),
        "lookahead-term" => until(Expr::JavaLookahead {
            pattern: ";",
            positive: true,
        }),
        "peg-ahead-term" => until(w(";").ahead()),
        "peg-not-term" => until(w(";").not_ahead()),
        "name-scope-term" => until(Expr::NameResolutionScope {
            child: Box::new(w(";")),
            requirements: Vec::<names::Requirement>::new(),
        }),
        _ => panic!("{scenario}"),
    }
}
fn raised(context: &ParseContext<'_>) -> String {
    match context.raised_failure() {
        None => "~".into(),
        Some(raised) => match raised.kind {
            RaisedKind::Commit => format!("commit@{}", raised.offset),
            RaisedKind::Label => format!("label:{}@{}", raised.label.unwrap(), raised.offset),
        },
    }
}

/// One Classic parse of a `wrappers.tsv` row; the column order from `success`.
fn observe_wrapper(parser: &Wrapper, row: &[&str], options: ParseOptions) -> Vec<String> {
    let input = decode(row[1]);
    let options = options.with_token_trace(Limits::default());
    let mut context = ParseContext::with_options(&input, options);
    context.set_reset_matched_with_consumed(false);
    let mut committed = Vec::new();
    if row[4] != "~" {
        let prior: &'static str = Box::leak(decode(row[4]).into_boxed_str());
        committed.extend(
            context
                .parse_with_token_mode(&w(prior), TokenKind::MatchOnly, false)
                .unwrap()
                .nodes,
        );
    }
    context.set_reset_matched_with_consumed(row[5] == "true");
    let (kind, invert) = (kind(row[2]), row[3] == "true");
    let parsed = match parser {
        Wrapper::Expression(expression) => context.parse_with_token_mode(expression, kind, invert),
        // The Rust root rule node is the existing construction boundary (#845): the Java
        // root is the repetition itself, so the comparison starts below the rule node.
        Wrapper::Grammar(grammar) => {
            context
                .parse_with_token_mode(grammar, kind, invert)
                .map(|mut parsed| {
                    parsed.nodes = parsed
                        .nodes
                        .iter()
                        .flat_map(|&node| context.node(node).unwrap().children.clone())
                        .collect();
                    parsed
                })
        }
    };
    let failure = context.failure();
    let mut values = vec![
        parsed.is_ok().to_string(),
        context.position().to_string(),
        context.matched_position().to_string(),
        failure.offset.to_string(),
        (!failure.expected.is_empty()).to_string(),
        raised(&context),
    ];
    let mut originals = Vec::new();
    if let Ok(parsed) = &parsed {
        for &node in &parsed.nodes {
            let id = context.token_node_id(node).unwrap();
            match context.token_info(id).unwrap().original_tokens() {
                Some(raw) => originals.extend(raw.to_vec()),
                None => originals.push(node),
            }
        }
        committed.extend(parsed.nodes.iter().copied());
    }
    let originals = originals
        .iter()
        .map(|&node| signature(&mut context, node))
        .collect();
    values.push(joined(originals));
    let committed = committed
        .iter()
        .map(|&node| tree(&mut context, node))
        .collect();
    values.push(joined(committed));
    let leaves = match context.token_trace() {
        Some(Ok(leaves)) => leaves
            .iter()
            .map(|leaf| {
                let span = leaf.span();
                format!(
                    "{},{},{},{}",
                    kind_name(leaf.kind),
                    span.start,
                    span.end,
                    encode(leaf.text(&input).unwrap())
                )
            })
            .collect(),
        other => panic!("{other:?}"),
    };
    values.push(joined(leaves));
    values.push("1".into());
    values.push(context.reset_matched_with_consumed().to_string());
    values
}

#[test]
fn shared_wrapper_rule_and_lookahead_terminator_oracle_in_every_mode() {
    let rows = rows(WRAPPERS);
    assert_eq!(rows.len(), 2342);
    let mut parsers = std::collections::HashMap::new();
    let mut failures = Vec::new();
    for (memo, diagnostics) in MODES {
        for row in &rows {
            assert_eq!(row.len(), 17);
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let parser = parsers
                .entry(row[0])
                .or_insert_with(|| wrapper_parser(row[0]));
            let actual = observe_wrapper(parser, row, options);
            let mut expected = row[6..].iter().map(|v| v.to_string()).collect::<Vec<_>>();
            // A raised failure (#700) keeps its diagnostics under DetailedOnFailure.
            if diagnostics == Diagnostics::DetailedOnFailure && row[11] == "~" {
                expected[3] = "0".into();
                expected[4] = "false".into();
            }
            if actual != expected {
                failures.push(format!(
                    "{:?}/{memo:?}/{diagnostics:?}\n expected {expected:?}\n actual   {actual:?}",
                    &row[..6]
                ));
            }
        }
    }
    assert!(
        failures.is_empty(),
        "{} failures\n{}",
        failures.len(),
        failures.join("\n")
    );
    assert_eq!(parsers.len(), 23);
}

/// The Rust recipes of the Java ParserWrapper / ParserHolder / MappedSingleCharacterParserHolder
/// constructions of `holders.tsv` (see README). Every recipe must reproduce every row.
fn holder_parsers(scenario: &str) -> Vec<Expr> {
    let until = |terminator: Expr| w("a").repeat_java_until(terminator, 0, None);
    let body = |inner: Expr| inner.repeat_java_until(w(";"), 0, None);
    let semi_x = || Expr::sequence([w(";"), w("x")]);
    let holder = |child: Expr| child.stop_consume().stop_invert();
    match scenario {
        "holder-word" => vec![
            until(holder(w(";"))),
            until(holder(Expr::CharRange(';', ';'))),
        ],
        "holder-chain" => vec![until(holder(semi_x())), until(semi_x())],
        "holder-body" => vec![
            body(holder(w("a"))),
            body(holder(Expr::CharRange('a', 'a'))),
        ],
        "holder-in-chain-term" => vec![
            until(Expr::sequence([holder(w(";")), w("x")])),
            until(semi_x()),
        ],
        "wrapper-matchonly" => vec![
            until(w(";").match_only()),
            until(Expr::JavaLookahead {
                pattern: ";",
                positive: true,
            }),
        ],
        "wrapper-matchonly-chain" => vec![until(semi_x().match_only())],
        "wrapper-matchonly-invert" => vec![until(w(";").invert_match().match_only())],
        "holder-of-matchonly" => vec![until(holder(w(";").match_only()))],
        "holder-of-matchonly-body" => vec![body(holder(w("a").match_only()))],
        _ => panic!("{scenario}"),
    }
}

#[test]
fn shared_parser_wrapper_and_holder_terminator_oracle_in_every_mode() {
    let rows = rows(HOLDERS);
    assert_eq!(rows.len(), 900);
    let mut parsers = std::collections::HashMap::new();
    let mut failures = Vec::new();
    for (memo, diagnostics) in MODES {
        for row in &rows {
            assert_eq!(row.len(), 17);
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut expected = row[6..].iter().map(|v| v.to_string()).collect::<Vec<_>>();
            if diagnostics == Diagnostics::DetailedOnFailure && row[11] == "~" {
                expected[3] = "0".into();
                expected[4] = "false".into();
            }
            let recipes = parsers.entry(row[0]).or_insert_with(|| {
                holder_parsers(row[0])
                    .into_iter()
                    .map(Wrapper::Expression)
                    .collect::<Vec<_>>()
            });
            for (index, parser) in recipes.iter().enumerate() {
                let actual = observe_wrapper(parser, row, options);
                if actual != expected {
                    failures.push(format!(
                        "{:?}#{index}/{memo:?}/{diagnostics:?}\n expected {expected:?}\n actual   {actual:?}",
                        &row[..6]
                    ));
                }
            }
        }
    }
    assert!(
        failures.is_empty(),
        "{} failures\n{}",
        failures.len(),
        failures.join("\n")
    );
    assert_eq!(parsers.len(), 9);
}

fn raised_state_parser(scenario: &str) -> Expr {
    let terminator = || Expr::Custom(mutating_semicolon).capture_owned("t");
    let cut = || w("a").repeat_java_until(Expr::sequence([terminator(), w("x").commit()]), 0, None);
    match scenario {
        "commit-chain" => {
            w("a").repeat_java_until(Expr::sequence([terminator(), w("x")]).commit(), 0, None)
        }
        "chain-commit" => cut(),
        "chain-label" => {
            w("a").repeat_java_until(Expr::sequence([terminator(), w("x").label("L")]), 0, None)
        }
        "choice-raise" => Expr::choice([Expr::sequence([cut(), w("z")]), w("a;y")]),
        "catch-label" => Expr::sequence([
            w("a").repeat_java_until(Expr::sequence([terminator(), w("x").label("L")]), 0, None),
            w("z"),
        ])
        .catch_labels(w("a;y"), ["L"]),
        "matchonly-raise" => cut().match_only(),
        "outer-fail" => Expr::sequence([cut(), w("z")]),
        _ => panic!("{scenario}"),
    }
}

#[test]
fn shared_raised_terminator_state_and_rollback_oracle() {
    let rows = rows(RAISED_STATE);
    assert_eq!(rows.len(), 112);
    for (memo, diagnostics) in MODES {
        for row in &rows {
            let input = decode(row[1]);
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options(&input, options);
            context.set_state("value", 7u32);
            CALLS.with(|calls| calls.set(0));
            let result =
                context.parse_with_token_mode(&raised_state_parser(row[0]), kind(row[2]), false);
            let capture = match context.capture_sources("t").last() {
                Some(TokenSource::Input(span)) => {
                    format!("{}:{}", span.start, encode(context.text(*span).unwrap()))
                }
                Some(other) => panic!("{other:?}"),
                None => "~".into(),
            };
            let actual = [
                result.is_ok().to_string(),
                context.position().to_string(),
                context.matched_position().to_string(),
                context.state::<u32>("value").unwrap().to_string(),
                capture,
                CALLS.with(Cell::get).to_string(),
                context.failure().offset.to_string(),
                raised(&context),
            ];
            let mut expected = row[3..].iter().map(|v| v.to_string()).collect::<Vec<_>>();
            if diagnostics == Diagnostics::DetailedOnFailure && row[10] == "~" {
                expected[6] = "0".into();
            }
            assert_eq!(
                actual.to_vec(),
                expected,
                "{row:?} {memo:?} {diagnostics:?}"
            );
        }
    }
}
