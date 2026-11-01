//! Parser construction and rule references (#845) against the shared Java/Rust oracle in
//! conformance/parser-construction. Mirrors ParserConstructionParityTest.java: every Rust
//! construction route builds one grammar and must reproduce cases.tsv.
use std::cell::Cell;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::fmt::Write;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Barrier, Condvar, Mutex, OnceLock};
use std::time::Duration;

use unlaxer_runtime::token::{TokenKind, TokenSource};
use unlaxer_runtime::{
    parse_detailed_shared, share_grammar, Diagnostics, Expr, Memoization, ParseContext,
    ParseOptions, ParseResult, Parser, Rule, RuleNameError, RuleNames, RuleStack, SharedGrammar,
};

const NAMES: [&str; 6] = ["Root", "Item", "Leaf", "Probe", "NotProbe", "StopProbe"];
const LEAF: usize = 2;
/// Routes building NotProbe with the user-defined `Expr::Custom` source (`by_names`).
const CUSTOM_SOURCE: [&str; 2] = ["rule-names", "once-lock"];
const VARIANTS: [&str; 5] = [
    "rule-index",
    "rule-names",
    "predicate-position",
    "once-lock",
    "fresh",
];

fn w(text: &'static str) -> Expr {
    Expr::literal(text)
}
fn seq<const N: usize>(elements: [Expr; N]) -> Expr {
    Expr::sequence(elements)
}

/// A user-defined Classic invert source: Java `Toggle` over `AbstractPropagatableSource`.
fn toggle_leaf(context: &mut ParseContext<'_>) -> ParseResult {
    let mode = context.token_mode();
    context.parse_with_token_mode(&Expr::Rule(LEAF), mode.kind, !mode.invert)
}

/// The cases.tsv grammar; `reference` resolves a rule name while the rules are built.
fn rules(reference: impl Fn(&str) -> Expr, not: impl Fn(Expr) -> Expr) -> Vec<Rule> {
    let rule = |name, expression| Rule { name, expression };
    vec![
        rule(
            "Root",
            Expr::choice([
                seq([reference("Item"), w("!")]),
                seq([reference("Item"), w("?")]),
                reference("Missing"),
                seq([reference("Item").match_only(), reference("Item"), w("#")]),
            ]),
        ),
        rule(
            "Item",
            Expr::choice([
                seq([w("("), reference("Item"), w(")")]),
                w("x"),
                w("😀"),
                w("\r\n"),
            ]),
        ),
        rule("Leaf", seq([w("a")])),
        rule("Probe", seq([reference("Leaf")])),
        rule("NotProbe", seq([not(reference("Leaf"))])),
        rule("StopProbe", seq([reference("Leaf").stop_invert()])),
    ]
}

fn by_index() -> Vec<Rule> {
    rules(
        |name| match name {
            "Missing" => Expr::JavaEmptyWord,
            name => Expr::Rule(NAMES.iter().position(|n| *n == name).unwrap()),
        },
        Expr::invert_match,
    )
}

fn by_names() -> SharedGrammar {
    let names = RuleNames::new(NAMES).unwrap();
    assert_eq!(names.get("Leaf"), Some(LEAF));
    let built = rules(
        |name| names.reference_or_never(name),
        |_| Expr::Custom(toggle_leaf),
    );
    names.share(built).unwrap()
}

fn by_predicate() -> Vec<Rule> {
    // Java ReferenceParser: the predicate runs once at construction; a miss stays a miss.
    rules(
        |name| match NAMES.iter().position(|declared| *declared == name) {
            Some(index) => Expr::Rule(index),
            None => Expr::JavaEmptyWord,
        },
        Expr::invert_match,
    )
}

static SHARED: OnceLock<SharedGrammar> = OnceLock::new();

fn variant(name: &str) -> Box<dyn Fn() -> SharedGrammar> {
    match name {
        "rule-index" => {
            let grammar = share_grammar(by_index());
            Box::new(move || Arc::clone(&grammar))
        }
        "rule-names" => {
            let grammar = by_names();
            Box::new(move || Arc::clone(&grammar))
        }
        "predicate-position" => {
            let grammar = share_grammar(by_predicate());
            Box::new(move || Arc::clone(&grammar))
        }
        "once-lock" => Box::new(|| Arc::clone(SHARED.get_or_init(by_names))),
        "fresh" => Box::new(|| share_grammar(by_index())),
        _ => panic!("{name}"),
    }
}

struct Grammar(SharedGrammar, usize);
impl Parser for Grammar {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_shared_grammar(&self.0, self.1, false)
    }
}

fn decode(value: &str) -> String {
    if value == "~" {
        return String::new();
    }
    String::from_utf8(
        (0..value.len())
            .step_by(2)
            .map(|index| u8::from_str_radix(&value[index..index + 2], 16).unwrap())
            .collect(),
    )
    .unwrap()
}
fn encode(value: &str) -> String {
    if value.is_empty() {
        return "~".into();
    }
    value.bytes().fold(String::new(), |mut text, byte| {
        write!(text, "{byte:02x}").unwrap();
        text
    })
}
fn kind(value: &str) -> TokenKind {
    match value {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        _ => panic!("{value}"),
    }
}
fn kind_name(value: TokenKind) -> &'static str {
    match value {
        TokenKind::Consumed => "consumed",
        TokenKind::MatchOnly => "matchOnly",
        TokenKind::VirtualConsumed => "virtualTokenConsumed",
        TokenKind::VirtualMatchOnly => "virtualTokenMatchOnly",
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
    let text = encode(context.token_text(id).unwrap());
    format!("{kind}@{start}:{length}:{text}")
}
fn signatures(context: &mut ParseContext<'_>, nodes: &[usize]) -> String {
    if nodes.is_empty() {
        return "~".into();
    }
    let rendered: Vec<_> = nodes.iter().map(|&node| signature(context, node)).collect();
    rendered.join(";")
}
fn leaves(context: &mut ParseContext<'_>, index: usize, target: &mut Vec<usize>) {
    let id = context.token_node_id(index).unwrap();
    let word = context.token_info(id).unwrap().parser_label() == Some("WordParser");
    let children = context.node(index).unwrap().children.clone();
    if word || children.is_empty() {
        target.push(index);
    } else {
        for child in children {
            leaves(context, child, target);
        }
    }
}
/// Java `Parsed.getOriginalTokens()`. A Rust rule node is a CST boundary without a Java
/// counterpart (a Java rule is the combinator itself), so read its expression's node.
fn java_original(context: &mut ParseContext<'_>, mut node: usize) -> Vec<usize> {
    loop {
        let id = context.token_node_id(node).unwrap();
        if let Some(raw) = context.token_info(id).unwrap().original_tokens() {
            return raw.to_vec();
        }
        let current = context.node(node).unwrap();
        match current.children[..] {
            [child] if current.rule < NAMES.len() => node = child,
            _ => return vec![node],
        }
    }
}
/// The whole CST including intermediate nodes: the Classic parser label, else the rule name.
/// cases.tsv compares root and leaves across languages; this shape is compared across the
/// construction routes of one language.
fn shape(context: &mut ParseContext<'_>, index: usize) -> String {
    let id = context.token_node_id(index).unwrap();
    let node = context.node(index).unwrap().clone();
    let label = context
        .token_info(id)
        .unwrap()
        .parser_label()
        .or_else(|| NAMES.get(node.rule).copied())
        .unwrap_or("node");
    if node.children.is_empty() {
        return format!(
            "{label}@{}:{}",
            node.span.start,
            node.span.end - node.span.start
        );
    }
    let children: Vec<_> = node
        .children
        .iter()
        .map(|&child| shape(context, child))
        .collect();
    format!("{label}({})", children.join(","))
}

fn render(stack: Option<RuleStack>) -> String {
    stack.map_or("~".into(), |stack| {
        let frames: Vec<_> = stack
            .frames
            .iter()
            .map(|frame| format!("{}@{}", frame.rule, frame.start))
            .collect();
        frames.join(">")
    })
}

fn rows(corpus: &'static str, columns: usize) -> Vec<Vec<&'static str>> {
    let rows: Vec<Vec<_>> = corpus
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|line| line.split('\t').collect())
        .collect();
    for row in &rows {
        assert_eq!(row.len(), columns, "{row:?}");
    }
    rows
}

