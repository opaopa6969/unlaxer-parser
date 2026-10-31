use unlaxer_runtime::token::TokenSource;
use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult, Rule,
    SharedGrammar,
};

/// Java SpaceDelimitor: zero or more of SpaceParser's six ASCII characters.
fn space() -> Expr {
    Expr::character("space", |c| {
        matches!(c, ' ' | '\t' | '\n' | '\u{b}' | '\u{c}' | '\r')
    })
    .repeat_java(0, None)
}

/// Java TagIdentifier: one or more scalars until ASCII punctuation or a space.
fn tag_name() -> Expr {
    Expr::Except(" !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~").repeat_java(1, None)
}

fn open_tag(name: Expr) -> Vec<Expr> {
    vec![
        Expr::literal("<"),
        space(),
        name,
        space(),
        Expr::literal(">"),
    ]
}

fn close_tag(name: Expr, end: Expr) -> Vec<Expr> {
    vec![
        Expr::literal("<"),
        space(),
        Expr::literal("/"),
        space(),
        name,
        space(),
        end,
    ]
}

fn empty_element_tag(name: Expr, end: Expr) -> Expr {
    Expr::sequence([
        Expr::literal("<"),
        space(),
        name,
        space(),
        Expr::literal("/"),
        space(),
        end,
    ])
}

/// For `paired`, empty-element names join the `tag` history and every final `>` is a
/// `closed` capture, from which [`java_candidates`] rebuilds the open elements.
fn element(rule: usize, open_name: Expr, close_name: Expr, scoped: bool, paired: bool) -> Expr {
    let end = || {
        let end = Expr::literal(">");
        if paired {
            end.capture_owned("closed")
        } else {
            end
        }
    };
    let mut element = open_tag(open_name);
    element.push(Expr::Rule(rule).optional_java());
    element.extend(close_tag(close_name, end()));
    let element = Expr::sequence(element);
    let element = if scoped {
        element.capture_scope()
    } else {
        element
    };
    let empty_name = if paired {
        tag_name().capture_owned("tag")
    } else {
        tag_name()
    };
    Expr::choice([element, empty_element_tag(empty_name, end())])
}

fn source_text<'a>(context: &ParseContext<'a>, source: &'a TokenSource) -> &'a str {
    match source {
        TokenSource::Input(span) => context.text(*span).unwrap(),
        TokenSource::Projected { text, .. } => text,
        TokenSource::Generated { text, .. } => text,
    }
}

fn source_start(source: &TokenSource) -> usize {
    match source {
        TokenSource::Input(span) | TokenSource::Projected { span, .. } => span.start,
        TokenSource::Generated { anchor, .. } => *anchor,
    }
}

/// Java's candidate order: TagIdentifier tokens on the current transaction stack,
/// innermost frame first. Replaying the `tag` (name) and `closed` (final `>`) histories
/// in source order gives the open elements; each frame holds an open element's name and
/// its finished children. Names outside every open element (earlier roots of `Twice`)
/// come last, in source order. The histories roll back with the parse, as the stack does.
fn java_candidates(context: &ParseContext<'_>) -> Vec<String> {
    let mut events = context
        .capture_sources("tag")
        .iter()
        .map(|source| (source_start(source), Some(source_text(context, source))))
        .chain(
            context
                .capture_sources("closed")
                .iter()
                .map(|source| (source_start(source), None)),
        )
        .collect::<Vec<_>>();
    events.sort_by_key(|&(start, _)| start);
    let mut names = Vec::new();
    let mut open = Vec::new();
    for (_, name) in events {
        match name {
            Some(name) => {
                open.push(names.len());
                names.push(name.to_owned());
            }
            None => {
                open.pop();
            }
        }
    }
    let mut candidates = Vec::with_capacity(names.len());
    let mut end = names.len();
    for start in open.into_iter().rev().chain([0]) {
        candidates.extend_from_slice(&names[start..end]);
        end = start;
    }
    candidates
}

/// Java MatchedTokenParser over earlier TagIdentifier tokens, read again at every close
/// (#1017): the first candidate that prefixes the input wins, and later candidates are
/// not retried. The match is consumed through a public parse so that it yields a token
/// like Java's WordParser.
fn earlier_tag_name(context: &mut ParseContext<'_>) -> ParseResult {
    let length = java_candidates(context)
        .iter()
        .find(|text| !text.is_empty() && context.remaining().starts_with(text.as_str()))
        .map(|text| text.chars().count());
    match length {
        Some(length) => context.parse(&Expr::Any.repeat(length, Some(length))),
        None => Err(context.error("earlier tag name")),
    }
}

