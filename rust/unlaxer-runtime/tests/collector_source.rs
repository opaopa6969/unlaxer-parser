use std::cell::RefCell;
use std::sync::{Arc, Mutex};
use unlaxer_runtime::semantic_predicates::Bindings;
use unlaxer_runtime::token::{TokenKind, TokenSource};
use unlaxer_runtime::{
    Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult,
    RecoveryMode, Rule, Span,
};

thread_local! {
    static OBSERVED: RefCell<String> = const { RefCell::new(String::new()) };
}

fn observed() -> String {
    OBSERVED.with(|observed| observed.borrow().clone())
}
fn set_observed(value: String) {
    OBSERVED.with(|observed| *observed.borrow_mut() = value);
}

fn here(context: &mut ParseContext<'_>) -> ParseResult {
    let at = context.position();
    Ok(ParseMatch::empty(Span { start: at, end: at }))
}
/// What capture_owned("w") stored, and the consumed/matched cursors after it.
fn observe_capture(context: &mut ParseContext<'_>) -> ParseResult {
    let value = match context.captured_source("w") {
        None => "none".into(),
        Some(TokenSource::Input(span)) => {
            let text = context.text(*span).unwrap();
            format!("INPUT@{}-{}'{text}'", span.start, span.end)
        }
        Some(TokenSource::Projected { span, text }) => {
            format!("PROJECTED@{}-{}'{text}'", span.start, span.end)
        }
        Some(TokenSource::Generated { anchor, text }) => {
            format!("GENERATED@{anchor}-{anchor}'{text}'")
        }
    };
    set_observed(format!(
        "{value}\t{}/{}",
        context.position(),
        context.matched_position()
    ));
    here(context)
}
fn observe_field(context: &mut ParseContext<'_>) -> ParseResult {
    let span = context.capture_spans("f").last().copied().unwrap();
    set_observed(format!("{}-{}", span.start, span.end));
    here(context)
}