/// Parses one cases.tsv row in a fresh context: the mismatch against the row, if any, and the
/// whole CST for the comparison across construction routes.
fn case_observation(
    grammar: &SharedGrammar,
    c: &[&str],
    memo: Memoization,
    diagnostics: Diagnostics,
) -> (Option<String>, String) {
    let input = decode(c[2]);
    let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
    let mut context = ParseContext::with_options(&input, options);
    let root = NAMES.iter().position(|name| *name == c[1]).unwrap();
    let parsed = context.parse_with_token_mode(
        &Grammar(Arc::clone(grammar), root),
        kind(c[3]),
        c[4] == "true",
    );
    let mut originals = Vec::new();
    let mut committed = Vec::new();
    if let Ok(parsed) = &parsed {
        for &node in &parsed.nodes {
            originals.extend(java_original(&mut context, node));
        }
        committed.extend(parsed.nodes.iter().copied());
    }
    let mut terminal = Vec::new();
    for &node in &committed {
        leaves(&mut context, node, &mut terminal);
    }
    let deferred = diagnostics == Diagnostics::DetailedOnFailure;
    let actual = [
        parsed.is_ok().to_string(),
        context.position().to_string(),
        context.matched_position().to_string(),
        context.failure().offset.to_string(),
        signatures(&mut context, &originals),
        signatures(&mut context, &committed),
        signatures(&mut context, &terminal),
        render(context.rule_stack()),
    ]
    .join("\t");
    let expected = [
        c[5],
        c[6],
        c[7],
        if deferred { "0" } else { c[8] },
        c[9],
        c[10],
        c[11],
        if deferred { "~" } else { c[12] },
    ]
    .join("\t");
    let mismatch =
        (actual != expected).then(|| format!("\n  expected {expected}\n  actual   {actual}"));
    let tree: Vec<_> = committed
        .iter()
        .map(|&node| shape(&mut context, node))
        .collect();
    (mismatch, tree.join(";"))
}

/// NotProbe is a different expression in the Custom-source routes: Custom is CST-transparent,
/// invert_match keeps a NotPropagatableSource boundary.
fn shape_key(
    c: &[&str],
    variant_name: &str,
    memo: Memoization,
    diagnostics: Diagnostics,
) -> String {
    let source = if c[1] == "NotProbe" && CUSTOM_SOURCE.contains(&variant_name) {
        "custom"
    } else {
        "builtin"
    };
    format!("{}/{memo:?}/{diagnostics:?}/{source}", c[0])
}

