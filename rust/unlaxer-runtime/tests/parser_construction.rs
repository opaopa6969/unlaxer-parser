//! Parser construction and rule references (#845) against the shared Java/Rust oracle in
//! conformance/parser-construction. Mirrors ParserConstructionParityTest.java: every Rust
//! construction route builds one grammar and must reproduce cases.tsv.
use std::cell::Cell;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::fmt::Write;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

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
                    let input = decode(c[2]);
                    let options =
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                    let mut context = ParseContext::with_options(&input, options);
                    let root = NAMES.iter().position(|name| *name == c[1]).unwrap();
                    let parsed = context.parse_with_token_mode(
                        &Grammar(grammar(), root),
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
                    if actual != expected {
                        failures.push(format!(
                            "{label}\n  expected {expected}\n  actual   {actual}"
                        ));
                    }
                    let tree: Vec<_> = committed
                        .iter()
                        .map(|&node| shape(&mut context, node))
                        .collect();
                    let tree = tree.join(";");
                    // NotProbe is a different expression in the Custom-source routes: Custom is
                    // CST-transparent, invert_match keeps a NotPropagatableSource boundary.
                    let source = if c[1] == "NotProbe" && CUSTOM_SOURCE.contains(&variant_name) {
                        "custom"
                    } else {
                        "builtin"
                    };
                    let first = shapes
                        .entry(format!("{}/{memo:?}/{diagnostics:?}/{source}", c[0]))
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
        "lazy-children-once" => {
            for _ in 0..3 {
                assert_eq!(prefix(lazy(), 0, "a"), "true/1");
            }
            LAZY_BUILDS.load(Ordering::SeqCst).to_string()
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
    assert_eq!(rows.len(), 56);
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
