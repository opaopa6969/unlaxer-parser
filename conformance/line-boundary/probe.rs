//! 実行可能な移植例。公開 runtime API だけを使用する。
use std::fmt::Write;
use unlaxer_runtime::{
    lexical::{LexicalExpression, Op},
    token::{TokenKind, TokenSource},
    Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult, Span,
};

fn lexical(op: Op) -> LexicalExpression {
    LexicalExpression {
        op,
        text: "",
        min: 0,
        max: 0,
        children: vec![],
    }
}

// Classic の virtual consumed も参照位置は matched。消費の分類とは異なる。
fn boundary(context: &mut ParseContext<'_>, op: Op) -> ParseResult {
    let mode = context.token_mode();
    let position = if mode.kind == TokenKind::Consumed {
        context.position()
    } else {
        context.matched_position()
    };
    let byte = context
        .source()
        .char_indices()
        .map(|(p, _)| p)
        .chain(std::iter::once(context.source().len()))
        .nth(position)
        .unwrap();
    let at_boundary = lexical(op).match_at(context.source(), byte).is_some();
    // StartOfLine の invert は非行頭を成功にしない。StartOfSource は XOR。
    let accepted = if op == Op::BOL {
        at_boundary && !mode.invert
    } else {
        at_boundary != mode.invert
    };
    if accepted {
        Ok(ParseMatch::empty(Span {
            start: position,
            end: position,
        }))
    } else {
        // Java のこの2型は失敗診断を追加しない。既存 frontier をそのまま返す。
        Err(context.failure())
    }
}
fn bol(context: &mut ParseContext<'_>) -> ParseResult {
    boundary(context, Op::BOL)
}
fn bof(context: &mut ParseContext<'_>) -> ParseResult {
    boundary(context, Op::BOF)
}