#[test]
fn every_construction_route_reproduces_the_shared_parse_oracle() {
    let rows = rows(
        include_str!("../../../conformance/parser-construction/cases.tsv"),
        13,
    );
    assert_eq!(rows.len(), 23);
    let mut failures = Vec::new();
    let mut shapes = HashMap::new();
    for variant_name in VARIANTS {
        let grammar = variant(variant_name);
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                for c in &rows {
                    let label = format!("{}/{variant_name}/{memo:?}/{diagnostics:?}", c[0]);
                    let (mismatch, tree) = case_observation(&grammar(), c, memo, diagnostics);
                    if let Some(mismatch) = mismatch {
                        failures.push(format!("{label}{mismatch}"));
                    }
                    let first = shapes
                        .entry(shape_key(c, variant_name, memo, diagnostics))
                        .or_insert_with(|| (variant_name, tree.clone()));
                    if first.1 != tree {
                        failures.push(format!(
                            "{label} intermediate CST differs from {}\n  {}\n  {tree}",
                            first.0, first.1
                        ));
                    }
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

// ---- concurrent parses of one frozen shared grammar (#1007)

const THREADS: usize = 8;
const ROUNDS: usize = 40;
const MODES: [(Memoization, Diagnostics); 6] = [
    (Memoization::Off, Diagnostics::Auto),
    (Memoization::Off, Diagnostics::Detailed),
    (Memoization::Off, Diagnostics::DetailedOnFailure),
    (Memoization::SafeFailures, Diagnostics::Auto),
    (Memoization::SafeFailures, Diagnostics::Detailed),
    (Memoization::SafeFailures, Diagnostics::DetailedOnFailure),
];
const LIST_NAMES: [&str; 3] = ["List", "Elem", "Top"];
const LIST: usize = 0;

/// The shared-graph.tsv grammar: List ::= Elem*; Elem ::= '(' List ')' | 'é' | '😀';
/// Top ::= List '!'. `elem_list` is Elem's reference to List.
fn list_rules(reference: impl Fn(&str) -> Expr, elem_list: Expr) -> Vec<Rule> {
    vec![
        rule("List", seq([reference("Elem").repeat_java(0, None)])),
        rule(
            "Elem",
            Expr::choice([seq([w("("), elem_list, w(")")]), w("é"), w("😀")]),
        ),
        rule("Top", seq([reference("List"), w("!")])),
    ]
}

fn list_position(name: &str) -> Expr {
    Expr::Rule(LIST_NAMES.iter().position(|n| *n == name).unwrap())
}

fn list_by_names() -> SharedGrammar {
    let names = RuleNames::new(LIST_NAMES).unwrap();
    names
        .share(list_rules(
            |name| names.reference_or_never(name),
            names.reference_or_never("List"),
        ))
        .unwrap()
}

/// Java `parser.parse(context)`: the Classic consumed mode under an outermost
/// `ParseContext::parse`, which records the outcome, so a successful parse reports no rule
/// stack. `parse_with_token_mode` as the outermost entry does not record it and reports the
/// speculative stack of a success (a separate residue, found by #1007's fixture).
struct ClassicEntry(Grammar);
impl Parser for ClassicEntry {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_with_token_mode(&self.0, TokenKind::Consumed, false)
    }
}

/// Parses one shared-graph.tsv row in a fresh context: the mismatch against the row, if any.
fn graph_observation(
    grammar: &SharedGrammar,
    body: Option<&Arc<LazyBody>>,
    c: &[&str],
    memo: Memoization,
    diagnostics: Diagnostics,
) -> Option<String> {
    let input = decode(c[2]);
    let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
    let mut context = ParseContext::with_options(&input, options);
    if let Some(body) = body {
        context.set_state(LAZY_BODY, Arc::clone(body));
    }
    let root = LIST_NAMES.iter().position(|name| *name == c[1]).unwrap();
    let parsed = context.parse(&ClassicEntry(Grammar(Arc::clone(grammar), root)));
    let mut terminal = Vec::new();
    if let Ok(parsed) = &parsed {
        for &node in &parsed.nodes {
            leaves(&mut context, node, &mut terminal);
        }
    }
    let deferred = diagnostics == Diagnostics::DetailedOnFailure;
    let actual = [
        parsed.is_ok().to_string(),
        context.position().to_string(),
        context.failure().offset.to_string(),
        signatures(&mut context, &terminal),
        render(context.rule_stack()),
    ]
    .join("\t");
    let expected = [
        c[3],
        c[4],
        if deferred { "0" } else { c[5] },
        c[6],
        if deferred { "~" } else { c[7] },
    ]
    .join("\t");
    (actual != expected).then(|| format!("\n  expected {expected}\n  actual   {actual}"))
}

/// The prefix observation of `scope_observation` in one rotated mode per input.
fn scope_prefixes(scoped: &SharedGrammar, control: &SharedGrammar, mut mode: usize) -> String {
    let calls = [(scoped, 0, "a"), (scoped, 0, "(x)"), (control, 1, "a")];
    let observed: Vec<_> = calls
        .into_iter()
        .map(|(grammar, root, input)| {
            let (memo, diagnostics) = MODES[mode % MODES.len()];
            mode += 1;
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options(input, options);
            let parsed = context.parse_shared_grammar(grammar, root, false);
            format!("{}/{}", parsed.is_ok(), context.position())
        })
        .collect();
    observed.join(";")
}

#[test]
fn a_frozen_shared_grammar_parses_concurrently_like_the_shared_oracle() {
    let cases = rows(
        include_str!("../../../conformance/parser-construction/cases.tsv"),
        13,
    );
    let graph_rows = rows(
        include_str!("../../../conformance/parser-construction/shared-graph.tsv"),
        8,
    );
    assert_eq!(graph_rows.len(), 6);
    let construction = rows(
        include_str!("../../../conformance/parser-construction/construction.tsv"),
        4,
    );
    let scope_expected = |id: &str| construction.iter().find(|row| row[0] == id).unwrap()[2];
    let failures = Mutex::new(Vec::new());
    let shapes = Mutex::new(HashMap::new());
    let parses = AtomicUsize::new(0);
    for round in 0..ROUNDS {
        // Built before the threads start; every thread parses clones of the same Arc grammars.
        let case_grammars: Vec<_> = VARIANTS
            .iter()
            .map(|name| (*name, variant(name)()))
            .collect();
        let list_grammars = [
            ("rule-names", list_by_names()),
            (
                "predicate-position",
                share_grammar(list_rules(list_position, list_position("List"))),
            ),
        ];
        let scopes = [
            (
                "root-indicator-scope-by-name",
                scoped_outer(call_scope_by_name),
            ),
            (
                "root-indicator-scope-predicate",
                scoped_outer(call_scope_by_position),
            ),
        ];
        let control = unscoped_outer();
        let barrier = Barrier::new(THREADS);
        std::thread::scope(|scope| {
            for thread in 0..THREADS {
                let (cases, graph_rows, case_grammars, list_grammars, scopes, control) = (
                    &cases,
                    &graph_rows,
                    &case_grammars,
                    &list_grammars,
                    &scopes,
                    &control,
                );
                let (barrier, failures, shapes, parses) = (&barrier, &failures, &shapes, &parses);
                scope.spawn(move || {
                    barrier.wait();
                    // Even threads share one order and so start on the same rows; odd ones rotate.
                    let shift = if thread % 2 == 0 { 0 } else { thread };
                    let mut mode = thread + round;
                    let mut local = Vec::new();
                    for (variant_name, grammar) in case_grammars {
                        for row in 0..cases.len() {
                            let c = &cases[(row + shift) % cases.len()];
                            let (memo, diagnostics) = MODES[mode % MODES.len()];
                            mode += 1;
                            let label = format!(
                                "{}/{variant_name}/{memo:?}/{diagnostics:?}/thread{thread}/round{round}",
                                c[0]
                            );
                            let (mismatch, tree) = case_observation(grammar, c, memo, diagnostics);
                            if let Some(mismatch) = mismatch {
                                local.push(format!("{label}{mismatch}"));
                            }
                            let mut shapes = shapes.lock().unwrap();
                            let first = shapes
                                .entry(shape_key(c, variant_name, memo, diagnostics))
                                .or_insert_with(|| tree.clone());
                            if *first != tree {
                                local.push(format!("{label} intermediate CST\n  {first}\n  {tree}"));
                            }
                            parses.fetch_add(1, Ordering::SeqCst);
                        }
                    }
                    for (variant_name, grammar) in list_grammars {
                        for row in 0..graph_rows.len() {
                            let c = &graph_rows[(row + shift) % graph_rows.len()];
                            let (memo, diagnostics) = MODES[mode % MODES.len()];
                            mode += 1;
                            if let Some(mismatch) = graph_observation(grammar, None, c, memo, diagnostics)
                            {
                                local.push(format!(
                                    "{}/{variant_name}/{memo:?}/{diagnostics:?}/thread{thread}/round{round}{mismatch}",
                                    c[0]
                                ));
                            }
                            parses.fetch_add(1, Ordering::SeqCst);
                        }
                    }
                    for (id, scoped) in scopes {
                        let actual = scope_prefixes(scoped, control, mode);
                        mode += 3;
                        if actual != scope_expected(id) {
                            local.push(format!(
                                "{id}/thread{thread}/round{round}: expected {} actual {actual}",
                                scope_expected(id)
                            ));
                        }
                        parses.fetch_add(3, Ordering::SeqCst);
                    }
                    failures.lock().unwrap().extend(local);
                });
            }
        });
    }
    let failures = failures.into_inner().unwrap();
    assert!(
        failures.is_empty(),
        "{} failures\n{}",
        failures.len(),
        failures[..failures.len().min(20)].join("\n")
    );
    let per_thread = VARIANTS.len() * cases.len() + 2 * graph_rows.len() + 2 * 3;
    assert_eq!(parses.load(Ordering::SeqCst), THREADS * ROUNDS * per_thread);
}

/// 0 = not reached, 1 = the first caller is parked, 2 = released.
static PARK: (Mutex<u8>, Condvar) = (Mutex::new(0), Condvar::new());

/// Elem's reference to List; the first call parks until the test releases it.
fn parked_list(context: &mut ParseContext<'_>) -> ParseResult {
    let (state, signal) = &PARK;
    let mut current = state.lock().unwrap();
    if *current == 0 {
        *current = 1;
        signal.notify_all();
        while *current != 2 {
            current = signal.wait(current).unwrap();
        }
    }
    drop(current);
    let mode = context.token_mode();
    context.parse_with_token_mode(&Expr::Rule(LIST), mode.kind, mode.invert)
}

/// first-use-parked in shared-graph.tsv: a context stopped in the middle of a parse holds
/// nothing another context on the same Arc grammar waits for; both match the row.
#[test]
fn a_context_parked_mid_parse_does_not_block_or_corrupt_another() {
    let graph_rows = rows(
        include_str!("../../../conformance/parser-construction/shared-graph.tsv"),
        8,
    );
    let row: Vec<&'static str> = graph_rows
        .iter()
        .find(|c| c[0] == "list-nested")
        .unwrap()
        .clone();
    let grammar = share_grammar(list_rules(list_position, Expr::Custom(parked_list)));
    let observe = {
        let (grammar, row) = (Arc::clone(&grammar), row.clone());
        move || {
            graph_observation(
                &grammar,
                None,
                &row,
                Memoization::Off,
                Diagnostics::Detailed,
            )
        }
    };
    let stopped = std::thread::spawn(observe.clone());
    let (state, signal) = &PARK;
    let (current, timeout) = signal
        .wait_timeout_while(state.lock().unwrap(), Duration::from_secs(60), |current| {
            *current == 0
        })
        .unwrap();
    assert!(!timeout.timed_out(), "the first call was reached");
    drop(current);
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || sender.send(observe()).unwrap());
    let other = receiver.recv_timeout(Duration::from_secs(60)).unwrap();
    assert_eq!(other, None);
    assert!(
        !stopped.is_finished(),
        "still parked while the other context finished"
    );
    *state.lock().unwrap() = 2;
    signal.notify_all();
    assert_eq!(stopped.join().unwrap(), None);
}

// ---- lazy delegates: Java user parsers that inherit AbstractParser.parse (#1007)

const LAZY_BODY: &str = "lazy-list-body";

/// Java's LazyDelegate, whose createParser builds List's body `Elem*` on first use. A Rust grammar
/// is immutable, so the body lives in a OnceLock beside it; the contexts that parse one graph share
/// it through their user state, and the first to reach it builds it while the others wait.
struct LazyBody {
    cell: OnceLock<Expr>,
    builds: AtomicUsize,
    build: Box<dyn Fn(usize) -> Expr + Send + Sync>,
}

impl LazyBody {
    fn new(build: impl Fn(usize) -> Expr + Send + Sync + 'static) -> Arc<Self> {
        Arc::new(LazyBody {
            cell: OnceLock::new(),
            builds: AtomicUsize::new(0),
            build: Box::new(build),
        })
    }

    /// The body, built by the first caller; `build` receives the number of earlier attempts.
    fn get(&self) -> &Expr {
        self.cell
            .get_or_init(|| (self.build)(self.builds.fetch_add(1, Ordering::SeqCst)))
    }
}

/// The lazily built expression of the graph whose LazyBody the context carries: List's body, or
/// Elem's alternatives in a supplied graph (#1014).
fn lazy_list_body(context: &mut ParseContext<'_>) -> ParseResult {
    let body = Arc::clone(
        context
            .state::<Arc<LazyBody>>(LAZY_BODY)
            .expect("the graph's lazy body"),
    );
    let mode = context.token_mode();
    context.parse_with_token_mode(body.get(), mode.kind, mode.invert)
}

/// shared-graph.tsv's grammar with List's body built by `lazy_list_body`.
fn delegate_rules(reference: impl Fn(&str) -> Expr) -> Vec<Rule> {
    vec![
        rule("List", seq([Expr::Custom(lazy_list_body)])),
        rule(
            "Elem",
            Expr::choice([seq([w("("), reference("List"), w(")")]), w("é"), w("😀")]),
        ),
        rule("Top", seq([reference("List"), w("!")])),
    ]
}

/// One graph and its lazy body for a reference route of shared-graph.tsv.
fn delegate_graph(variant: &str) -> (SharedGrammar, Arc<LazyBody>) {
    match variant {
        "rule-names" => {
            let names = RuleNames::new(LIST_NAMES).unwrap();
            let grammar = names
                .share(delegate_rules(|name| names.reference_or_never(name)))
                .unwrap();
            let body = LazyBody::new(move |_| {
                let names = RuleNames::new(LIST_NAMES).unwrap();
                names.reference_or_never("Elem").repeat_java(0, None)
            });
            (grammar, body)
        }
        "predicate-position" => (
            share_grammar(delegate_rules(list_position)),
            LazyBody::new(|_| list_position("Elem").repeat_java(0, None)),
        ),
        _ => unreachable!("{variant}"),
    }
}

/// The body after its first uses: built once, referring to Elem.
fn delegate_properties(body: &LazyBody) -> String {
    let expected = format!("{:?}", list_position("Elem").repeat_java(0, None));
    let built = body.cell.get().map(|expression| format!("{expression:?}"));
    format!(
        "{};{}",
        body.builds.load(Ordering::SeqCst),
        if built.as_deref() == Some(expected.as_str()) {
            "same"
        } else {
            "distinct"
        }
    )
}

/// lazy-delegate first use in shared-graph.tsv: THREADS threads, each parsing every row in two
/// contexts, start together on graphs whose body nobody built yet (first-use) and on graphs whose
/// body was built before sharing (materialized). Every parse matches its row; afterwards each body
/// was built once.
#[test]
fn concurrent_first_uses_of_a_lazy_body_build_it_once() {
    let graph_rows = rows(
        include_str!("../../../conformance/parser-construction/shared-graph.tsv"),
        8,
    );
    const CONTEXTS: usize = 2;
    let failures = Mutex::new(Vec::new());
    let parses = AtomicUsize::new(0);
    let mut graph_count = 0;
    for round in 0..ROUNDS {
        let mut graphs = Vec::new();
        for variant in ["rule-names", "predicate-position"] {
            for state in ["first-use", "materialized"] {
                let (grammar, body) = delegate_graph(variant);
                if state == "materialized" {
                    // The freeze step: build the body before sharing.
                    body.get();
                }
                graphs.push((format!("{variant}/{state}"), grammar, body));
            }
        }
        graph_count = graphs.len();
        let barrier = Barrier::new(THREADS);
        std::thread::scope(|scope| {
            for thread in 0..THREADS {
                let (graph_rows, graphs) = (&graph_rows, &graphs);
                let (barrier, failures, parses) = (&barrier, &failures, &parses);
                scope.spawn(move || {
                    barrier.wait();
                    let shift = if thread % 2 == 0 { 0 } else { thread };
                    let mut mode = thread + round;
                    let mut local = Vec::new();
                    for (label, grammar, body) in graphs {
                        for row in 0..graph_rows.len() {
                            let c = &graph_rows[(row + shift) % graph_rows.len()];
                            for context in 0..CONTEXTS {
                                let (memo, diagnostics) = MODES[mode % MODES.len()];
                                mode += 1;
                                if let Some(mismatch) =
                                    graph_observation(grammar, Some(body), c, memo, diagnostics)
                                {
                                    local.push(format!(
                                        "{}/{label}/{memo:?}/{diagnostics:?}/thread{thread}/context{context}/round{round}{mismatch}",
                                        c[0]
                                    ));
                                }
                                parses.fetch_add(1, Ordering::SeqCst);
                            }
                        }
                    }
                    failures.lock().unwrap().extend(local);
                });
            }
        });
        for (label, _, body) in &graphs {
            let actual = delegate_properties(body);
            if actual != "1;same" {
                failures
                    .lock()
                    .unwrap()
                    .push(format!("{label}/round{round} properties: {actual}"));
            }
        }
    }
    let failures = failures.into_inner().unwrap();
    assert!(
        failures.is_empty(),
        "{} failures\n{}",
        failures.len(),
        failures[..failures.len().min(20)].join("\n")
    );
    assert_eq!(
        parses.load(Ordering::SeqCst),
        THREADS * ROUNDS * graph_count * graph_rows.len() * CONTEXTS
    );
}

/// 0 = not reached, 1 = the first build is parked, 2 = released.
static BUILD_PARK: (Mutex<u8>, Condvar) = (Mutex::new(0), Condvar::new());

/// lazy-delegate-parked in shared-graph.tsv: a context stopped inside the body's first build
/// holds the OnceLock; another context's first use waits for it instead of building a second
/// body, then both match the row.
#[test]
fn a_first_use_parked_in_the_build_makes_the_other_first_use_wait_for_the_one_body() {
    let graph_rows = rows(
        include_str!("../../../conformance/parser-construction/shared-graph.tsv"),
        8,
    );
    let row: Vec<&'static str> = graph_rows
        .iter()
        .find(|c| c[0] == "list-nested")
        .unwrap()
        .clone();
    let grammar = share_grammar(delegate_rules(list_position));
    let body = LazyBody::new(|_| {
        let (state, signal) = &BUILD_PARK;
        let mut current = state.lock().unwrap();
        if *current == 0 {
            *current = 1;
            signal.notify_all();
            while *current != 2 {
                current = signal.wait(current).unwrap();
            }
        }
        list_position("Elem").repeat_java(0, None)
    });
    let observe = {
        let (grammar, body, row) = (Arc::clone(&grammar), Arc::clone(&body), row.clone());
        move || {
            graph_observation(
                &grammar,
                Some(&body),
                &row,
                Memoization::Off,
                Diagnostics::Detailed,
            )
        }
    };
    let stopped = std::thread::spawn(observe.clone());
    let (state, signal) = &BUILD_PARK;
    let (current, timeout) = signal
        .wait_timeout_while(state.lock().unwrap(), Duration::from_secs(60), |current| {
            *current == 0
        })
        .unwrap();
    assert!(!timeout.timed_out(), "the first build was reached");
    drop(current);
    let other = std::thread::spawn(observe);
    std::thread::sleep(Duration::from_millis(200));
    assert!(
        !other.is_finished(),
        "the other first use waits for the parked build"
    );
    *state.lock().unwrap() = 2;
    signal.notify_all();
    assert_eq!(stopped.join().unwrap(), None);
    assert_eq!(other.join().unwrap(), None);
    assert_eq!(delegate_properties(&body), "1;same");
}

/// Parses List of a delegate graph in a fresh context.
fn delegate_prefix(grammar: &SharedGrammar, body: &Arc<LazyBody>, input: &str) -> String {
    let mut context = ParseContext::new(input);
    context.set_state(LAZY_BODY, Arc::clone(body));
    let parsed = context.parse_shared_grammar(grammar, LIST, false);
    format!("{}/{}", parsed.is_ok(), context.position())
}

// ---- lazy children supplied into one list (#1014)

const FIRST_SUPPLY_FAILS: &str = "first supply fails";

/// The calls of Java's counted suppliers. A Rust grammar is immutable: the suppliers run into a
/// local Vec that is published into a OnceLock only when every one of them returned, so a failure
/// drops the children built so far instead of publishing them.
struct Supplied {
    calls: Vec<AtomicUsize>,
}

impl Supplied {
    fn new(count: usize) -> Arc<Self> {
        Arc::new(Supplied {
            calls: (0..count).map(|_| AtomicUsize::new(0)).collect(),
        })
    }

    /// Counts one call of supplier `index`; whether it is the second supplier's first call.
    fn first_of_second(&self, index: usize) -> bool {
        self.calls[index].fetch_add(1, Ordering::SeqCst) == 0 && index == 1
    }

    fn calls(&self) -> String {
        self.calls
            .iter()
            .map(|calls| calls.load(Ordering::SeqCst).to_string())
            .collect::<Vec<_>>()
            .join("/")
    }
}

/// Java's children A ::= 'a', B ::= 'b', C ::= 'c' in declaration order.
fn abc(index: usize) -> Expr {
    w(["a", "b", "c"][index])
}

fn children(expression: &Expr) -> &[Expr] {
    match expression {
        Expr::Sequence(children) | Expr::Choice(children) => children,
        other => panic!("not a child list: {other:?}"),
    }
}

fn same<T>(first: &T, second: &T) -> &'static str {
    if std::ptr::eq(first, second) {
        "same"
    } else {
        "distinct"
    }
}