/// The adapter reads only the input and the captures, so a fresh-context retry
/// reproduces it.
fn close_name() -> Expr {
    Expr::CustomWith {
        parser: earlier_tag_name,
        reads_diagnostics: false,
        replayable: true,
    }
}

fn unescape(text: &str) -> String {
    let mut out = String::new();
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        out.push(match chars.next() {
            Some('r') => '\r',
            Some('n') => '\n',
            Some('t') => '\t',
            Some('\\') => '\\',
            _ => panic!("{text}"),
        });
    }
    out
}

const PAIRED: usize = 1;

fn grammar() -> SharedGrammar {
    share_grammar(vec![
        // Usage002_02: the close name is not compared with the open name.
        Rule {
            name: "NoMixedContentsXmlElements",
            expression: element(0, tag_name(), tag_name(), false, false),
        },
        // Usage002_03 / Usage002_04: MatchedTokenParser over earlier names. Without a kept
        // list the root and the class-factory instance behave alike, so one rule serves both.
        Rule {
            name: "NoMixedContentsXmlElementsPaired",
            expression: element(
                PAIRED,
                tag_name().capture_owned("tag"),
                close_name(),
                false,
                true,
            ),
        },
        // Owned replay of the innermost open name.
        Rule {
            name: "ScopedXmlElement",
            expression: element(
                2,
                tag_name().capture_owned("tag"),
                Expr::Replay("tag"),
                true,
                false,
            ),
        },
        // xml-tags-current-candidates.tsv: Choice(Chain(shared, "!"), Chain("<a><ab/></a>", shared)).
        Rule {
            name: "Retry",
            expression: Expr::choice([
                Expr::sequence([Expr::Rule(PAIRED), Expr::literal("!")]),
                Expr::sequence([Expr::literal("<a><ab/></a>"), Expr::Rule(PAIRED)]),
            ]),
        },
        // Its second alternative alone.
        Rule {
            name: "RetrySecond",
            expression: Expr::sequence([Expr::literal("<a><ab/></a>"), Expr::Rule(PAIRED)]),
        },
        // Chain(shared, shared): the same instance twice.
        Rule {
            name: "Twice",
            expression: Expr::sequence([Expr::Rule(PAIRED), Expr::Rule(PAIRED)]),
        },
    ])
}

/// Checks each (root rule, fixture column) pair and returns the row count.
fn check(fixture: &str, width: usize, roots: &[(usize, usize)]) -> usize {
    let grammar = grammar();
    let mut cases = 0;
    for line in fixture.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let row = line.split('\t').collect::<Vec<_>>();
        assert_eq!(row.len(), width, "{line}");
        cases += 1;
        let input = unescape(row[0]);
        for &(root, column) in roots {
            let expected = row[column];
            let success = expected != "-";
            let consumed = if success {
                expected.parse::<usize>().unwrap()
            } else {
                0
            };
            for memoization in [Memoization::Off, Memoization::SafeFailures] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    let label = format!("{root} {memoization:?} {diagnostics:?}: {line}");
                    let mut context = ParseContext::with_options(
                        &input,
                        ParseOptions {
                            memoization,
                            diagnostics,
                            ..ParseOptions::default()
                        },
                    );
                    let parsed = context.parse_shared_grammar(&grammar, root, false);
                    assert_eq!(parsed.is_ok(), success, "{label}");
                    assert_eq!(context.position(), consumed, "{label}");
                    assert_eq!(context.matched_position(), consumed, "{label}");
                    if let Ok(parsed) = parsed {
                        let prefix = input.chars().take(consumed).collect::<String>();
                        assert_eq!(context.text(parsed.span).unwrap(), prefix, "{label}");
                    }
                }
            }
        }
    }
    cases
}

#[test]
fn xml_tag_samples_match_shared_prefix_contract() {
    let fixture = include_str!("../../../conformance/samples/xml-tags.tsv");
    assert_eq!(check(fixture, 5, &[(0, 1), (PAIRED, 2), (2, 3)]), 70);
}

#[test]
fn paired_close_uses_current_candidates() {
    let fixture = include_str!("../../../conformance/samples/xml-tags-current-candidates.tsv");
    assert_eq!(
        check(fixture, 6, &[(PAIRED, 1), (3, 2), (4, 3), (5, 4)]),
        15
    );
}
