//! Public FIRST queries (#853) against the oracle shared with Java
//! `FirstSetParityTest`: `conformance/first-sets/cases.tsv`. Each row fixes the Java-shaped
//! `Display`, the flags, the trivia marker, and `may_start_with` / `may_start` for every ASCII
//! code point, end of input and representative non-ASCII (including non-BMP).
//! `accepts.tsv` checks that each expression here accepts and consumes like its Java pair.
use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::sync::atomic::{AtomicUsize, Ordering};

use unlaxer_runtime::{
    parse_shared, set_candidate_exclusion_for_current_thread, share_grammar, CandidateExclusion,
    Expr, FirstSet, FirstSets, ParseContext, ParseResult, Rule,
};

const CASES: &str = include_str!("../../../conformance/first-sets/cases.tsv");
const ACCEPTS: &str = include_str!("../../../conformance/first-sets/accepts.tsv");
const NON_ASCII: [char; 3] = ['\u{e9}', '\u{3042}', '\u{1f600}'];

static CALLBACKS: AtomicUsize = AtomicUsize::new(0);

fn custom(context: &mut ParseContext<'_>) -> ParseResult {
    CALLBACKS.fetch_add(1, Ordering::SeqCst);
    Err(context.error("custom"))
}

fn only_a(c: char) -> bool {
    CALLBACKS.fetch_add(1, Ordering::SeqCst);
    c == 'a'
}

fn rule(name: &'static str, expression: Expr) -> Rule {
    Rule { name, expression }
}

fn root(expression: Expr) -> FirstSet {
    let grammar = share_grammar(vec![rule("root", expression)]);
    FirstSets::of(&grammar).rule(0).unwrap()
}

fn lit(text: &'static str) -> Expr {
    Expr::literal(text)
}

/// The single-rule expression of a row, or `None` for the other rows. `only_a` is the
/// row's predicate, so parsing can avoid the query's callback counter.
fn expression(id: &str, only_a: fn(char) -> bool) -> Option<Expr> {
    Some(match id {
        "literal" => lit("ab"),
        "empty-word" => Expr::JavaEmptyWord,
        "non-bmp-literal" => lit("\u{1f600}x"),
        "choice" => Expr::choice([lit("a"), lit("b")]),
        "sequence" => Expr::sequence([lit("a").optional_java(), lit("b")]),
        "sequence-nullable" => {
            Expr::sequence([lit("a").optional_java(), lit("b").repeat_java(0, None)])
        }
        "optional" => lit("a").optional_java(),
        "zero-or-more" => lit("a").repeat_java(0, None),
        "one-or-more" => lit("a").repeat_java(1, None),
        "repeat-zero" => lit("a").repeat_java(0, Some(0)),
        "repeat-invalid" => lit("a").repeat_java(2, Some(1)),
        "eof" => Expr::Eof,
        "eof-then-literal" => Expr::sequence([Expr::Eof, lit("a")]),
        "zero-or-more-any" => Expr::Any.repeat_java(0, None),
        "custom" => Expr::Custom(custom),
        "literal-then-custom" => Expr::sequence([lit("a"), Expr::Custom(custom)]),
        "custom-then-literal" => Expr::sequence([Expr::Custom(custom), lit("a")]),
        "choice-with-custom" => Expr::choice([lit("a"), Expr::Custom(custom)]),
        "recursive-left" => Expr::choice([
            Expr::sequence([Expr::Rule(0), lit(",")]),
            lit("x").optional_java(),
        ]),
        "char-range" => Expr::CharRange('a', 'c'),
        "character-predicate" => Expr::character("a", only_a),
        // Java Classic `Not(WordParser)`, as generated for a UBNF negative lookahead.
        "not-lookahead" => Expr::JavaLookahead {
            pattern: "a",
            positive: false,
        },
        // Java `PegLookahead(WordParser, false)`.
        "peg-not-lookahead" => lit("a").not_ahead(),
        "case-insensitive" => Expr::case_insensitive("ab"),
        "empty-chain" => Expr::sequence([]),
        "empty-choice" => Expr::choice([]),
        _ => return None,
    })
}