/// shared-graph.tsv's grammar with Elem's alternatives supplied into a LazyBody: the second
/// supplier ('é') fails on its first call without a panic message (resume_unwind).
fn supplied_graph(variant: &str) -> (SharedGrammar, Arc<LazyBody>, Arc<Supplied>) {
    let rules = |reference: &dyn Fn(&str) -> Expr| {
        vec![
            rule("List", seq([reference("Elem").repeat_java(0, None)])),
            rule("Elem", Expr::Custom(lazy_list_body)),
            rule("Top", seq([reference("List"), w("!")])),
        ]
    };
    let (grammar, list): (SharedGrammar, fn() -> Expr) = match variant {
        "rule-names" => {
            let names = RuleNames::new(LIST_NAMES).unwrap();
            let grammar = names
                .share(rules(&|name| names.reference_or_never(name)))
                .unwrap();
            (grammar, || {
                RuleNames::new(LIST_NAMES)
                    .unwrap()
                    .reference_or_never("List")
            })
        }
        "predicate-position" => (share_grammar(rules(&list_position)), || {
            list_position("List")
        }),
        _ => unreachable!("{variant}"),
    };
    let supplied = Supplied::new(3);
    let counted = Arc::clone(&supplied);
    let body = LazyBody::new(move |_| {
        Expr::choice((0..3).map(|index| {
            if counted.first_of_second(index) {
                std::panic::resume_unwind(Box::new(FIRST_SUPPLY_FAILS));
            }
            match index {
                0 => seq([w("("), list(), w(")")]),
                1 => w("é"),
                _ => w("😀"),
            }
        }))
    });
    (grammar, body, supplied)
}