pub fn line_end() -> Expr {
    Expr::choice([
        Expr::literal("\r\n"),
        Expr::literal("\r"),
        Expr::literal("\n"),
        Expr::Eof,
    ])
}
fn parser(name: &str) -> Expr {
    match name {
        "bol" => Expr::Custom(bol),
        "bof" => Expr::Custom(bof),
        // Java の DETAILED_ON_FAILURE だけに出る FIRST 除外の欠陥（#861）は移植しない。
        "terminator" | "eol" => line_end(),
        _ => panic!("unknown parser {name}"),
    }
}
fn kind(name: &str) -> TokenKind {
    match name {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("unknown kind {name}"),
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
fn decode(value: &str) -> String {
    if value == "~" {
        return String::new();
    }
    String::from_utf8(
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
            .collect(),
    )
    .unwrap()
}
fn encode(value: &str) -> String {
    if value.is_empty() {
        "~".to_owned()
    } else {
        value
            .as_bytes()
            .iter()
            .fold(String::new(), |mut output, byte| {
                write!(output, "{byte:02x}").unwrap();
                output
            })
    }
}
fn tree(context: &mut ParseContext<'_>, index: usize, reduced: bool) -> String {
    let node = context.node(index).unwrap().clone();
    let id = context.token_node_id(index).unwrap();
    let info = context.token_info(id).unwrap();
    let label = match info.parser_label().unwrap() {
        "Choice" => "Line",
        other => other,
    };
    let (start, end) = match &info.source {
        TokenSource::Input(span) | TokenSource::Projected { span, .. } => (span.start, span.end),
        TokenSource::Generated { anchor, text } => (*anchor, *anchor + text.chars().count()),
    };
    let text = context.token_text(id).unwrap();
    let head = format!(
        "{label}/{}@{start}:{end}={}",
        kind_name(info.kind),
        encode(text)
    );
    if reduced && text.is_empty() {
        return String::new();
    }
    let children = node
        .children
        .iter()
        .map(|child| tree(context, *child, reduced))
        .filter(|value| !value.is_empty())
        .collect::<Vec<_>>()
        .join(",");
    format!("{head}[{children}]")
}
fn forest(context: &mut ParseContext<'_>, nodes: &[usize], reduced: bool) -> String {
    let result = nodes
        .iter()
        .map(|node| tree(context, *node, reduced))
        .filter(|value| !value.is_empty())
        .collect::<Vec<_>>()
        .join(";");
    if result.is_empty() {
        "~".to_owned()
    } else {
        result
    }
}

pub fn verify() -> usize {
    let rows: Vec<_> = include_str!("cases.tsv")
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
        .collect();
    assert_eq!(rows.len(), 1088);
    let mut count = 0;
    let mut failures = Vec::new();
    for row in rows {
        let c: Vec<_> = row.split('\t').collect();
        assert_eq!(c.len(), 16);
        assert_eq!(c[8], "direct");
        let input = decode(c[1]);
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
                Diagnostics::Auto,
            ] {
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                context.set_reset_matched_with_consumed(false);
                let consumed: usize = c[5].parse().unwrap();
                let matched: usize = c[6].parse().unwrap();
                for (start, end, mode) in [
                    (0, consumed, TokenKind::Consumed),
                    (consumed, matched, TokenKind::MatchOnly),
                ] {
                    if end > start {
                        let prefix = input
                            .chars()
                            .skip(start)
                            .take(end - start)
                            .collect::<String>();
                        let word = Box::leak(prefix.into_boxed_str());
                        context
                            .parse_with_token_mode(&Expr::literal(word), mode, false)
                            .unwrap();
                    }
                }
                context.set_reset_matched_with_consumed(c[7] == "true");
                context.set_state("sentinel", 7_u32);
                context.scopes_mut().declare("before", 0);
                let marker = context
                    .add_token_node(
                        unlaxer_runtime::TOKEN_LEAF_RULE,
                        TokenKind::VirtualConsumed,
                        TokenSource::Generated {
                            anchor: 0,
                            text: "seed".into(),
                        },
                    )
                    .unwrap();
                assert!(context.put_token_extra(marker, "value", "before"));
                let depth = context.scopes().current_scope_depth();
                let expression = parser(c[2]);
                for _attempt in 0..2 {
                    let aborted: Result<(), _> = context.transaction(|context| context.transaction(|context| {
                context.set_state("sentinel", 9_u32);
                context.scopes_mut().enter(); context.scopes_mut().declare("trial", 0);
                assert!(context.put_token_extra(marker, "value", "trial"));
                let result = context.parse_with_token_mode(&expression, kind(c[3]), c[4] == "true");
                let nodes = result.as_ref().map(|r| r.nodes.clone()).unwrap_or_default();
                assert!(result.as_ref().map_or(true, |r| r.captures.is_empty()));
                assert!(context.capture_spans("site").is_empty());
                assert_eq!(context.state::<u32>("sentinel"), Some(&9));
                assert_eq!(context.scopes().all_declarations().len(), 2);
                assert_eq!(context.scopes().all_declarations()[0].name, "before");
                let actual = vec![result.is_ok().to_string(), context.position().to_string(), context.matched_position().to_string(),
                    context.failure().offset.to_string(), (!context.failure().expected.is_empty()).to_string(),
                    forest(context, &nodes, false), forest(context, &nodes, true)];
                let mut expected = c[9..16].iter().map(|s| s.to_string()).collect::<Vec<_>>();
                // 末尾列は Java の既知欠陥 #861 の現状値。Rust は主の期待値に照合する。
                if diagnostics == Diagnostics::DetailedOnFailure { expected[3] = "0".into(); expected[4] = "false".into(); }
                if actual != expected { failures.push(format!("{} {memo:?} {diagnostics:?}\nexpected {expected:?}\nactual   {actual:?}", c[0])); }
                count += 1;
                assert_eq!(context.token_info(marker).unwrap().extra("value"), Some("trial"));
                context.parse_with_token_mode(&Expr::Empty.capture_owned("site"), TokenKind::Consumed, false).unwrap();
                assert!(context.captured_text("site").is_some());
                Err(context.failure())
                }));
                    assert!(aborted.is_err());
                    assert_eq!(context.reset_matched_with_consumed(), c[7] == "true");
                    assert_eq!(context.position(), consumed);
                    assert_eq!(context.matched_position(), matched);
                    assert_eq!(context.state::<u32>("sentinel"), Some(&7));
                    assert_eq!(context.scopes().current_scope_depth(), depth);
                    assert_eq!(context.scopes().all_declarations().len(), 1);
                    assert!(context.captured_text("site").is_none());
                    assert_eq!(
                        context.token_info(marker).unwrap().extra("value"),
                        Some("before")
                    );
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
    count
}
pub fn verify_transactions() {
    for row in include_str!("transactions.tsv")
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
    {
        let c: Vec<_> = row.split('\t').collect();
        let input = decode(c[1]);
        let start: usize = c[2].parse().unwrap();
        let prefix: &'static str = Box::leak(
            input
                .chars()
                .take(start)
                .collect::<String>()
                .into_boxed_str(),
        );
        let subject = parser(c[0]);
        let losing = subject.clone().then(Expr::literal("\0"));
        let expression = match c[3] {
            "choice" => losing.clone().or(subject.clone()),
            "ahead" => subject.clone().ahead().then(subject.clone()),
            "nested" => Expr::sequence([subject.clone()]),
            "retry" => subject.clone(),
            _ => panic!("{}", c[3]),
        };
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
                Diagnostics::Auto,
            ] {
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                if start > 0 {
                    context
                        .parse_with_token_mode(&Expr::literal(prefix), TokenKind::Consumed, false)
                        .unwrap();
                }
                if c[3] == "retry" {
                    assert!(context
                        .parse_with_token_mode(&losing, TokenKind::Consumed, false)
                        .is_err());
                    assert_eq!(context.position(), start);
                    assert_eq!(context.matched_position(), start);
                }
                let result = context.parse_with_token_mode(&expression, TokenKind::Consumed, false);
                assert_eq!(result.is_ok().to_string(), c[4], "{row}");
                assert_eq!(context.position().to_string(), c[5], "{row}");
                assert_eq!(context.matched_position().to_string(), c[5], "{row}");
                assert_eq!(
                    context.failure().offset.to_string(),
                    if diagnostics == Diagnostics::DetailedOnFailure {
                        "0"
                    } else {
                        c[6]
                    },
                    "{row}"
                );
                assert_eq!(
                    !context.failure().expected.is_empty(),
                    diagnostics != Diagnostics::DetailedOnFailure && c[7] == "true",
                    "{row}"
                );
                let nodes = result.as_ref().map(|r| r.nodes.clone()).unwrap_or_default();
                let mut raw = forest(&mut context, &nodes, false);
                if c[3] == "choice" {
                    raw = raw.replacen("Line/", "Choice/", 1);
                }
                assert_eq!(raw, c[8], "{row}");
                let reduced_nodes = if c[3] == "retry" {
                    nodes
                } else {
                    nodes
                        .iter()
                        .flat_map(|n| context.node(*n).unwrap().children.clone())
                        .collect()
                };
                assert_eq!(forest(&mut context, &reduced_nodes, true), c[9], "{row}");
            }
        }
    }
}

pub fn verify_lexical_boundaries() {
    for row in include_str!("lexical.tsv")
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
    {
        let c: Vec<_> = row.split('\t').collect();
        let source = decode(c[0]);
        for (column, op) in [(1, Op::BOL), (2, Op::BOF), (3, Op::EOL)] {
            let expected: Vec<usize> = c[column].split(',').map(|p| p.parse().unwrap()).collect();
            for position in 0..=source.chars().count() {
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    for diagnostics in [
                        Diagnostics::Detailed,
                        Diagnostics::DetailedOnFailure,
                        Diagnostics::Auto,
                    ] {
                        let mut context = ParseContext::with_options(
                            &source,
                            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                        );
                        assert!(context.advance(position));
                        let result = context.parse_with_token_mode(
                            &Expr::Lexical("boundary", lexical(op)),
                            TokenKind::Consumed,
                            false,
                        );
                        assert_eq!(
                            result.is_ok(),
                            expected.contains(&position),
                            "{op:?}@{position}"
                        );
                        assert_eq!(context.position(), position);
                        assert_eq!(context.matched_position(), position);
                        if let Ok(result) = result {
                            assert_eq!(result.nodes.len(), 1);
                            assert_eq!(
                                context.node(result.nodes[0]).unwrap().span,
                                Span {
                                    start: position,
                                    end: position
                                }
                            );
                        }
                    }
                }
            }
        }
    }
}