fn build(id: &str) -> FirstSet {
    if let Some(expression) = expression(id, only_a) {
        return root(expression);
    }
    match id {
        "recursive-mutual" => {
            let grammar = share_grammar(vec![
                rule(
                    "a",
                    Expr::choice([Expr::sequence([Expr::Rule(1), lit("a")]), lit("x")]),
                ),
                rule(
                    "b",
                    Expr::choice([
                        Expr::sequence([Expr::Rule(0), lit("b")]),
                        lit("y").optional_java(),
                    ]),
                ),
            ]);
            FirstSets::of(&grammar).rule(0).unwrap()
        }
        "of-code-points" => FirstSet::of_code_points(['a', 'Z', '\u{1f600}']),
        "control" => FirstSet::of_code_points(['\0', ' ', '~', '\x7f']),
        "union" => FirstSet::of_code_points(['a']).union(FirstSet::EPSILON),
        "union-starts-into-nullable" => {
            FirstSet::EPSILON.union_starts(FirstSet::of_code_points(['b']))
        }
        "union-starts-drops-nullable" => {
            FirstSet::of_code_points(['a']).union_starts(FirstSet::EPSILON)
        }
        "unknown-union" => FirstSet::of_code_points(['a']).union(FirstSet::UNKNOWN),
        "unknown-union-starts" => FirstSet::of_code_points(['a']).union_starts(FirstSet::UNKNOWN),
        "nullable-non-ascii" => FirstSet::of_code_points(['a'])
            .as_nullable()
            .with_non_ascii(),
        "empty" => FirstSet::EMPTY,
        "epsilon" => FirstSet::EPSILON,
        "unknown" => FirstSet::UNKNOWN,
        _ => panic!("no Rust construction for {id}"),
    }
}

fn ascii(column: &str) -> [bool; 128] {
    let mut result = [column == "all"; 128];
    if column != "all" && column != "none" {
        for part in column.split(',') {
            let (from, to) = part.split_once('-').unwrap_or((part, part));
            let from = usize::from_str_radix(from, 16).unwrap();
            let to = usize::from_str_radix(to, 16).unwrap();
            result[from..=to].fill(true);
        }
    }
    result
}

fn flag(column: &str) -> bool {
    match column {
        "yes" => true,
        "no" => false,
        _ => panic!("flag {column}"),
    }
}

const TRIVIA: [char; 7] = [' ', '\t', '\n', '\r', '\u{b}', '\u{c}', '/'];