/// lazy-children first use in shared-graph.tsv (#1014): THREADS threads, each parsing every row in
/// two contexts, start together on a graph nobody used yet whose Elem alternatives are supplied on
/// first use; the second supplier fails on its first call. Exactly one parse gets that failure,
/// every other parse matches its row, and the published list holds all three alternatives.
#[test]
fn concurrent_first_uses_of_lazy_children_publish_only_the_complete_list() {
    let graph_rows = rows(
        include_str!("../../../conformance/parser-construction/shared-graph.tsv"),
        8,
    );
    const CONTEXTS: usize = 2;
    const VARIANTS: [&str; 2] = ["rule-names", "predicate-position"];
    let failures = Mutex::new(Vec::new());
    let parses = AtomicUsize::new(0);
    for round in 0..ROUNDS {
        let graphs: Vec<_> = VARIANTS
            .iter()
            .map(|variant| (*variant, supplied_graph(variant), AtomicUsize::new(0)))
            .collect();
        let barrier = Barrier::new(THREADS);
        std::thread::scope(|scope| {
            for thread in 0..THREADS {
                let (graph_rows, graphs) = (&graph_rows, &graphs);
                let (barrier, failures, parses) = (&barrier, &failures, &parses);
                scope.spawn(move || {
                    barrier.wait();
                    let shift = if thread % 2 == 0 { 0 } else { thread };
                    let mut mode = thread + round;
                    let mut local = Vec::new();
                    for (label, (grammar, body, _), supply_failures) in graphs {
                        for row in 0..graph_rows.len() {
                            let c = &graph_rows[(row + shift) % graph_rows.len()];
                            for context in 0..CONTEXTS {
                                let (memo, diagnostics) = MODES[mode % MODES.len()];
                                mode += 1;
                                let label = format!(
                                    "{}/{label}/{memo:?}/{diagnostics:?}/thread{thread}/context{context}/round{round}",
                                    c[0]
                                );
                                match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    graph_observation(grammar, Some(body), c, memo, diagnostics)
                                })) {
                                    Ok(None) => {}
                                    Ok(Some(mismatch)) => local.push(format!("{label}{mismatch}")),
                                    Err(payload) => {
                                        if payload.downcast_ref::<&str>() == Some(&FIRST_SUPPLY_FAILS) {
                                            supply_failures.fetch_add(1, Ordering::SeqCst);
                                        } else {
                                            local.push(format!("{label}: panicked"));
                                        }
                                    }
                                }
                                parses.fetch_add(1, Ordering::SeqCst);
                            }
                        }
                    }
                    failures.lock().unwrap().extend(local);
                });
            }
        });
        for (label, (_, body, supplied), supply_failures) in &graphs {
            let actual = format!(
                "{};{};{}",
                supply_failures.load(Ordering::SeqCst),
                supplied.calls(),
                body.cell.get().map_or(0, |built| children(built).len())
            );
            if actual != "1;2/2/1;3" {
                failures
                    .lock()
                    .unwrap()
                    .push(format!("{label}/round{round} properties: {actual}"));
            }
        }
    }
    let failures = failures.into_inner().unwrap();
    assert!(
        failures.is_empty(),
        "{} failures\n{}",
        failures.len(),
        failures[..failures.len().min(20)].join("\n")
    );
    assert_eq!(
        parses.load(Ordering::SeqCst),
        THREADS * ROUNDS * VARIANTS.len() * graph_rows.len() * CONTEXTS
    );
}

// ---- construction and identity observations

fn identity(first: &SharedGrammar, second: &SharedGrammar) -> &'static str {
    if Arc::ptr_eq(first, second) {
        "same"
    } else {
        "distinct"
    }
}

fn shared() -> &'static SharedGrammar {
    static CELL: OnceLock<SharedGrammar> = OnceLock::new();
    CELL.get_or_init(|| share_grammar(by_index()))
}

fn other_shared() -> &'static SharedGrammar {
    static CELL: OnceLock<SharedGrammar> = OnceLock::new();
    CELL.get_or_init(|| share_grammar(by_index()))
}

static FLAKY_ATTEMPTS: AtomicUsize = AtomicUsize::new(0);

/// A fallible factory: the cell is set only after a successful build, so failures retry.
fn flaky() -> Result<&'static SharedGrammar, &'static str> {
    static CELL: OnceLock<SharedGrammar> = OnceLock::new();
    if let Some(grammar) = CELL.get() {
        return Ok(grammar);
    }
    if FLAKY_ATTEMPTS.fetch_add(1, Ordering::SeqCst) == 0 {
        return Err("first construction fails");
    }
    let grammar = share_grammar(by_index());
    Ok(CELL.get_or_init(|| grammar))
}

/// Java ParserFactoryByClass.get(kind, Class): one shared instance per (grammar, kind) key.
fn kinded(kind: Option<&'static str>) -> SharedGrammar {
    static CELLS: OnceLock<Mutex<HashMap<Option<&'static str>, SharedGrammar>>> = OnceLock::new();
    let cells = CELLS.get_or_init(Default::default);
    let mut cells = cells.lock().unwrap();
    Arc::clone(
        cells
            .entry(kind)
            .or_insert_with(|| share_grammar(by_index())),
    )
}

static LAZY_BUILDS: AtomicUsize = AtomicUsize::new(0);

fn lazy() -> &'static SharedGrammar {
    static CELL: OnceLock<SharedGrammar> = OnceLock::new();
    CELL.get_or_init(|| {
        LAZY_BUILDS.fetch_add(1, Ordering::SeqCst);
        share_grammar(vec![Rule {
            name: "Lazy",
            expression: seq([w("a")]),
        }])
    })
}

fn prefix(grammar: &SharedGrammar, root: usize, input: &str) -> String {
    let mut context = ParseContext::new(input);
    let parsed = context.parse_shared_grammar(grammar, root, false);
    format!("{}/{}", parsed.is_ok(), context.position())
}

fn rule(name: &'static str, expression: Expr) -> Rule {
    Rule { name, expression }
}

fn observe_invert(context: &mut ParseContext<'_>) -> ParseResult {
    let mode = context.token_mode();
    context.set_state("invert", mode.invert);
    context.parse_with_token_mode(&w("a"), mode.kind, mode.invert)
}

/// The Rust port of HasChildrenParser.newWithoutRecursive: drop matching nodes at any depth,
/// keeping each combinator variant. Cutting the only child of a single-child combinator is a
/// construction error, as Java's createWith fails for an empty single-child parser.
fn without(expression: Expr, cut: &dyn Fn(&Expr) -> bool) -> Result<Option<Expr>, &'static str> {
    if cut(&expression) {
        return Ok(None);
    }
    let filter = |children: Vec<Expr>| -> Result<Vec<Expr>, &'static str> {
        let mut kept = Vec::new();
        for child in children {
            kept.extend(without(child, cut)?);
        }
        Ok(kept)
    };
    Ok(Some(match expression {
        Expr::Sequence(children) => Expr::Sequence(filter(children)?),
        Expr::Choice(children) => Expr::Choice(filter(children)?),
        Expr::Commit { child, message } => Expr::Commit {
            child: Box::new(without(*child, cut)?.ok_or("single-child")?),
            message,
        },
        other => other,
    }))
}

/// The variant tree of an expression, as Java names the nested combinator classes.
fn variants(expression: &Expr) -> String {
    match expression {
        Expr::Sequence(children) | Expr::Choice(children) => {
            let label = if matches!(expression, Expr::Sequence(_)) {
                "Sequence"
            } else {
                "Choice"
            };
            let nested: Vec<_> = children
                .iter()
                .filter(|child| matches!(child, Expr::Sequence(_) | Expr::Choice(_)))
                .map(variants)
                .collect();
            if nested.is_empty() {
                label.into()
            } else {
                format!("{label}({})", nested.join(","))
            }
        }
        _ => "leaf".into(),
    }
}