/// A host projection "Z" / "" whose logical start is 0, away from the cursor; it consumes nothing.
fn projected<const EMPTY: bool>(context: &mut ParseContext<'_>) -> ParseResult {
    let span = Span {
        start: 0,
        end: usize::from(!EMPTY),
    };
    let text = if EMPTY { "" } else { "Z" };
    let id = context
        .add_token_node(
            0,
            TokenKind::Consumed,
            TokenSource::Projected {
                span,
                text: text.into(),
            },
        )
        .ok_or_else(|| context.error("token"))?;
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// A host projection "Z" (of the matchOnly kind when `MATCH_ONLY`) that consumes `CONSUME` code points.
fn projected_kind<const MATCH_ONLY: bool, const CONSUME: usize>(
    context: &mut ParseContext<'_>,
) -> ParseResult {
    let span = Span { start: 0, end: 1 };
    let kind = if MATCH_ONLY {
        TokenKind::MatchOnly
    } else {
        TokenKind::Consumed
    };
    let id = context
        .add_token_node(
            0,
            kind,
            TokenSource::Projected {
                span,
                text: "Z".into(),
            },
        )
        .ok_or_else(|| context.error("token"))?;
    if CONSUME > 0 && !context.advance(CONSUME) {
        return Err(context.error("input"));
    }
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}
/// 'c' returned in the virtual consumed kind, whatever the caller's kind.
fn virtual_c(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_with_token_mode(&Expr::literal("c"), TokenKind::VirtualConsumed, false)
}

/// vci / mi / vmi: a native host that reads 'c' at the cursor, publishes only its own token of
/// that input in the kind `KIND` (VirtualConsumed / MatchOnly / VirtualMatchOnly), and advances 1.
fn input_c<const KIND: u8>(context: &mut ParseContext<'_>) -> ParseResult {
    let start = context.position();
    let span = Span {
        start,
        end: start + 1,
    };
    if context.text(span) != Some("c") {
        return Err(context.error("c"));
    }
    let kind = match KIND {
        0 => TokenKind::VirtualConsumed,
        1 => TokenKind::MatchOnly,
        _ => TokenKind::VirtualMatchOnly,
    };
    let id = context
        .add_token_node(0, kind, TokenSource::Input(span))
        .ok_or_else(|| context.error("token"))?;
    if !context.advance(1) {
        return Err(context.error("input"));
    }
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// h0 / hx: a host that returns an empty token of the consumed kind over the input at the cursor /
/// at 0 (its ParseMatch stays at the cursor).
fn host_empty<const AT_CURSOR: bool>(context: &mut ParseContext<'_>) -> ParseResult {
    let at = context.position();
    let token = if AT_CURSOR { at } else { 0 };
    let id = context
        .add_token_node(
            0,
            TokenKind::Consumed,
            TokenSource::Input(Span {
                start: token,
                end: token,
            }),
        )
        .ok_or_else(|| context.error("token"))?;
    Ok(ParseMatch {
        span: Span { start: at, end: at },
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// hv: a host that returns an empty token of the virtual consumed kind over the input at the cursor.
fn host_empty_virtual(context: &mut ParseContext<'_>) -> ParseResult {
    let at = context.position();
    let span = Span { start: at, end: at };
    let id = context
        .add_token_node(0, TokenKind::VirtualConsumed, TokenSource::Input(span))
        .ok_or_else(|| context.error("token"))?;
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// h1 / hc: a host that returns a token of the consumed kind over 'c' at the cursor and advances
/// 1 / does not advance.
fn host_c<const ADVANCE: bool>(context: &mut ParseContext<'_>) -> ParseResult {
    let start = context.position();
    let span = Span {
        start,
        end: start + 1,
    };
    if context.text(span) != Some("c") {
        return Err(context.error("c"));
    }
    let id = context
        .add_token_node(0, TokenKind::Consumed, TokenSource::Input(span))
        .ok_or_else(|| context.error("token"))?;
    if ADVANCE && !context.advance(1) {
        return Err(context.error("input"));
    }
    Ok(ParseMatch {
        span: if ADVANCE {
            span
        } else {
            Span { start, end: start }
        },
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// LX: Java LexicalTokenParser("LX", z?), which matches nothing here.
fn lexical_optional_z() -> Expr {
    use unlaxer_runtime::lexical::{LexicalExpression, Op};
    let leaf = |op, text| LexicalExpression {
        op,
        text,
        min: 0,
        max: 0,
        children: vec![],
    };
    Expr::Lexical(
        "LX",
        LexicalExpression {
            min: 0,
            max: 1,
            children: vec![leaf(Op::LITERAL, "z")],
            ..leaf(Op::REPEAT, "")
        },
    )
}

/// A shape of conformance/collector-source/full.tsv, as Java builds the same name, and the bodies
/// of its rules in order as rules 1, 2, ... (`Rule[Rule[X]]` is rule 1 whose body is rule 2).
fn shape(name: &str) -> (Expr, Vec<Expr>) {
    let mut rules = vec![];
    let expression = shape_into(name, &mut rules);
    (expression, rules)
}
fn shape_into(name: &str, rules: &mut Vec<Expr>) -> Expr {
    let Some(open) = name.find('[') else {
        return match name {
            "MO" => Expr::literal("c").match_only(),
            "&c" => Expr::Lookahead {
                child: Box::new(Expr::Literal("c")),
                positive: true,
            },
            "!z" => Expr::Lookahead {
                child: Box::new(Expr::Literal("z")),
                positive: false,
            },
            "E" => Expr::JavaEmpty,
            "proj" => Expr::Custom(projected::<false>),
            "proj0" => Expr::Custom(projected::<true>),
            "projc" => Expr::Custom(projected_kind::<false, 1>),
            "projm" => Expr::Custom(projected_kind::<true, 0>),
            "vc" => Expr::Custom(virtual_c),
            "vci" => Expr::Custom(input_c::<0>),
            "mi" => Expr::Custom(input_c::<1>),
            "vmi" => Expr::Custom(input_c::<2>),
            "h0" => Expr::Custom(host_empty::<true>),
            "hx" => Expr::Custom(host_empty::<false>),
            "hn" => Expr::Custom(here),
            "hv" => Expr::Custom(host_empty_virtual),
            "h1" => Expr::Custom(host_c::<true>),
            "hc" => Expr::Custom(host_c::<false>),
            "EL" => Expr::JavaEmptyLiteral,
            "LX" => lexical_optional_z(),
            _ => Expr::literal(Box::leak(name.to_owned().into_boxed_str())),
        };
    };
    let inner = &name[open + 1..name.len() - 1];
    if &name[..open] == "Rule" {
        let index = rules.len();
        rules.push(Expr::JavaEmpty);
        rules[index] = shape_into(inner, rules);
        return Expr::Rule(index + 1);
    }
    let mut children = vec![];
    let (mut depth, mut start) = (0, 0);
    for (index, c) in inner.char_indices() {
        match c {
            '[' => depth += 1,
            ']' => depth -= 1,
            ',' if depth == 0 => {
                children.push(shape_into(&inner[start..index], rules));
                start = index + 1;
            }
            _ => {}
        }
    }
    children.push(shape_into(&inner[start..], rules));
    match &name[..open] {
        "Choice" => Expr::choice(children),
        "Chain" => Expr::sequence(children),
        "Opt" => children.remove(0).optional_java(),
        "Rep" => children.remove(0).repeat_java(0, None),
        "Rec" => children.remove(0).recover(RecoveryMode::Sync, [";"], "m"),
        "Ord" => Expr::NonOrdered(children),
        "LC" => Expr::longest_choice(children),
        "Own" => children.remove(0).capture_owned("o"),
        "Val" => children.remove(0).text_value(),
        "VB" => children.remove(0).value_boundary(),
        "IB" => Expr::InputCaptureBoundary(
            unlaxer_runtime::capture_tree::Kind::Value,
            Box::new(children.remove(0)),
        ),
        "Cmt" => children.remove(0).commit(),
        "Lbl" => children.remove(0).label("l"),
        "SI" => children.remove(0).stop_invert(),
        "SP" => Expr::SemanticPredicate {
            child: Box::new(children.remove(0)),
            name: "t",
        },
        "SPS" => Expr::SemanticPredicateScope {
            child: Box::new(children.remove(0)),
            requirements: vec![],
        },
        "CS" => children.remove(0).capture_scope(),
        "NRS" => Expr::NameResolutionScope {
            child: Box::new(children.remove(0)),
            requirements: vec![],
        },
        "SC" => children.remove(0).stop_consume(),
        // Java Occurs with a terminator: ZeroOrMore(X, T), OneOrMore(X).newWithTerminator(T) and
        // new Repeat(X, M, N).newWithTerminator(T).
        head @ ("RepT" | "Rep1T") => {
            let terminator = children.remove(1);
            children
                .remove(0)
                .repeat_java_until(terminator, usize::from(head == "Rep1T"), None)
        }
        head if head.len() == 5 && head.starts_with("Occ") => {
            let bound = |index: usize| usize::from(head.as_bytes()[index] - b'0');
            let terminator = children.remove(1);
            children
                .remove(0)
                .repeat_java_until(terminator, bound(3), Some(bound(4)))
        }
        _ => panic!("{name}"),
    }
}

fn after(prefix: &'static str, rest: Vec<Expr>) -> Expr {
    let mut all = vec![];
    if !prefix.is_empty() {
        all.push(Expr::literal(prefix));
    }
    all.extend(rest);
    Expr::sequence(all)
}

#[derive(Clone, Copy, Debug)]
enum Entry {
    Full,
    /// The full-input entry with the Java contract chosen by a `.stop_consume()` root.
    StopConsume,
    /// `ParseContext::parse`, which is Classic only when the graph asks for it.
    Context,
    Classic,
}
const ENTRIES: [Entry; 4] = [
    Entry::Full,
    Entry::StopConsume,
    Entry::Context,
    Entry::Classic,
];

thread_local! {
    static RULES: RefCell<Option<unlaxer_runtime::SharedGrammar>> = const { RefCell::new(None) };
}
fn enter_rules(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = RULES.with(|rules| rules.borrow().clone()).expect("rules");
    context.parse_shared_grammar(&grammar, 0, false)
}
/// Rule 0 with body `expression`, followed by the rule `bodies` of its shape.
fn grammar(expression: Expr, bodies: Vec<Expr>) -> unlaxer_runtime::SharedGrammar {
    assert!(bodies.len() <= 3);
    let mut rules = vec![Rule {
        name: "start",
        expression,
    }];
    for (expression, name) in bodies.into_iter().zip(["r", "s", "t"]) {
        rules.push(Rule { name, expression });
    }
    unlaxer_runtime::share_grammar(rules)
}

/// The bindings of a root SemanticPredicateScope(["t"]) whose predicate t always holds.
fn always() -> Bindings {
    Bindings::new().with("t", |_| true).unwrap()
}

/// `expression` under a root SemanticPredicateScope that also requires t (see [`always`]).
fn scoped(expression: Expr) -> Expr {
    match expression {
        Expr::SemanticPredicateScope {
            child,
            mut requirements,
        } => {
            requirements.push("t");
            Expr::SemanticPredicateScope {
                child,
                requirements,
            }
        }
        other => Expr::SemanticPredicateScope {
            child: Box::new(other),
            requirements: vec!["t"],
        },
    }
}

/// Whole-input acceptance and the sticky predicate failure of `expression` (whose shape has the
/// rule `bodies`) at `entry`, under a root scope of t when `scope` (`bindings` then bind t too).
fn run(
    entry: Entry,
    (expression, bodies): (Expr, Vec<Expr>),
    input: &str,
    options: ParseOptions,
    bindings: Option<&Bindings>,
    scope: bool,
) -> (bool, String) {
    let default = always();
    let (expression, bindings) = if scope {
        (scoped(expression), Some(bindings.unwrap_or(&default)))
    } else {
        (expression, bindings)
    };
    match entry {
        Entry::Full | Entry::StopConsume => {
            let expression = if matches!(entry, Entry::StopConsume) {
                expression.stop_consume()
            } else {
                expression
            };
            let grammar = grammar(expression, bodies);
            let result = match bindings {
                Some(bindings) => unlaxer_runtime::parse_detailed_shared_with_semantic_predicates(
                    &grammar, 0, false, input, options, bindings,
                ),
                None => unlaxer_runtime::parse_detailed_shared_with_options(
                    &grammar, 0, false, input, options,
                ),
            };
            let failure = match &result {
                Err(error) if error.kind.starts_with("predicate") => {
                    format!("{}@{}", error.kind, error.offset)
                }
                _ => "-".into(),
            };
            (result.is_ok(), failure)
        }
        Entry::Context | Entry::Classic => {
            // The rules are entered as a child grammar, which chooses Classic as ParseContext::parse.
            let expression = if bodies.is_empty() {
                expression
            } else {
                let grammar = grammar(expression, bodies);
                RULES.with(|rules| *rules.borrow_mut() = Some(grammar));
                Expr::Custom(enter_rules)
            };
            let mut context = match bindings {
                Some(bindings) => ParseContext::with_semantic_predicates(input, bindings, options),
                None => ParseContext::with_options(input, options),
            };
            let ok = if matches!(entry, Entry::Classic) {
                context.parse_with_token_mode(&expression, TokenKind::Consumed, false)
            } else {
                context.parse(&expression)
            }
            .is_ok();
            let failure = context
                .semantic_predicate_failure()
                .map_or("-".into(), |failure| {
                    format!("{}@{}", failure.kind, failure.span.start)
                });
            (ok && context.position() == input.chars().count(), failure)
        }
    }
}

fn options() -> Vec<ParseOptions> {
    [Memoization::Off, Memoization::SafeFailures]
        .into_iter()
        .flat_map(|memo| {
            [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ]
            .map(|diagnostics| ParseOptions::with_memoization(memo).with_diagnostics(diagnostics))
        })
        .collect()
}

/// Every Rust entry returns what Java's consumed collectors return (#1023 A/B): a Classic child
/// of another kind (MatchOnly) contributes nothing to Sequence / optional_java / repeat_java /
/// recover / @f / a rule whose body is not a Choice (a Java LazyChain), and a host projection is
/// collected in order instead of the input at the cursor. owned capture, Replay, the predicate
/// candidate, @f and InputCaptureBoundary all read that source.
#[test]
fn collectors_return_the_consumed_collection_on_every_entry() {
    check_table(
        include_str!("../../../conformance/collector-source/full.tsv"),
        312,
        false,
    );
}

/// A host token of another kind over the input (vci / mi / vmi) is excluded by its kind, not by
/// how it was produced: every entry returns what Java returns for the same host (#1023).
#[test]
fn input_backed_tokens_of_another_kind_are_excluded_on_every_entry() {
    check_table(
        include_str!("../../../conformance/collector-source/virtual-input.tsv"),
        129,
        false,
    );
}

/// Java Occurs with a terminator (Rust repeat_java_until) is a consumed collector of its bodies
/// and its committed terminator on every entry, also when it stays native (#1023 x #1012).
#[test]
fn terminated_repetition_returns_the_consumed_collection_on_every_entry() {
    check_table(
        include_str!("../../../conformance/collector-source/terminated.tsv"),
        177,
        false,
    );
}

/// A Java collector returns a token of the consumed kind even when it is empty, which starts the
/// collection of its parent there; a PEG assertion returns none (#1023). A rule collects the plain
/// input of its body as well.
#[test]
fn empty_tokens_start_the_collection_on_every_entry() {
    check_table(
        include_str!("../../../conformance/collector-source/empty-token.tsv"),
        906,
        false,
    );
}

/// SemanticPredicate, SemanticPredicateScope, NameResolutionScope and capture_scope return what
/// their child returned, as Java's (none of them a CollectingParser), and a host what it returned:
/// a later collection collects its consumed tokens over the input at their own positions, no
/// token leaves "" at the consumed cursor (#1023).
#[test]
fn wrappers_and_hosts_return_what_they_returned_on_every_entry() {
    check_table(
        include_str!("../../../conformance/collector-source/returned-token.tsv"),
        915,
        true,
    );
}

/// The capture-less spans of the shapes of returned-token.tsv (#1023).
#[test]
fn wrappers_and_hosts_return_the_capture_less_spans() {
    check_boundary_table(
        include_str!("../../../conformance/collector-source/returned-token-boundary.tsv"),
        915,
        true,
    );
}

/// A lexical leaf that matches nothing (JavaEmptyLiteral, Expr::Lexical) returns an empty token
/// of the consumed kind, as Java Lexing.LiteralParser("") / LexicalTokenParser do (#1023).
#[test]
fn empty_lexical_tokens_start_the_collection_on_every_entry() {
    check_table(
        include_str!("../../../conformance/collector-source/lexical-token.tsv"),
        189,
        true,
    );
}

/// The capture-less spans of the shapes of lexical-token.tsv (#1023).
#[test]
fn empty_lexical_tokens_start_the_capture_less_spans() {
    check_boundary_table(
        include_str!("../../../conformance/collector-source/lexical-token-boundary.tsv"),
        189,
        true,
    );
}

/// The capture-less spans of the same shapes: the value boundaries and InputCaptureBoundary on the
/// full-input entry, and the ParseMatch, the boundaries and @f on ParseContext::parse, as Java's
/// CaptureBoundaryParser origins and the origin of the whole expression (#1023).
#[test]
fn empty_tokens_start_the_capture_less_spans() {
    check_boundary_table(
        include_str!("../../../conformance/collector-source/empty-token-boundary.tsv"),
        618,
        false,
    );
}

/// Checks every row of a capture-less span table with memo 2 x diagnostics 3, under a root scope
/// of t when `scope` (see [`run`]).
fn check_boundary_table(table: &str, expected_rows: usize, scope: bool) {
    let rows: Vec<Vec<&str>> = table
        .lines()
        .filter(|row| !row.starts_with('#'))
        .map(|row| row.split('\t').collect())
        .collect();
    assert_eq!(rows.len(), expected_rows);
    let bindings = always();
    let root = |expression: Expr| {
        if scope {
            scoped(expression)
        } else {
            expression
        }
    };
    fn new_context<'a>(
        input: &'a str,
        options: ParseOptions,
        bindings: Option<&'a Bindings>,
    ) -> ParseContext<'a> {
        match bindings {
            Some(bindings) => ParseContext::with_semantic_predicates(input, bindings, options),
            None => ParseContext::with_options(input, options),
        }
    }
    // capture_tree::Kind::Value's boundary node.
    let input_boundary_rule = usize::MAX - 5;
    type Boundary = fn(Expr) -> Expr;
    let boundaries: [(&str, Boundary, usize); 3] = [
        ("value", Expr::text_value, unlaxer_runtime::TEXT_VALUE_RULE),
        (
            "value",
            Expr::value_boundary,
            unlaxer_runtime::VALUE_BOUNDARY_RULE,
        ),
        (
            "input",
            |expression| {
                Expr::InputCaptureBoundary(
                    unlaxer_runtime::capture_tree::Kind::Value,
                    Box::new(expression),
                )
            },
            input_boundary_rule,
        ),
    ];
    let span = |span: Span| format!("{}-{}", span.start, span.end);
    for row in rows {
        let prefix: &'static str = match row[0] {
            "0" => "",
            "1" => "p",
            "3" => "😀\r\n",
            other => panic!("{other}"),
        };
        let input = format!("{prefix}c");
        let expected = |column: &str| if column == "value" { row[2] } else { row[3] };
        for options in options() {
            let label = format!("{row:?} {options:?}");
            for (column, boundary, rule) in boundaries {
                let (shaped, bodies) = shape(row[1]);
                let expression = root(after(
                    prefix,
                    vec![boundary(shaped), Expr::literal("c").optional()],
                ));
                let grammar = grammar(expression, bodies);
                let full = if scope {
                    unlaxer_runtime::parse_detailed_shared_with_semantic_predicates(
                        &grammar, 0, false, &input, options, &bindings,
                    )
                } else {
                    unlaxer_runtime::parse_detailed_shared_with_options(
                        &grammar, 0, false, &input, options,
                    )
                }
                .map_or("fail".into(), |tree| {
                    let root = &tree.nodes[tree.root];
                    let node = root
                        .children
                        .iter()
                        .find(|&&child| tree.nodes[child].rule == rule)
                        .expect(&label);
                    span(tree.nodes[*node].span)
                });
                assert_eq!(full, expected(column), "{label} {rule} full");
                RULES.with(|rules| *rules.borrow_mut() = Some(grammar));
                let mut context = new_context(&input, options, scope.then_some(&bindings));
                let parsed =
                    context
                        .parse(&Expr::Custom(enter_rules))
                        .map_or("fail".into(), |matched| {
                            let found = matched
                                .nodes
                                .iter()
                                .find_map(|&id| boundary_span(&context, id, rule));
                            span(found.expect(&label))
                        });
                assert_eq!(parsed, expected(column), "{label} {rule} context");
            }
            let (shaped, bodies) = shape(row[1]);
            let field = root(after(
                prefix,
                vec![shaped.capture("f"), Expr::literal("c").optional()],
            ));
            RULES.with(|rules| *rules.borrow_mut() = Some(grammar(field, bodies)));
            let mut context = new_context(&input, options, scope.then_some(&bindings));
            let parsed = context.parse(&Expr::Custom(enter_rules)).map_or_else(
                |_| "fail".into(),
                |_| span(*context.capture_spans("f").last().expect(&label)),
            );
            assert_eq!(parsed, row[3], "{label} @f context");
            let (shaped, bodies) = shape(row[1]);
            let whole = root(after(prefix, vec![shaped, Expr::literal("c").optional()]));
            RULES.with(|rules| *rules.borrow_mut() = Some(grammar(whole, bodies)));
            let mut context = new_context(&input, options, scope.then_some(&bindings));
            let parsed = context
                .parse(&Expr::Custom(enter_rules))
                .map_or("fail".into(), |matched| span(matched.span));
            assert_eq!(parsed, row[4], "{label} whole context");
        }
    }
}

/// Checks every row of a collector-source table at the four entries with memo 2 x diagnostics 3,
/// under a root scope of t when `scope` (see [`run`]).
fn check_table(table: &str, expected_rows: usize, scope: bool) {
    let rows: Vec<Vec<&str>> = table
        .lines()
        .filter(|row| !row.starts_with('#'))
        .map(|row| row.split('\t').collect())
        .collect();
    assert_eq!(rows.len(), expected_rows);
    for row in rows {
        let prefix: &'static str = match row[0] {
            "0" => "",
            "1" => "p",
            "3" => "😀\r\n",
            other => panic!("{other}"),
        };
        let input = format!("{prefix}c");
        for entry in ENTRIES {
            for options in options() {
                let label = format!("{row:?} {entry:?} {options:?}");
                set_observed("unreached\t-".into());
                let (shaped, bodies) = shape(row[1]);
                let capture = after(
                    prefix,
                    vec![
                        shaped.capture_owned("w"),
                        Expr::Custom(observe_capture),
                        Expr::Replay("w"),
                    ],
                );
                let (replay, _) = run(entry, (capture, bodies), &input, options, None, scope);
                assert_eq!(observed(), format!("{}\t{}", row[2], row[3]), "{label}");
                assert_eq!(replay.to_string(), row[4], "{label}");
                for wants_c in [true, false] {
                    let seen = Arc::new(Mutex::new(Vec::<String>::new()));
                    let recorded = Arc::clone(&seen);
                    let bindings = if scope { always() } else { Bindings::new() }
                        .with("valid", move |candidate| {
                            let text = if candidate.text.is_empty() {
                                "~"
                            } else {
                                candidate.text
                            };
                            let value = format!("{text}@{}", candidate.span.start);
                            let mut seen = recorded.lock().unwrap();
                            if !seen.contains(&value) {
                                seen.push(value);
                            }
                            (candidate.text == "c") == wants_c
                        })
                        .unwrap();
                    let (shaped, bodies) = shape(row[1]);
                    let gated = Expr::SemanticPredicateScope {
                        child: Box::new(after(
                            prefix,
                            vec![
                                Expr::SemanticPredicate {
                                    child: Box::new(shaped),
                                    name: "valid",
                                },
                                Expr::literal("c"),
                            ],
                        )),
                        requirements: vec!["valid"],
                    };
                    let (accepted, failure) = run(
                        entry,
                        (gated, bodies),
                        &input,
                        options,
                        Some(&bindings),
                        scope,
                    );
                    let seen = seen.lock().unwrap();
                    let candidates = if seen.is_empty() {
                        "-".into()
                    } else {
                        seen.join(",")
                    };
                    assert_eq!(candidates, row[5], "{label} wants_c={wants_c}");
                    assert_eq!(failure, row[6], "{label} wants_c={wants_c}");
                    let expected = if wants_c { row[7] } else { row[8] };
                    assert_eq!(accepted.to_string(), expected, "{label} wants_c={wants_c}");
                }
                set_observed("fail".into());
                let (shaped, bodies) = shape(row[1]);
                let field = after(
                    prefix,
                    vec![
                        shaped.capture("f"),
                        Expr::Custom(observe_field),
                        Expr::literal("c").optional(),
                    ],
                );
                run(entry, (field, bodies), &input, options, None, scope);
                assert_eq!(observed(), row[9], "{label}");
                // InputCaptureBoundary (the AST field check) accepts exactly when Java's
                // CaptureBoundaryParser.input does.
                let (shaped, bodies) = shape(row[1]);
                let input_boundary = after(
                    prefix,
                    vec![
                        Expr::InputCaptureBoundary(
                            unlaxer_runtime::capture_tree::Kind::Value,
                            Box::new(shaped),
                        ),
                        Expr::literal("c").optional(),
                    ],
                );
                let (accepted, _) = run(
                    entry,
                    (input_boundary, bodies),
                    &input,
                    options,
                    None,
                    scope,
                );
                assert_eq!(accepted, row[9] != "fail", "{label} input boundary");
            }
        }
    }
}

/// The span of the first node of `rule` reachable from `id` in a ParseContext.
fn boundary_span(context: &ParseContext<'_>, id: usize, rule: usize) -> Option<Span> {
    let node = context.node(id)?;
    if node.rule == rule {
        return Some(node.span);
    }
    node.children
        .iter()
        .find_map(|&child| boundary_span(context, child, rule))
}

/// The value boundaries are consumed collectors too (Java CaptureBoundaryParser is a Chain): over
/// a Classic child of another kind (MatchOnly, a token returned in the virtual kind) the boundary
/// node is the consumed collection, a child of the enclosing rule. Java measured
/// (CaptureBoundaryParser.value after "😀\r\n" and before an optional 'c'): 3-3 over MatchOnly
/// and the rules over it, 3-4 when 'c' is consumed after it, 4-4 after a virtual 'c'.
#[test]
fn value_boundary_over_a_classic_child_is_the_consumed_collection() {
    let cases = [
        ("MO", 3, 3),
        ("Choice[MO]", 3, 3),
        ("Chain[MO]", 3, 3),
        ("Opt[MO]", 3, 3),
        ("Opt[Choice[MO]]", 3, 3),
        ("Rep[MO]", 3, 3),
        ("Rec[MO]", 3, 3),
        ("Rule[MO]", 3, 3),
        ("Rule[Rule[MO]]", 3, 3),
        ("Rule[Choice[MO]]", 3, 3),
        ("Chain[MO,c]", 3, 4),
        ("Chain[c,Opt[MO]]", 3, 4),
        ("Chain[Rule[MO],c]", 3, 4),
        ("Ord[MO,c]", 3, 4),
        ("Opt[c]", 3, 4),
        ("c", 3, 4),
        ("vc", 4, 4),
        ("Chain[vc]", 4, 4),
        ("Opt[vc]", 4, 4),
        ("Rep[vc]", 4, 4),
        ("Ord[vc]", 4, 4),
        ("Rule[vc]", 4, 4),
        ("Rule[Rule[vc]]", 4, 4),
        ("Chain[Chain[vc]]", 4, 4),
    ];
    type Boundary = fn(Expr) -> Expr;
    let boundaries: [(Boundary, usize); 2] = [
        (Expr::text_value, unlaxer_runtime::TEXT_VALUE_RULE),
        (Expr::value_boundary, unlaxer_runtime::VALUE_BOUNDARY_RULE),
    ];
    for (name, start, end) in cases {
        for (boundary, rule) in boundaries {
            for options in options() {
                let label = format!("{name} {rule} {options:?}");
                let (shaped, bodies) = shape(name);
                let expression = after(
                    "😀\r\n",
                    vec![boundary(shaped), Expr::literal("c").optional()],
                );
                let grammar = grammar(expression, bodies);
                let tree = unlaxer_runtime::parse_detailed_shared_with_options(
                    &grammar,
                    0,
                    false,
                    "😀\r\nc",
                    options,
                )
                .unwrap();
                let node = tree
                    .nodes
                    .iter()
                    .position(|node| node.rule == rule)
                    .expect(&label);
                assert_eq!(tree.nodes[node].span, Span { start, end }, "{label}");
                assert!(tree.nodes[tree.root].children.contains(&node), "{label}");
                if let Some(info) = tree.token_node_id(node).and_then(|id| tree.token_info(id)) {
                    assert_eq!(info.kind, TokenKind::Consumed, "{label}");
                }
                // ParseContext::parse agrees where it stays native (a MatchOnly makes the graph
                // Classic, whose boundary is a Classic meta node instead).
                if name.contains("MO") {
                    continue;
                }
                RULES.with(|rules| *rules.borrow_mut() = Some(grammar));
                let mut context = ParseContext::with_options("😀\r\nc", options);
                let matched = context.parse(&Expr::Custom(enter_rules)).expect(&label);
                let span = matched
                    .nodes
                    .iter()
                    .find_map(|&id| boundary_span(&context, id, rule));
                assert_eq!(span, Some(Span { start, end }), "{label} context");
            }
        }
    }
}