#[test]
fn public_queries_match_the_shared_oracle() {
    let mut hosts: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
    let mut checked = BTreeSet::new();
    let before = CALLBACKS.load(Ordering::SeqCst);
    for line in CASES.lines().filter(|line| !line.starts_with('#')) {
        let row: Vec<&str> = line.split('\t').collect();
        assert_eq!(row.len(), 9, "{line}");
        let (id, host) = (row[0], row[1]);
        assert!(["both", "java", "rust"].contains(&host), "{line}");
        assert!(hosts.entry(id).or_default().insert(host), "{line}");
        if host != "both" {
            assert!(
                !row[8].is_empty(),
                "a split row explains the difference: {line}"
            );
        }
        if host == "java" {
            continue;
        }
        assert!(checked.insert(id), "one Rust row per id: {line}");
        let set = build(id);
        let (non_ascii, nullable, unknown) = (flag(row[3]), flag(row[4]), flag(row[5]));
        let starts = ascii(row[2]);
        let (policy, always) = match row[6] {
            "none" => (false, false),
            "policy" => (true, false),
            "always" => (true, true),
            other => panic!("trivia {other} in {line}"),
        };
        let context = format!("{id} {set:?}");
        assert_eq!(set.to_string(), row[7], "{context}");
        assert_eq!(set.is_nullable(), nullable, "{context}");
        assert_eq!(set.is_unknown(), unknown, "{context}");
        assert_eq!(set.skips_trivia(false), always, "{context}");
        assert_eq!(set.skips_trivia(true), policy, "{context}");
        let open = unknown || nullable;
        for whitespace in [false, true] {
            let trivia = if whitespace { policy } else { always };
            for (code_point, &start) in starts.iter().enumerate() {
                let c = char::from(code_point as u8);
                let expected = open || start || (trivia && TRIVIA.contains(&c));
                let at = format!("{context} U+{code_point:04x} trivia={whitespace}");
                assert_eq!(set.may_start_with(Some(c), whitespace), expected, "{at}");
                // `may_start` is the parser's own check; `/` alone is not a comment there.
                let rest = format!("{c}x");
                let parser = open || start || (trivia && c != '/' && TRIVIA.contains(&c));
                assert_eq!(set.may_start(&rest, whitespace), parser, "{at}");
            }
            assert_eq!(set.may_start_with(None, whitespace), open, "{context} EOF");
            assert_eq!(set.may_start("", whitespace), open, "{context} EOF");
            for c in NON_ASCII {
                let expected = open || non_ascii;
                assert_eq!(
                    set.may_start_with(Some(c), whitespace),
                    expected,
                    "{context} {c}"
                );
                assert_eq!(set.may_start(&format!("{c}x"), whitespace), expected);
            }
            assert_eq!(
                set.may_start("// c\nb", whitespace),
                open || trivia || starts[47]
            );
        }
    }
    for (id, row_hosts) in &hosts {
        let split: BTreeSet<&str> = ["java", "rust"].into();
        assert!(
            *row_hosts == BTreeSet::from(["both"]) || *row_hosts == split,
            "{id} {row_hosts:?}"
        );
    }
    assert_eq!(hosts.keys().copied().collect::<BTreeSet<_>>(), checked);
    assert_eq!(
        CALLBACKS.load(Ordering::SeqCst),
        before,
        "the query never runs a custom parser or predicate"
    );
}

fn uncounted_only_a(c: char) -> bool {
    c == 'a'
}

const ACCEPT_INPUTS: [&str; 8] = ["", "a", "b", "ab", "abc", "\u{1f600}", " a", "\u{e9}"];

/// Consumed code points of a root parse of `expression` at the start of `input`, or `None`.
/// The parsers are deterministic, so at most one split lets `expression` then the rest of
/// `input` as a literal accept the whole input.
fn consumed(expression: &Expr, input: &str) -> Option<usize> {
    let mut boundaries: Vec<usize> = input.char_indices().map(|(at, _)| at).collect();
    boundaries.push(input.len());
    boundaries.into_iter().find_map(|at| {
        let rest: &'static str = Box::leak(input[at..].to_owned().into_boxed_str());
        let grammar = share_grammar(vec![rule(
            "probe",
            Expr::sequence([expression.clone(), lit(rest)]),
        )]);
        parse_shared(&grammar, 0, false, input)
            .is_ok()
            .then(|| input[..at].chars().count())
    })
}

#[test]
fn paired_expressions_accept_as_the_shared_oracle() {
    let mut rows = 0;
    for line in ACCEPTS.lines().filter(|line| !line.starts_with('#')) {
        let row: Vec<&str> = line.split('\t').collect();
        assert_eq!(row.len(), 1 + ACCEPT_INPUTS.len(), "{line}");
        let expression = expression(row[0], uncounted_only_a).unwrap();
        for (input, expected) in ACCEPT_INPUTS.iter().zip(&row[1..]) {
            let actual = consumed(&expression, input).map_or("fail".into(), |n| n.to_string());
            assert_eq!(actual, *expected, "{} on {input:?}", row[0]);
        }
        rows += 1;
    }
    assert_eq!(rows, 21);
}

