use std::fmt::Write;
use unlaxer_runtime::{
    token::TokenKind, Diagnostics, Expr, Memoization, ParseContext, ParseOptions,
};
fn decode(s: &str) -> String {
    if s == "~" {
        return String::new();
    }
    String::from_utf8(
        s.as_bytes()
            .chunks_exact(2)
            .map(|p| u8::from_str_radix(std::str::from_utf8(p).unwrap(), 16).unwrap())
            .collect(),
    )
    .unwrap()
}
fn encode(s: &str) -> String {
    if s.is_empty() {
        return "~".into();
    }
    s.as_bytes().iter().fold(String::new(), |mut v, c| {
        write!(v, "{c:02x}").unwrap();
        v
    })
}
fn kind(s: &str) -> TokenKind {
    match s {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("{s}"),
    }
}
fn parser(s: &str) -> Expr {
    if let Some((wrapper, base)) = s.split_once('-') {
        let child = parser(base);
        return match wrapper {
            "match" => child.match_only(),
            "consume" => child.stop_consume(),
            "stopinvert" => child.stop_invert(),
            "invert" => child.invert_match(),
            _ => panic!("{wrapper}"),
        };
    }
    match s {
        "start" => Expr::CodeStart,
        "end" => Expr::CodeEnd,
        "long" => Expr::LongCodeBlock,
        _ => panic!("{s}"),
    }
}
fn leaves(ctx: &mut ParseContext<'_>, index: usize, output: &mut Vec<String>) {
    let children = ctx.node(index).unwrap().children.clone();
    if children.is_empty() {
        let id = ctx.token_node_id(index).unwrap();
        let info = ctx.token_info(id).unwrap();
        let kind = match info.kind {
            TokenKind::Consumed => "consumed",
            TokenKind::MatchOnly => "matchOnly",
            TokenKind::VirtualConsumed => "virtualTokenConsumed",
            TokenKind::VirtualMatchOnly => "virtualTokenMatchOnly",
        };
        let span = match &info.source {
            unlaxer_runtime::token::TokenSource::Input(span)
            | unlaxer_runtime::token::TokenSource::Projected { span, .. } => *span,
            unlaxer_runtime::token::TokenSource::Generated { anchor, text } => {
                unlaxer_runtime::Span {
                    start: *anchor,
                    end: *anchor + text.chars().count(),
                }
            }
        };
        output.push(format!(
            "{kind}@{}:{}:{}",
            span.start,
            span.end - span.start,
            encode(ctx.token_text(id).unwrap())
        ));
    } else {
        for child in children {
            leaves(ctx, child, output);
        }
    }
}
fn observe(traced: bool) -> Vec<String> {
    let mut report = Vec::new();
    for (mi, memo) in [Memoization::Off, Memoization::SafeFailures]
        .into_iter()
        .enumerate()
    {
        for (di, diag) in [
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
            Diagnostics::Auto,
        ]
        .into_iter()
        .enumerate()
        {
            for row in include_str!("../../../conformance/tiny-classic-modes/cases.tsv")
                .lines()
                .filter(|l| !l.starts_with('#') && !l.is_empty())
            {
                let c: Vec<_> = row.split('\t').collect();
                let input = decode(c[1]);
                let mut ctx = ParseContext::with_options(
                    &input,
                    if traced {
                        ParseOptions::with_memoization(memo)
                            .with_diagnostics(diag)
                            .with_token_trace(Default::default())
                    } else {
                        ParseOptions::with_memoization(memo).with_diagnostics(diag)
                    },
                );
                ctx.set_reset_matched_with_consumed(false);
                for (col, k) in [(5, TokenKind::Consumed), (6, TokenKind::MatchOnly)] {
                    if c[col] != "~" {
                        let text: &'static str = Box::leak(decode(c[col]).into_boxed_str());
                        ctx.parse_with_token_mode(&Expr::Literal(text), k, false)
                            .unwrap();
                    }
                }
                ctx.set_reset_matched_with_consumed(c[7] == "true");
                let trace_before = if traced {
                    ctx.token_trace().unwrap().unwrap().len()
                } else {
                    0
                };
                let result = ctx.with_trivia(true, |ctx| {
                    ctx.parse_with_token_mode(&parser(c[2]), kind(c[3]), c[4] == "true")
                });
                let mut actual = vec![
                    result.is_ok().to_string(),
                    ctx.position().to_string(),
                    ctx.matched_position().to_string(),
                ];
                let mut terminal = Vec::new();
                if let Ok(value) = result {
                    for index in &value.nodes {
                        leaves(&mut ctx, *index, &mut terminal);
                    }
                    assert_eq!(value.nodes.len(), 1, "{}", c[0]);
                    let id = ctx.token_node_id(value.nodes[0]).unwrap();
                    let info = ctx.token_info(id).unwrap();
                    let expected_kind = if c[2].starts_with("match-") {
                        TokenKind::MatchOnly
                    } else if c[2].starts_with("consume-") {
                        TokenKind::Consumed
                    } else {
                        kind(c[3])
                    };
                    assert_eq!(info.kind, expected_kind, "{}", c[0]);
                    actual.extend([
                        encode(ctx.token_text(id).unwrap()),
                        value.span.start.to_string(),
                        (value.span.end - value.span.start).to_string(),
                    ]);
                } else {
                    actual.extend(["-".into(), "-".into(), "-".into()]);
                }
                let expected = c[8..].iter().map(|s| s.to_string()).collect::<Vec<_>>();
                assert_eq!(actual, expected, "{}/{mi}/{di}", c[0]);
                if traced {
                    let trace = ctx.token_trace().unwrap().unwrap();
                    let actual = trace
                        .iter()
                        .skip(trace_before)
                        .map(|leaf| {
                            let k = match leaf.kind {
                                TokenKind::Consumed => "consumed",
                                TokenKind::MatchOnly => "matchOnly",
                                TokenKind::VirtualConsumed => "virtualTokenConsumed",
                                TokenKind::VirtualMatchOnly => "virtualTokenMatchOnly",
                            };
                            let span = leaf.span();
                            format!(
                                "{k}@{}:{}:{}",
                                span.start,
                                span.end - span.start,
                                encode(leaf.text(&input).unwrap())
                            )
                        })
                        .collect::<Vec<_>>();
                    let expected = terminal.clone();
                    assert_eq!(actual, expected, "committed trace {}", c[0]);
                }
                report.push(format!(
                    "{}\t{mi}\t{di}\t{}\t{}\t{}\t{}",
                    c[0],
                    actual.join("\t"),
                    ctx.failure().offset,
                    !ctx.failure().expected.is_empty(),
                    if terminal.is_empty() {
                        "~".into()
                    } else {
                        terminal.join(";")
                    }
                ));
            }
        }
    }
    report
}
#[test]
fn shared_handwritten_tiny_mode_oracle() {
    let plain = observe(false);
    assert_eq!(plain.len(), 4464);
    assert_eq!(plain, observe(true));
}
fn main() {
    for row in observe(true) {
        println!("{row}");
    }
}

#[test]
fn deep_fence_wrappers_fail_with_a_resource_error_on_two_mib() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            for base in [Expr::CodeStart, Expr::CodeEnd, Expr::LongCodeBlock] {
                let mut expr = base;
                for _ in 0..1500 {
                    expr = expr.match_only();
                }
                let mut ctx = ParseContext::new("````x:A\ntext\n````");
                let error = ctx
                    .parse_with_token_mode(&expr, TokenKind::Consumed, false)
                    .unwrap_err();
                assert_eq!(error.expected, ["expression nesting below 1024"]);
                while let Expr::MatchOnly(child) = expr {
                    expr = *child;
                }
                assert_eq!(ctx.position(), 0);
                assert_eq!(ctx.matched_position(), 0);
            }
        })
        .unwrap()
        .join()
        .unwrap();
}
