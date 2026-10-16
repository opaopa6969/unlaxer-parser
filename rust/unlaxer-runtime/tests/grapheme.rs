use unlaxer_runtime::grapheme::{GraphemeIndex, GraphemeLength, UNICODE_VERSION};
use unlaxer_runtime::source::Snapshot;

#[test]
fn official_unicode_17_grapheme_break_suite() {
    let mut cases = 0;
    for line in
        include_str!("../../../spec-corpus/grapheme/unicode-17.0.0/GraphemeBreakTest.txt").lines()
    {
        let data = line.split('#').next().unwrap().trim();
        if data.is_empty() {
            continue;
        }
        let (mut text, mut expected, mut point) = (String::new(), Vec::new(), 0);
        for token in data.split_whitespace() {
            match token {
                "÷" => expected.push(point),
                "×" => (),
                _ => {
                    text.push(char::from_u32(u32::from_str_radix(token, 16).unwrap()).unwrap());
                    point += 1;
                }
            }
        }
        let table = Snapshot::new("official", 1, text).unwrap().graphemes();
        let actual: Vec<_> = (0..=table.length().0)
            .map(|index| table.code_point(GraphemeIndex(index)).unwrap())
            .collect();
        assert_eq!(actual, expected, "{line}");
        cases += 1;
    }
    assert_eq!(cases, 766);
    assert_eq!(UNICODE_VERSION, "17.0.0");
}

fn decode(hex: &str) -> String {
    if hex == "~" {
        return String::new();
    }
    hex.split_whitespace()
        .map(|scalar| char::from_u32(u32::from_str_radix(scalar, 16).unwrap()).unwrap())
        .collect()
}

#[test]
fn independent_coordinates_strict_interiors_rounding_and_snapshot_identity() {
    for row in include_str!("../../../spec-corpus/grapheme/boundaries.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
    {
        let fields: Vec<_> = row.split('\t').collect();
        let source = Snapshot::new(fields[0], 1, decode(fields[1])).unwrap();
        let table = source.graphemes();
        let expected: Vec<Vec<usize>> = fields[2]
            .split(',')
            .map(|boundary| {
                boundary
                    .split(':')
                    .map(|number| number.parse().unwrap())
                    .collect()
            })
            .collect();
        assert_eq!(table.length(), GraphemeLength(expected.len() - 1));
        for (index, boundary) in expected.iter().enumerate() {
            let index = GraphemeIndex(index);
            assert_eq!(table.code_point(index), Ok(boundary[0]));
            assert_eq!(table.utf16(index), Ok(boundary[1]));
            assert_eq!(table.utf8(index), Ok(boundary[2]));
        }
        for unit in 0..3 {
            let total = expected.last().unwrap()[unit];
            for offset in 0..=total + 1 {
                let exact = expected
                    .iter()
                    .position(|boundary| boundary[unit] == offset)
                    .map(GraphemeIndex);
                let before = expected
                    .iter()
                    .rposition(|boundary| boundary[unit] <= offset)
                    .map(GraphemeIndex)
                    .filter(|_| offset <= total);
                let after = expected
                    .iter()
                    .position(|boundary| boundary[unit] >= offset)
                    .map(GraphemeIndex);
                let (strict, floor, ceiling) = match unit {
                    0 => (
                        table.from_code_point(offset),
                        table.floor_code_point(offset),
                        table.ceiling_code_point(offset),
                    ),
                    1 => (
                        table.from_utf16(offset),
                        table.floor_utf16(offset),
                        table.ceiling_utf16(offset),
                    ),
                    _ => (
                        table.from_utf8(offset),
                        table.floor_utf8(offset),
                        table.ceiling_utf8(offset),
                    ),
                };
                assert_eq!(
                    strict.ok(),
                    exact,
                    "{} unit {unit} offset {offset}",
                    fields[0]
                );
                assert_eq!(floor.ok(), before);
                assert_eq!(ceiling.ok(), after);
            }
        }
        assert!(table.code_point(GraphemeIndex(expected.len())).is_err());
        assert!(table.utf16(GraphemeIndex(usize::MAX)).is_err());
        assert!(table.utf8(GraphemeIndex(usize::MAX)).is_err());
        assert!(table.floor_code_point(usize::MAX).is_err());
        table.require_snapshot(&source).unwrap();
        for changed in [
            Snapshot::new("other", 1, &source.text).unwrap(),
            Snapshot::new(&source.uri, 2, &source.text).unwrap(),
            Snapshot::new(&source.uri, 1, format!("{}x", source.text)).unwrap(),
        ] {
            assert!(table.require_snapshot(&changed).is_err());
        }
        assert_eq!(table.snapshot(), &source);
    }
}

#[test]
fn shared_parser_cursor_span_rollback_and_lookahead_oracle() {
    use unlaxer_runtime::{Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Span};
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../spec-corpus/grapheme/parser.tsv")
                .lines()
                .filter(|row| !row.starts_with('#'))
            {
                let fields: Vec<_> = row.split('\t').collect();
                let input = decode(fields[1]);
                let g = Expr::Grapheme.capture("item");
                let parser = match fields[2] {
                    "G" => g,
                    "CG" => Expr::Any.then(g),
                    "GC" => g.then(Expr::Any),
                    "GG" => g.then(Expr::Grapheme),
                    "RETRY" => Expr::choice([Expr::Any.then(g.clone()), g]),
                    "FAIL" => g.then(Expr::literal("!")),
                    "AHEAD" => g.ahead(),
                    "AHEAD_G" => g.clone().ahead().then(g),
                    _ => panic!("unknown operation"),
                };
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                let result = context.parse(&parser);
                assert_eq!(result.is_ok(), fields[3] == "true", "{}", fields[0]);
                assert_eq!(
                    context.position(),
                    fields[4].parse().unwrap(),
                    "{}",
                    fields[0]
                );
                assert_eq!(
                    context.matched_position(),
                    fields[6].parse().unwrap(),
                    "{}",
                    fields[0]
                );
                if let Ok(matched) = result {
                    let end = fields[8].parse().unwrap();
                    assert_eq!(matched.span, Span { start: 0, end }, "{}", fields[0]);
                    assert_eq!(
                        context.text(matched.span),
                        Some(input.chars().take(end).collect::<String>().as_str())
                    );
                    if fields[2] == "AHEAD" {
                        assert!(context.captured("item").is_none());
                    } else {
                        assert!(context.captured("item").is_some());
                    }
                } else {
                    assert!(context.captured("item").is_none());
                    if !input.is_empty() {
                        assert!(context
                            .parse(&unlaxer_runtime::grapheme::GraphemeParser)
                            .is_ok());
                    }
                }
            }
        }
    }
}