#[test]
fn equality_includes_the_rust_only_trivia() {
    // Java has no trivia, so its equality and `toString` agree; Rust shows the same text but a
    // sequence that may skip trivia is a different value (the fixed point compares whole sets).
    let sequence = root(Expr::sequence([lit("a").optional_java(), lit("b")]));
    let plain = FirstSet::of_code_points(['a', 'b']);
    assert_eq!(sequence.to_string(), plain.to_string());
    assert!(sequence.skips_trivia(true) && !plain.skips_trivia(true));
    assert_ne!(sequence, plain);
    assert_eq!(root(Expr::choice([lit("a"), lit("b")])), plain);
}

#[test]
fn peg_and_lexical_empty_literals_are_epsilon() {
    // Not in the shared table: Java's zero-width empty literal is `Lexing.LiteralParser("")` in
    // unlaxer-dsl, not a unlaxer-common parser. Both Rust forms succeed consuming nothing.
    assert_eq!(root(lit("")), FirstSet::EPSILON);
    assert_eq!(root(Expr::JavaEmptyLiteral), FirstSet::EPSILON);
    assert_eq!(consumed(&lit(""), "a"), Some(0));
    assert_eq!(consumed(&Expr::JavaEmptyLiteral, "a"), Some(0));
}

#[test]
fn java_shaped_and_peg_repetitions_share_their_first_sets() {
    let a = || lit("a");
    assert_eq!(root(a().optional()), root(a().optional_java()));
    assert_eq!(root(a().zero_or_more()), root(a().repeat_java(0, None)));
    assert_eq!(root(a().one_or_more()), root(a().repeat_java(1, None)));
    assert_eq!(root(Expr::JavaLiteral("ab")), root(lit("ab")));
    let set: HashSet<FirstSet> = [root(lit("ab")), FirstSet::of_code_points(['a'])].into();
    assert_eq!(set.len(), 1, "equal sets hash alike");
}

#[test]
fn rule_ids_outside_the_grammar_are_explicit() {
    let grammar = share_grammar(vec![
        rule("root", Expr::sequence([Expr::Rule(7), lit("a")])),
        rule("leaf", lit("b")),
    ]);
    let sets = FirstSets::of(&grammar);
    assert_eq!(sets.rules().len(), 2);
    assert_eq!(sets.rule(2), None);
    assert_eq!(sets.rule(usize::MAX), None);
    // A dangling reference is unknown, as in the parser, and never panics.
    assert_eq!(sets.rule(0), Some(FirstSet::UNKNOWN));
    assert_eq!(sets.expression(&Expr::Rule(99)), FirstSet::UNKNOWN);
    // Inline expressions resolve rule references against the snapshot.
    let inline = sets.expression(&Expr::choice([Expr::Rule(1), lit("c")]));
    assert_eq!(inline, FirstSet::of_code_points(['b', 'c']));
    assert_eq!(FirstSets::of(&[]).rule(0), None);
    assert!(FirstSets::of(&[]).rules().is_empty());
}

#[test]
fn a_snapshot_is_owned_and_does_not_steer_the_parser() {
    let grammar = share_grammar(vec![rule(
        "root",
        Expr::choice([lit("a"), Expr::sequence([lit("b"), lit("c")])]),
    )]);
    let sets = FirstSets::of(&grammar);
    // Widening a copy cannot reach the grammar or a later query.
    let widened = sets.rule(0).unwrap().union(FirstSet::UNKNOWN);
    assert!(widened.is_unknown());
    assert_eq!(FirstSets::of(&grammar), sets);
    assert_eq!(sets.rule(0).unwrap().to_string(), "FirstSet[ab]");
    for mode in [CandidateExclusion::On, CandidateExclusion::Off] {
        set_candidate_exclusion_for_current_thread(Some(mode));
        assert!(parse_shared(&grammar, 0, false, "a").is_ok());
        assert!(parse_shared(&grammar, 0, false, "bc").is_ok());
        assert!(parse_shared(&grammar, 0, false, "c").is_err());
    }
    set_candidate_exclusion_for_current_thread(None);
}