/// Java ChildOccurs as an adapter over the Expr arity.
fn child_occurs(expression: &Expr) -> &'static str {
    match expression {
        Expr::Sequence(_)
        | Expr::NonOrdered(_)
        | Expr::Choice(_)
        | Expr::LongestChoice(_)
        | Expr::UniqueLongestChoice(_)
        | Expr::PredictiveChoice { .. }
        | Expr::CatchLabels { .. } => "multi",
        Expr::Capture(..)
        | Expr::MetadataCapture(..)
        | Expr::CaptureScope(_)
        | Expr::OwnedCapture(..)
        | Expr::MatchOnly(_)
        | Expr::InvertMatch(_)
        | Expr::StopInvert(_)
        | Expr::StopConsume(_)
        | Expr::Optional(_)
        | Expr::JavaOptional(_)
        | Expr::Repeat { .. }
        | Expr::JavaRepeat { .. }
        | Expr::ZeroMatch(_)
        | Expr::Lookahead { .. }
        | Expr::Commit { .. }
        | Expr::Label { .. } => "single",
        _ => "none",
    }
}

/// Java ParserPrinter simple / detail / mostDetail as an adapter over RuleStack frames: the
/// innermost rule, the dynamic rule path, and the path with start offsets.
fn print_rule_stack(stack: &RuleStack) -> String {
    let names: Vec<_> = stack.frames.iter().map(|frame| frame.rule).collect();
    let starts: Vec<_> = stack
        .frames
        .iter()
        .map(|frame| format!("{}@{}", frame.rule, frame.start))
        .collect();
    format!(
        "parser:{};parser:/{};parser:/{}",
        names.last().unwrap(),
        names.join("/"),
        starts.join("/")
    )
}

// RootParserIndicator recipe: a scope is its own grammar with its own RuleNames, called from
// the outer grammar. Its references resolve only against the scope table.
// Scope ::= '(' Scope ')' | Item | 'x'; Item ::= 'a' is declared only in the outer table.
const SCOPE_NAMES: [&str; 1] = ["Scope"];

fn scope_rules(reference: impl Fn(&str) -> Expr) -> Vec<Rule> {
    vec![rule(
        "Scope",
        Expr::choice([
            seq([w("("), reference("Scope"), w(")")]),
            reference("Item"),
            w("x"),
        ]),
    )]
}

fn scope_by_name() -> &'static SharedGrammar {
    static CELL: OnceLock<SharedGrammar> = OnceLock::new();
    CELL.get_or_init(|| {
        let names = RuleNames::new(SCOPE_NAMES).unwrap();
        assert_eq!(names.get("Item"), None);
        names
            .share(scope_rules(|name| names.reference_or_never(name)))
            .unwrap()
    })
}

fn scope_by_position() -> &'static SharedGrammar {
    static CELL: OnceLock<SharedGrammar> = OnceLock::new();
    CELL.get_or_init(|| {
        share_grammar(scope_rules(|name| {
            SCOPE_NAMES
                .iter()
                .position(|declared| *declared == name)
                .map_or(Expr::JavaEmptyWord, Expr::Rule)
        }))
    })
}

fn call_scope_by_name(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_shared_grammar(scope_by_name(), 0, false)
}

fn call_scope_by_position(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_shared_grammar(scope_by_position(), 0, false)
}

fn scoped_outer(call: fn(&mut ParseContext<'_>) -> ParseResult) -> SharedGrammar {
    let outer = RuleNames::new(["Outer", "Item"]).unwrap();
    assert_eq!(outer.get("Item"), Some(1));
    outer
        .share(vec![
            rule("Outer", seq([Expr::Custom(call)])),
            rule("Item", seq([w("a")])),
        ])
        .unwrap()
}

/// The control: one table for Outer, Scope and Item.
fn unscoped_outer() -> SharedGrammar {
    let names = RuleNames::new(["Scope", "Outer", "Item"]).unwrap();
    let mut rules = scope_rules(|name| names.reference_or_never(name));
    rules.push(rule("Outer", seq([names.reference("Scope").unwrap()])));
    rules.push(rule("Item", seq([w("a")])));
    let grammar = names.share(rules).unwrap();
    assert_eq!(grammar[names.get("Outer").unwrap()].name, "Outer");
    grammar
}

/// The same observation in every memo policy and diagnostics mode, or the differing values.
fn every_option(grammar: &SharedGrammar, root: usize, input: &str) -> String {
    let mut observed = BTreeSet::new();
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options(input, options);
            let parsed = context.parse_shared_grammar(grammar, root, false);
            observed.insert(format!("{}/{}", parsed.is_ok(), context.position()));
        }
    }
    observed.into_iter().collect::<Vec<_>>().join("|")
}