// この候補は通常の受理/cursor を再現するが、body の各回に余分な Chain CST を作る。
// #846 を完了したとは扱わず、独立した差分 oracle を維持する。
fn composition(name: &str) -> Expr {
    let body = if name == "empty" {
        Expr::character("SpaceParser", |c| " \t\r\n\u{b}\u{c}".contains(c))
    } else {
        Expr::character("WildCardStringParser", |_| true)
    };
    Expr::sequence([
        Expr::Custom(bol),
        line_end().not_ahead().then(body).repeat_java(0, None),
        line_end(),
    ])
}
fn body_frames(context: &mut ParseContext<'_>, index: usize) -> usize {
    let children = context.node(index).unwrap().children.clone();
    let id = context.token_node_id(index).unwrap();
    let own = usize::from(context.token_info(id).unwrap().parser_label() == Some("Chain"));
    own + children
        .iter()
        .map(|child| body_frames(context, *child))
        .sum::<usize>()
}
pub fn verify_compositions() {
    for row in include_str!("compositions.tsv")
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
    {
        let c: Vec<_> = row.split('\t').collect();
        let source = decode(c[1]);
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
                Diagnostics::Auto,
            ] {
                let mut context = ParseContext::with_options(
                    &source,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                let result =
                    context.parse_with_token_mode(&composition(c[0]), TokenKind::Consumed, false);
                assert_eq!(result.is_ok().to_string(), c[2], "{row}");
                assert_eq!(context.position().to_string(), c[3], "{row}");
                assert_eq!(context.matched_position().to_string(), c[4], "{row}");
                let count: usize = result.as_ref().map_or(0, |r| {
                    r.nodes
                        .iter()
                        .map(|n| body_frames(&mut context, *n) - 1)
                        .sum()
                });
                assert_eq!(count.to_string(), c[6], "{row}");
            }
        }
    }
}
pub fn verify_repeat_choice() {
    for row in include_str!("repeat-choice.tsv")
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
    {
        let c: Vec<_> = row.split('\t').collect();
        let input = decode(c[1]);
        let body = if c[0] == "empty" {
            Expr::character("SpaceParser", |cp| " \t\r\n\u{b}\u{c}".contains(cp))
        } else {
            Expr::character("WildCardStringParser", |_| true)
        };
        // terminator を先頭の選択肢にしても、match-only cursor が進むため Repeat は停止しない。
        let repeated = line_end().match_only().or(body).repeat_java(0, None);
        let expression = Expr::sequence([Expr::Custom(bol), repeated, line_end()]);
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
                Diagnostics::Auto,
            ] {
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                let result =
                    context.parse_with_token_mode(&expression, TokenKind::MatchOnly, false);
                assert_eq!(result.is_ok().to_string(), c[5], "{row}");
                assert_eq!(context.position().to_string(), c[6], "{row}");
                assert_eq!(context.matched_position().to_string(), c[7], "{row}");
            }
        }
    }
}
// 既存の Repeat で書ける最も近い候補。終端の CR/LF を body の文字クラスから外し、
// terminator を反復の前に試さない。consumed / matchOnly の受理・cursor・原CSTは揃うが、
// virtual consumed での terminator commit と、その試行が残す失敗診断は再現しない（#846）。
fn char_class_line(name: &str) -> Expr {
    let body = if name == "empty" {
        Expr::character("SpaceParser", |c| " \t\u{b}\u{c}".contains(c))
    } else {
        Expr::character("WildCardStringParser", |c| c != '\r' && c != '\n')
    };
    Expr::sequence([Expr::Custom(bol), body.repeat_java(0, None), line_end()])
}
pub fn verify_char_class() {
    for row in include_str!("char-class.tsv")
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
    {
        let c: Vec<_> = row.split('\t').collect();
        assert_eq!(c.len(), 12, "{row}");
        let source = decode(c[2]);
        let (root, repeat) = if c[0] == "empty" {
            ("EmptyLineParser/", "ZeroOrMore/")
        } else {
            ("WildCardLineParser/", "WildCardStringTerminatorParser/")
        };
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
                Diagnostics::Auto,
            ] {
                let mut context = ParseContext::with_options(
                    &source,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                let result =
                    context.parse_with_token_mode(&char_class_line(c[0]), kind(c[1]), false);
                assert_eq!(result.is_ok().to_string(), c[7], "{row}");
                assert_eq!(context.position().to_string(), c[8], "{row}");
                assert_eq!(context.matched_position().to_string(), c[9], "{row}");
                let farthest = if diagnostics == Diagnostics::DetailedOnFailure {
                    "0"
                } else {
                    c[10]
                };
                assert_eq!(context.failure().offset.to_string(), farthest, "{row}");
                if c[11] != "-" {
                    let nodes = result.as_ref().map(|r| r.nodes.clone()).unwrap_or_default();
                    let cst = forest(&mut context, &nodes, false)
                        .replacen("Chain/", root, 1)
                        .replacen("ZeroOrMore/", repeat, 1);
                    assert_eq!(cst, c[11], "{row}");
                }
            }
        }
    }
}
#[allow(dead_code)]
fn main() {
    verify_lexical_boundaries();
    verify_repeat_choice();
    verify_transactions();
    verify_compositions();
    verify_char_class();
    println!("line-boundary observations: {}", verify());
}