#[test]
fn empty_rust_combinators_match_their_first_sets() {
    // Rust's empty sequence succeeds consuming nothing and its empty choice always fails,
    // which is why they are `EPSILON` (plus trivia) and `EMPTY` where Java answers unknown.
    let grammar = share_grammar(vec![
        rule("sequence", Expr::sequence([])),
        rule("choice", Expr::choice([])),
    ]);
    assert!(parse_shared(&grammar, 0, false, "").is_ok());
    assert!(parse_shared(&grammar, 1, false, "").is_err());
    assert!(parse_shared(&grammar, 1, false, "a").is_err());
}

#[test]
fn eof_matches_only_at_the_end() {
    let grammar = share_grammar(vec![rule("root", Expr::sequence([Expr::Eof, lit("a")]))]);
    assert!(parse_shared(&grammar, 0, false, "a").is_err());
    assert!(parse_shared(&grammar, 0, false, "").is_err());
    let set = FirstSets::of(&grammar).rule(0).unwrap();
    assert!(!set.may_start("b", false) && set.may_start("a", false));
}

#[test]
fn the_readme_example_holds() {
    // conformance/first-sets/README.md
    let grammar = share_grammar(vec![Rule {
        name: "value",
        expression: Expr::choice([Expr::literal("a"), Expr::literal("b").optional_java()]),
    }]);
    let sets = FirstSets::of(&grammar);
    let value = sets.rule(0).unwrap();
    assert_eq!(value.to_string(), "FirstSet[nullable ab]");
    assert!(value.is_nullable() && value.may_start_with(None, false));
    assert_eq!(sets.rule(1), None);
    assert!(value.union(FirstSet::UNKNOWN).is_unknown() && !sets.rule(0).unwrap().is_unknown());
}

/// Runs `query` on a thread with the 2 MiB stack of a spawned thread, so a query that recursed
/// through the graph would overflow here instead of passing on a larger main-thread stack. An
/// overflow aborts the whole test process; nothing here catches it.
fn on_standard_stack(query: impl FnOnce() + Send + 'static) {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(query)
        .unwrap()
        .join()
        .unwrap();
}

/// `leaf` under `height - 1` Java optionals: `of` would recurse `height` frames.
fn nested(leaf: Expr, height: usize) -> Expr {
    (1..height).fold(leaf, |expression, _| expression.optional_java())
}

const BUDGET_HEIGHT: usize = 1024;
const BUDGET_NODES: usize = 100_000;

#[test]
fn deep_graphs_fall_back_to_unknown_without_recursing() {
    on_standard_stack(|| {
        let before = CALLBACKS.load(Ordering::SeqCst);
        let shallow = FirstSets::of(&[rule("leaf", lit("b"))]);
        for height in [BUDGET_HEIGHT + 1, 2000, 100_000] {
            let leaves = [
                lit("a"),
                Expr::Custom(custom),
                Expr::character("a", only_a),
                Expr::Rule(0),
                Expr::Rule(99),
            ];
            for leaf in leaves {
                let deep = nested(leaf, height);
                assert_eq!(shallow.expression(&deep), FirstSet::UNKNOWN, "{height}");
                // A cycle (rule 0), a dangling reference (99) and a shallow rule beside it.
                let grammar = vec![
                    rule("deep", deep),
                    rule("cycle", Expr::choice([Expr::Rule(1), lit("c")])),
                    rule("dangling", Expr::Rule(99)),
                ];
                let sets = FirstSets::of(&grammar);
                assert_eq!(sets.rules(), [FirstSet::UNKNOWN; 3], "{height}");
                assert_eq!(sets.rule(3), None);
                // The existing `Expr` destructor recurses as deep as the graph; dropping it is
                // not what this test checks, so the fixture is leaked on purpose.
                std::mem::forget(grammar);
            }
        }
        assert_eq!(CALLBACKS.load(Ordering::SeqCst), before);
    });
}

