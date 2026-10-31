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

fn close_tag(name: Expr) -> Vec<Expr> {
    vec![
        Expr::literal("<"),
        space(),
        Expr::literal("/"),
        space(),
        name,
        space(),
        Expr::literal(">"),
    ]
}

fn empty_element_tag() -> Expr {
    Expr::sequence([
        Expr::literal("<"),
        space(),
        tag_name(),
        space(),
        Expr::literal("/"),
        space(),
        Expr::literal(">"),
    ])
}

fn element(rule: usize, open_name: Expr, close_name: Expr, scoped: bool) -> Expr {
    let mut paired = open_tag(open_name);
    paired.push(Expr::Rule(rule).optional_java());
    paired.extend(close_tag(close_name));
    let paired = Expr::sequence(paired);
    let paired = if scoped {
        paired.capture_scope()
    } else {
        paired
    };
    Expr::choice([paired, empty_element_tag()])
}

/// Java MatchedTokenParser over earlier TagIdentifier tokens: the first earlier name
/// that prefixes the input wins, and later candidates are not retried. The match is
/// consumed through a public parse so that it yields a token like Java's WordParser.
fn earlier_tag_name(context: &mut ParseContext<'_>) -> ParseResult {
    let length = context.capture_sources("tag").iter().find_map(|source| {
        let text = match source {
            TokenSource::Input(span) => context.text(*span)?,
            TokenSource::Projected { text, .. } => text,
            TokenSource::Generated { text, .. } => text.as_str(),
        };
        (!text.is_empty() && context.remaining().starts_with(text)).then(|| text.chars().count())
    });
    match length {
        Some(length) => context.parse(&Expr::Any.repeat(length, Some(length))),
        None => Err(context.error("earlier tag name")),
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

fn grammar() -> SharedGrammar {
    let earlier = Expr::CustomWith {
        parser: earlier_tag_name,
        reads_diagnostics: false,
        replayable: true,
    };
    share_grammar(vec![
        // Usage002_02: the close name is not compared with the open name.
        Rule {
            name: "NoMixedContentsXmlElements",
            expression: element(0, tag_name(), tag_name(), false),
        },
        // Usage002_03 / Usage002_04: MatchedTokenParser over earlier names.
        Rule {
            name: "NoMixedContentsXmlElementsPaired",
            expression: element(1, tag_name().capture_owned("tag"), earlier, false),
        },
        // Owned replay of the innermost open name.
        Rule {
            name: "ScopedXmlElement",
            expression: element(
                2,
                tag_name().capture_owned("tag"),
                Expr::Replay("tag"),
                true,
            ),
        },
    ])
}

/// Checks each (root rule, fixture column) pair and returns the row count.
fn check(fixture: &str, roots: &[(usize, usize)]) -> usize {
    let grammar = grammar();
    let mut cases = 0;
    for line in fixture.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let row = line.split('\t').collect::<Vec<_>>();
        assert_eq!(row.len(), 5, "{line}");
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
    assert_eq!(check(fixture, &[(0, 1), (1, 2), (2, 3)]), 57);
}

/// The rust column is the source-order adapter, not Java's candidate cache (#852).
#[test]
fn paired_adapter_records_its_history_difference() {
    let fixture = include_str!("../../../conformance/samples/xml-tags-history.tsv");
    assert_eq!(check(fixture, &[(1, 2), (2, 3)]), 4);
}