#[test]
fn long_clusters_and_repeated_parsing_do_not_rescan_prefixes() {
    let input = format!("a{}", "\u{301}".repeat(20_000));
    let table = Snapshot::new("long", 1, &input).unwrap().graphemes();
    assert_eq!(table.length(), GraphemeLength(1));
    assert_eq!(table.code_point(GraphemeIndex(1)), Ok(20_001));
    let repeated = "e\u{301}".repeat(10_000);
    let mut context = unlaxer_runtime::ParseContext::new(&repeated);
    context
        .parse(&unlaxer_runtime::Expr::Grapheme.zero_or_more())
        .unwrap();
    assert_eq!(context.position(), 20_000);
}

#[test]
fn lsp_columns_are_utf16_and_lengths_need_their_source() {
    use unlaxer_runtime::source::Position;
    let source = Snapshot::new("lines", 1, "👨‍👩‍👧‍👦\r\nx").unwrap();
    let table = source.graphemes();
    assert_eq!(
        source.lsp(table.code_point(GraphemeIndex(1)).unwrap()),
        Ok(Position {
            line: 0,
            character: 11
        })
    );
    assert_eq!(
        source.lsp(table.code_point(GraphemeIndex(2)).unwrap()),
        Ok(Position {
            line: 1,
            character: 0
        })
    );
    assert_eq!(
        table.from_code_point(
            source
                .from_lsp(Position {
                    line: 0,
                    character: 11
                })
                .unwrap()
        ),
        Ok(GraphemeIndex(1))
    );
    assert!(source.lsp(8).is_err());
    assert!(table.from_code_point(8).is_err());
    assert_eq!(
        Snapshot::new("a", 1, "ab").unwrap().graphemes().length(),
        GraphemeLength(2)
    );
    assert_eq!(
        Snapshot::new("b", 1, "e\u{301}")
            .unwrap()
            .graphemes()
            .length(),
        GraphemeLength(1)
    );
}