#[test]
fn the_query_budget_keeps_every_smaller_graph_exact() {
    on_standard_stack(|| {
        let exact = FirstSet::of_code_points(['a']).as_nullable();
        let shallow = FirstSets::of(&[rule("leaf", lit("b"))]);
        // The parser's own limit: "expression nesting below 1024" active expression frames.
        let deepest = nested(lit("a"), BUDGET_HEIGHT);
        assert_eq!(shallow.expression(&deepest), exact);
        let grammar = vec![rule("deepest", deepest), rule("leaf", lit("b"))];
        let sets = FirstSets::of(&grammar);
        assert_eq!(sets.rule(0), Some(exact));
        assert_eq!(sets.rule(1), Some(FirstSet::of_code_points(['b'])));
        std::mem::forget(grammar);
        let over = nested(lit("a"), BUDGET_HEIGHT + 1);
        assert_eq!(shallow.expression(&over), FirstSet::UNKNOWN);
        std::mem::forget(over);
    });
}

#[test]
fn wide_and_long_graphs_are_bounded_by_the_java_node_budget() {
    const STARTS: [&str; 3] = ["a", "b", "c"];
    let alternatives = |count: usize| Expr::choice((0..count).map(|at| lit(STARTS[at % 3])));
    let starts = FirstSet::of_code_points(['a', 'b', 'c']);
    let shallow = FirstSets::of(&[]);
    // The choice node itself counts, as Java counts every parser of the walked graph.
    let widest = alternatives(BUDGET_NODES - 1);
    assert_eq!(shallow.expression(&widest), starts);
    assert_eq!(
        FirstSets::of(&[rule("widest", widest)]).rule(0),
        Some(starts)
    );
    let wider = alternatives(BUDGET_NODES);
    assert_eq!(shallow.expression(&wider), FirstSet::UNKNOWN);
    assert_eq!(
        FirstSets::of(&[rule("wider", wider)]).rules(),
        [FirstSet::UNKNOWN]
    );
    // The budget covers the whole grammar, not each rule.
    let half = BUDGET_NODES / 2;
    let split = [
        rule("left", alternatives(half)),
        rule("right", alternatives(half)),
    ];
    assert_eq!(FirstSets::of(&split).rules(), [FirstSet::UNKNOWN; 2]);
    let long = Expr::sequence((0..4 * BUDGET_NODES).map(|_| lit("a").optional_java()));
    assert_eq!(shallow.expression(&long), FirstSet::UNKNOWN);
    let sets = FirstSets::of(&[rule("long", long), rule("leaf", lit("b"))]);
    assert_eq!(sets.rules().len(), 2);
    assert!(sets.rules().iter().all(|set| set.is_unknown()));
}

#[test]
fn long_rule_chains_are_bounded_by_the_java_round_budget() {
    // Rule i refers forward to rule i + 1, so the fixed point settles one rule per round.
    let chain = |count: usize| -> Vec<Rule> {
        (0..count)
            .map(|id| match id + 1 {
                next if next < count => rule("chain", Expr::Rule(next)),
                _ => rule("chain", lit("a")),
            })
            .collect()
    };
    let sets = FirstSets::of(&chain(1_000));
    assert!(sets
        .rules()
        .iter()
        .all(|set| set.to_string() == "FirstSet[a]"));
    let sets = FirstSets::of(&chain(1_001));
    assert_eq!(sets.rules().len(), 1_001);
    assert!(sets.rules().iter().all(|set| set.is_unknown()));
    assert_eq!(sets.rule(1_001), None);
    // The same graph in the other order settles in two rounds and stays exact.
    let reversed: Vec<Rule> = (0..20_000)
        .map(|id| match id {
            0 => rule("chain", lit("a")),
            _ => rule("chain", Expr::Rule(id - 1)),
        })
        .collect();
    assert!(FirstSets::of(&reversed)
        .rules()
        .iter()
        .all(|set| set.to_string() == "FirstSet[a]"));
}