fn scope_observation(call: fn(&mut ParseContext<'_>) -> ParseResult) -> String {
    let scoped = scoped_outer(call);
    // A failure, then a success on the same graph, then the control without the scope.
    [
        every_option(&scoped, 0, "a"),
        every_option(&scoped, 0, "(x)"),
        every_option(&unscoped_outer(), 1, "a"),
    ]
    .join(";")
}

fn record(context: &mut ParseContext<'_>) -> ParseResult {
    context.set_state("seen", true);
    let mode = context.token_mode();
    context.parse_with_token_mode(&w("a"), mode.kind, mode.invert)
}

fn expression_prefix(expression: &Expr, input: &str) -> String {
    let mut context = ParseContext::new(input);
    let parsed = context.parse(expression);
    format!("{}/{}", parsed.is_ok(), context.position())
}

fn observe(id: &str) -> String {
    let is_c = |expression: &Expr| matches!(expression, Expr::Literal("c"));
    match id {
        "factory-class-shared" => identity(shared(), shared()).into(),
        "factory-new-instance" => {
            identity(&share_grammar(by_index()), &share_grammar(by_index())).into()
        }
        "factory-supplier-same" => {
            let supplier = shared;
            identity(supplier(), supplier()).into()
        }
        "factory-supplier-distinct" => identity(shared(), other_shared()).into(),
        "factory-get-supplier" => {
            let first = || shared();
            let second = || shared();
            identity(first(), second()).into()
        }
        "factory-failure-retried" => {
            let first = if flaky().is_err() { "error" } else { "ok" };
            let second = flaky().unwrap();
            format!("{first};instance;{}", identity(second, flaky().unwrap()))
        }
        "factory-kind" => {
            let tagged = kinded(Some("Other"));
            format!(
                "{};{};{}",
                identity(&tagged, &kinded(Some("Other"))),
                identity(&tagged, &kinded(Some("ChoicedOperand"))),
                identity(&tagged, &kinded(None))
            )
        }
        "lazy-construction-cycle" => prefix(&share_grammar(by_index()), 1, "((x))"),
        "lazy-delegate-built-once" => {
            let (grammar, body) = delegate_graph("rule-names");
            for _ in 0..3 {
                assert_eq!(delegate_prefix(&grammar, &body, "é"), "true/1");
            }
            body.builds.load(Ordering::SeqCst).to_string()
        }
        "lazy-delegate-failure-retried" => {
            let (grammar, _) = delegate_graph("rule-names");
            let body = LazyBody::new(|attempt| {
                assert_ne!(attempt, 0, "first build fails");
                list_position("Elem").repeat_java(0, None)
            });
            let first = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                delegate_prefix(&grammar, &body, "é")
            }));
            let first = if first.is_err() { "error" } else { "ok" };
            format!(
                "{first};{};{}",
                delegate_prefix(&grammar, &body, "é"),
                body.builds.load(Ordering::SeqCst)
            )
        }
        "lazy-children-once" => {
            for _ in 0..3 {
                assert_eq!(prefix(lazy(), 0, "a"), "true/1");
            }
            LAZY_BUILDS.load(Ordering::SeqCst).to_string()
        }
        "lazy-children-failure-retried" => {
            let supplied = Supplied::new(3);
            let cell = OnceLock::new();
            let get = || -> Result<&Expr, &'static str> {
                if let Some(children) = cell.get() {
                    return Ok(children);
                }
                let children = (0..3)
                    .map(|index| {
                        if supplied.first_of_second(index) {
                            Err(FIRST_SUPPLY_FAILS)
                        } else {
                            Ok(abc(index))
                        }
                    })
                    .collect::<Result<Vec<_>, _>>()?;
                Ok(cell.get_or_init(|| Expr::sequence(children)))
            };
            let first = match get() {
                Ok(built) => expression_prefix(built, "abc"),
                Err(_) => "error".into(),
            };
            let built = get().unwrap();
            format!(
                "{first};{};{};{}",
                expression_prefix(built, "abc"),
                children(built).len(),
                supplied.calls()
            )
        }
        "lazy-children-error-retried" => {
            let supplied = Supplied::new(3);
            let cell = OnceLock::new();
            let get = || {
                cell.get_or_init(|| {
                    Expr::sequence((0..3).map(|index| {
                        if supplied.first_of_second(index) {
                            std::panic::resume_unwind(Box::new(FIRST_SUPPLY_FAILS));
                        }
                        abc(index)
                    }))
                })
            };
            let first = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                expression_prefix(get(), "abc")
            }));
            let first = first.unwrap_or_else(|_| "error".into());
            format!(
                "{first};{};{};{}",
                expression_prefix(get(), "abc"),
                children(get()).len(),
                supplied.calls()
            )
        }
        "lazy-children-empty" => {
            let cell = OnceLock::new();
            let built = cell.get_or_init(|| Expr::sequence([]));
            format!(
                "{};{};{}",
                expression_prefix(built, ""),
                children(built).len(),
                same(cell.get().unwrap(), cell.get().unwrap())
            )
        }
        "lazy-children-published-once" => {
            let supplied = Supplied::new(3);
            let cell = OnceLock::new();
            let get = || {
                cell.get_or_init(|| {
                    Expr::sequence((0..3).map(|index| {
                        supplied.first_of_second(index);
                        abc(index)
                    }))
                })
            };
            for _ in 0..3 {
                assert_eq!(expression_prefix(get(), "abc"), "true/3");
            }
            format!(
                "{};{};{}",
                supplied.calls(),
                same(get(), get()),
                same(&children(get())[1], &children(get())[1])
            )
        }
        "shared-graph-independent-contexts" => {
            let grammar = shared();
            [
                prefix(grammar, 0, "x!"),
                prefix(grammar, 0, "y"),
                prefix(grammar, 0, "x?"),
            ]
            .join(";")
        }
        "name-context-duplicate" => match RuleNames::new(["Root", "Item", "Item"]) {
            Err(RuleNameError::Duplicate {
                name,
                first,
                second,
            }) => {
                assert_eq!((first, second), (1, 2));
                format!("duplicate:{name}")
            }
            other => format!("{other:?}"),
        },
        "name-context-reused-instance" => {
            let declared = ["Root", "Left", "Right", "Item"];
            let names = RuleNames::new(declared).unwrap();
            let item = names.reference("Item").unwrap();
            let grammar = names
                .share(vec![
                    rule(
                        "Root",
                        seq([
                            names.reference("Left").unwrap(),
                            names.reference("Right").unwrap(),
                        ]),
                    ),
                    rule("Left", seq([item.clone()])),
                    rule("Right", seq([item])),
                    rule("Item", seq([w("a")])),
                ])
                .unwrap();
            assert_eq!(prefix(&grammar, 0, "aa"), "true/2");
            names
                .get("Item")
                .map_or("absent".into(), |index| declared[index].into())
        }
        "name-context-unregistered" => {
            let names = RuleNames::new(["Root"]).unwrap();
            names
                .get("Missing")
                .map_or("absent".into(), |index| index.to_string())
        }
        "by-name-first-match" => {
            let declared = ["Root", "Item", "Item"];
            assert!(matches!(
                RuleNames::new(declared),
                Err(RuleNameError::Duplicate { .. })
            ));
            let first = declared.iter().position(|name| *name == "Item").unwrap();
            let grammar = share_grammar(vec![
                rule("Root", seq([Expr::Rule(first)])),
                rule("Item", seq([w("a")])),
                rule("Item", seq([w("b")])),
            ]);
            format!("{};{}", prefix(&grammar, 0, "a"), prefix(&grammar, 0, "b"))
        }
        "by-name-late-registration" => {
            let before = RuleNames::new(["Root"]).unwrap();
            let grammar = before
                .share(vec![rule("Root", seq([before.reference_or_never("Late")]))])
                .unwrap();
            let first = prefix(&grammar, 0, "x");
            // Rebuilding re-resolves the name, as ReferenceByNameParser re-searches a miss.
            let after = RuleNames::new(["Root", "Late"]).unwrap();
            let late = after.reference("Late").unwrap();
            let grammar = after
                .share(vec![
                    rule("Root", seq([after.reference_or_never("Late"), late])),
                    rule("Late", seq([w("x")])),
                ])
                .unwrap();
            format!("{first};{}", prefix(&grammar, 0, "xx"))
        }
        "predicate-miss-cached" => {
            let declared = ["Root"];
            let resolved = declared
                .iter()
                .position(|name| *name == "Late")
                .map_or(Expr::JavaEmptyWord, Expr::Rule);
            let grammar = share_grammar(vec![rule("Root", seq([resolved.clone()]))]);
            let first = prefix(&grammar, 0, "x");
            // Extending the rules keeps the expression resolved once, like ReferenceParser's cache.
            let grammar = share_grammar(vec![
                rule("Root", seq([resolved, Expr::Rule(1)])),
                rule("Late", seq([w("x")])),
            ]);
            format!("{first};{}", prefix(&grammar, 0, "xx"))
        }
        "predicate-evaluated-once" => {
            let evaluations = Cell::new(0);
            let declared = ["Root", "Item"];
            let item = declared
                .iter()
                .position(|name| {
                    evaluations.set(evaluations.get() + 1);
                    *name == "Item"
                })
                .unwrap();
            let grammar = share_grammar(vec![
                rule("Root", seq([Expr::Rule(item)])),
                rule("Item", seq([w("a")])),
            ]);
            assert!(evaluations.get() > 0);
            evaluations.set(0);
            assert_eq!(prefix(&grammar, 0, "a"), "true/1");
            assert_eq!(prefix(&grammar, 0, "b"), "false/0");
            evaluations.get().to_string()
        }
        "referencer-uncached" => {
            let lookup = |rules: &[Rule]| rules.iter().position(|rule| rule.name == "Late");
            let mut built = vec![rule("Root", seq([w("x")]))];
            let before = if lookup(&built).is_some() {
                "present"
            } else {
                "absent"
            };
            built.push(rule("Late", seq([w("x")])));
            let after = if lookup(&built).is_some() {
                "present"
            } else {
                "absent"
            };
            format!("{before};{after}")
        }
        "unregistered-strict" => match RuleNames::new(NAMES).unwrap().reference("Missing") {
            Err(RuleNameError::Unregistered(name)) => format!("unregistered:{name}"),
            other => format!("{other:?}"),
        },
        "share-mismatch" => {
            let names = RuleNames::new(["Root", "Item", "Leaf"]).unwrap();
            match names.share(vec![
                rule("Root", w("r")),
                rule("Leaf", w("a")),
                rule("Item", w("x")),
            ]) {
                Err(RuleNameError::Mismatch {
                    index,
                    declared: Some(declared),
                    actual: Some(actual),
                }) => format!("mismatch:{index}:{declared}:{actual}"),
                other => format!("{:?}", other.map(|_| ())),
            }
        }
        "left-recursion" => {
            let grammar = share_grammar(vec![rule("A", seq([Expr::Rule(0), w("x")]))]);
            parse_detailed_shared(&grammar, 0, false, "xx")
                .err()
                .map_or("ok".into(), |diagnostic| diagnostic.kind.to_string())
        }
        "source-before-parse" => {
            let context = ParseContext::new("a");
            let mode = context.token_mode();
            format!("{}/{}", kind_name(mode.kind), mode.invert)
        }
        "destination-without-source" | "destination-under-source" => {
            let observer = Expr::Custom(observe_invert);
            let expression = if id == "destination-under-source" {
                observer.invert_match()
            } else {
                observer
            };
            let input = if id == "destination-under-source" {
                "b"
            } else {
                "a"
            };
            let mut context = ParseContext::new(input);
            context
                .parse_with_token_mode(&expression, TokenKind::Consumed, false)
                .unwrap();
            assert_eq!(context.position(), 1);
            context.state::<bool>("invert").unwrap().to_string()
        }
        "token-parsed-by" | "parsers-specifier" => {
            let grammar = shared();
            let mut context = ParseContext::new("a");
            let parsed = context.parse_shared_grammar(grammar, LEAF, false).unwrap();
            let rule_index = context.node(parsed.root_node().unwrap()).unwrap().rule;
            if id == "token-parsed-by" {
                format!("{};{}", rule_index == LEAF, rule_index == 0)
            } else {
                let leaf_rules = HashSet::from([LEAF]);
                let root_rules = HashSet::from([0]);
                format!(
                    "{};{}",
                    leaf_rules.contains(&rule_index),
                    root_rules.contains(&rule_index)
                )
            }
        }
        "new-filtered" => {
            let Expr::Sequence(mut children) = seq([w("a"), w("b"), w("c")]) else {
                unreachable!()
            };
            children.retain(|child| !is_c(child));
            expression_prefix(&Expr::Sequence(children), "ab")
        }
        "new-without-recursive" => {
            let expression = seq([seq([w("a"), w("c"), w("b")]), w("c")]);
            expression_prefix(&without(expression, &is_c).unwrap().unwrap(), "ab")
        }
        "new-without-recursive-mixed" => {
            let expression = seq([Expr::choice([w("a"), w("c"), w("z")]), w("b"), w("c")]);
            let filtered = without(expression, &is_c).unwrap().unwrap();
            format!(
                "{};{};{};{}",
                variants(&filtered),
                expression_prefix(&filtered, "ab"),
                expression_prefix(&filtered, "zb"),
                expression_prefix(&filtered, "cb")
            )
        }
        "new-without-recursive-single-child" => {
            let commit = || Expr::Commit {
                child: Box::new(w("c")),
                message: "m",
            };
            let emptied = match without(seq([commit(), w("b")]), &is_c) {
                Err(error) => format!("error:{error}"),
                Ok(other) => format!("{other:?}"),
            };
            let is_commit = |expression: &Expr| matches!(expression, Expr::Commit { .. });
            let filtered = without(seq([commit(), w("b")]), &is_commit)
                .unwrap()
                .unwrap();
            format!("{emptied};{}", expression_prefix(&filtered, "b"))
        }
        "child-occurs" => format!(
            "{};{};{}",
            child_occurs(&w("a")),
            child_occurs(&w("a").match_only()),
            child_occurs(&seq([w("a")]))
        ),
        "parser-printer" => {
            let grammar = share_grammar(vec![
                rule("Root", seq([Expr::Rule(1)])),
                rule("Item", seq([w("x")])),
            ]);
            let options = ParseOptions::with_memoization(Memoization::Off)
                .with_diagnostics(Diagnostics::Detailed);
            let mut context = ParseContext::with_options("y", options);
            assert!(context.parse_shared_grammar(&grammar, 0, false).is_err());
            print_rule_stack(&context.rule_stack().unwrap())
        }
        "name-kinds" => {
            let fqcn = "org.unlaxer.parser.elementary.WordParser";
            let duplicate = match RuleNames::new([fqcn, fqcn]) {
                Err(RuleNameError::Duplicate { name, .. }) => format!("duplicate:{name}"),
                other => format!("{other:?}"),
            };
            let names = RuleNames::new(["x"]).unwrap();
            format!(
                "{duplicate};{}",
                names.get(&String::from("x")) == names.get("x")
            )
        }
        "root-indicator-scope-by-name" => scope_observation(call_scope_by_name),
        "root-indicator-scope-predicate" => scope_observation(call_scope_by_position),
        "root-indicator-root" => format!(
            "{};{}",
            scope_by_name()[0].name,
            RuleNames::new(SCOPE_NAMES).unwrap().get("Outer").is_some()
        ),
        "root-indicator-rule-stack" => {
            let options = ParseOptions::with_memoization(Memoization::Off)
                .with_diagnostics(Diagnostics::Detailed);
            let mut context = ParseContext::with_options("y", options);
            let grammar = scoped_outer(call_scope_by_name);
            assert!(context.parse_shared_grammar(&grammar, 0, false).is_err());
            render(context.rule_stack())
        }
        "by-name-hit-cached" => {
            let build = |item: &'static str| {
                let names = RuleNames::new(["Use", "Item"]).unwrap();
                names
                    .share(vec![
                        rule("Use", seq([names.reference("Item").unwrap()])),
                        rule("Item", seq([w(item)])),
                    ])
                    .unwrap()
            };
            let resolved = build("a");
            let first = prefix(&resolved, 0, "a");
            // The change builds a new grammar; the existing one keeps its resolution.
            let changed = build("b");
            format!(
                "{first};{};{};{}",
                prefix(&resolved, 0, "b"),
                prefix(&resolved, 0, "a"),
                prefix(&changed, 0, "b")
            )
        }
        "root-cache-after-reparent" => {
            let alone = RuleNames::new(["Inner"]).unwrap();
            let inner = rule("Inner", seq([alone.reference_or_never("Late")]));
            let before = prefix(&alone.share(vec![inner.clone()]).unwrap(), 0, "x");
            // Composing the resolved rule into a larger grammar keeps its resolution.
            let names = RuleNames::new(["NewRoot", "Inner", "Late"]).unwrap();
            let compose = |inner: Rule| {
                names
                    .share(vec![
                        rule("NewRoot", seq([Expr::Rule(1), Expr::Rule(2)])),
                        inner,
                        rule("Late", seq([w("x")])),
                    ])
                    .unwrap()
            };
            let attached = rule("Inner", seq([names.reference_or_never("Late")]));
            format!(
                "{before};{};{}",
                prefix(&compose(inner), 1, "x"),
                prefix(&compose(attached), 1, "x")
            )
        }
        "rule-names-stale-id" => {
            let old = RuleNames::new(["Use", "Item"]).unwrap();
            let body = seq([old.reference("Item").unwrap()]);
            let rebuilt = RuleNames::new(["Use", "Other", "Item"]).unwrap();
            let grammar = rebuilt
                .share(vec![
                    rule("Use", body),
                    rule("Other", seq([w("b")])),
                    rule("Item", seq([w("a")])),
                ])
                .unwrap();
            format!("{};{}", prefix(&grammar, 0, "b"), prefix(&grammar, 0, "a"))
        }
        "share-out-of-range" => {
            let foreign = RuleNames::new(["A", "B", "C"])
                .unwrap()
                .reference("C")
                .unwrap();
            let rules = || {
                vec![rule(
                    "A",
                    seq([w("a"), Expr::choice([foreign.clone(), w("x")])]),
                )]
            };
            let checked = match RuleNames::new(["A"]).unwrap().share(rules()) {
                Err(RuleNameError::OutOfRange {
                    rule,
                    reference,
                    rules,
                }) => format!("out-of-range:{rule}:{reference}:{rules}"),
                other => format!("{:?}", other.map(|_| ())),
            };
            // Unchecked sharing turns the bad reference into a silent choice fallback.
            format!(
                "{checked};unchecked:{}",
                prefix(&share_grammar(rules()), 0, "ax")
            )
        }
        "class-and-kind-equality" => format!(
            "{};{};{}",
            Arc::ptr_eq(&kinded(None), &kinded(None)),
            Arc::ptr_eq(&kinded(None), &kinded(Some("Other"))),
            Arc::ptr_eq(&kinded(Some("Other")), &kinded(Some("Other")))
        ),
        "shared-graph-context-state" => {
            let grammar = share_grammar(vec![rule("Recording", seq([Expr::Custom(record)]))]);
            let mut first = ParseContext::new("a");
            first.parse_shared_grammar(&grammar, 0, false).unwrap();
            let seen = first.state::<bool>("seen").copied();
            let mut fresh = ParseContext::new("a");
            let before = if fresh.state::<bool>("seen").is_some() {
                "present"
            } else {
                "absent"
            };
            let parsed = fresh.parse_shared_grammar(&grammar, 0, false);
            format!(
                "{};{before};{}/{}",
                seen.map_or("absent".into(), |value| value.to_string()),
                parsed.is_ok(),
                fresh.position()
            )
        }
        _ => panic!("unimplemented observation {id}"),
    }
}

#[test]
fn construction_and_identity_observations_match_the_shared_table() {
    let rows = rows(
        include_str!("../../../conformance/parser-construction/construction.tsv"),
        4,
    );
    assert_eq!(rows.len(), 72);
    let ids: HashSet<_> = rows.iter().map(|row| row[0]).collect();
    assert_eq!(ids.len(), rows.len());
    let mut failures = Vec::new();
    for row in &rows {
        assert!(!row[3].trim().is_empty(), "{}", row[0]);
        assert!(row[1] != "-" || row[2] != "-", "{}", row[0]);
        if row[2] == "-" {
            continue;
        }
        let actual = observe(row[0]);
        if actual != row[2] {
            failures.push(format!("{}: expected {} actual {actual}", row[0], row[2]));
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}
