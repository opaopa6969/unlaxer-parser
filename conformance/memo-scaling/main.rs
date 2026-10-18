use std::cell::Cell;
use unlaxer_runtime::{
    Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult, Rule, Span,
};

thread_local! { static METRICS: Cell<(usize,usize,usize,usize)> = const { Cell::new((0,0,0,0)) }; }

// A non-memoized outer observer reads the generated grammar's still-active cache.
// Generated rules are left unchanged and retain their own purity eligibility.
fn observe(context: &mut ParseContext<'_>) -> ParseResult {
    METRICS.set((
        context.success_memo_entries(),
        context.success_memo_retained_bytes(),
        context.success_memo_budget_misses(),
        context.memoized_success_hits(),
    ));
    let position = context.position();
    Ok(ParseMatch::empty(Span {
        start: position,
        end: position,
    }))
}

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let cases = std::fs::read_to_string(&args[1]).unwrap();
    let budget: usize = args[2].parse().unwrap();
    let mut rules = generated::parser::rules();
    let root = rules.len();
    rules.push(Rule {
        name: "ReadOnlyBudgetObserver",
        expression: Expr::sequence([Expr::Rule(0), Expr::Custom(observe)]),
    });
    let grammar: unlaxer_runtime::SharedGrammar = rules.into();
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for row in cases.lines().skip(1) {
            let row: Vec<usize> = row.split('\t').map(|v| v.parse().unwrap()).collect();
            let input = "let abc = 1+2*3-(4+5)/6; ".repeat(row[1]);
            assert_eq!(input.len(), row[2]);
            let options = ParseOptions::with_memoization(memo)
                .with_diagnostics(Diagnostics::DetailedOnFailure);
            let mut context = ParseContext::with_options(&input, options);
            let parsed = context.parse_shared_grammar(&grammar, root, true).unwrap();
            assert_eq!(context.position(), row[2]);
            assert_eq!(context.matched_position(), row[3]);
            assert_eq!(
                parsed.span,
                Span {
                    start: row[4],
                    end: row[5]
                }
            );
            let root_node = parsed.root_node().unwrap();
            assert_eq!(context.node(root_node).unwrap().span, parsed.span);
            let id = context.token_node_id(root_node).unwrap();
            assert_eq!(context.token_text(id), Some(input.as_str()));
            let mut pending = vec![root_node];
            let mut visited = 0;
            while let Some(id) = pending.pop() {
                let node = context.node(id).unwrap();
                assert!(node.span.start <= node.span.end && node.span.end <= row[5]);
                pending.extend_from_slice(&node.children);
                visited += 1;
                assert!(visited <= input.len() * 64, "invalid cyclic/overlarge CST");
            }
            let (entries, charge, misses, hits) = METRICS.get();
            assert!(entries <= 65536 && charge <= budget);
            if memo == Memoization::Off {
                assert_eq!((entries, charge, misses, hits), (0, 0, 0, 0));
            }
            if budget == 0 {
                assert_eq!((entries, charge), (0, 0));
            }
            if memo == Memoization::SafeFailures {
                if budget > 0 {
                    assert!(
                        entries > 0 && charge > 0,
                        "must observe retained success entries"
                    );
                }
                if budget <= 65536 {
                    assert!(misses > 0, "must exercise finite-budget refusal");
                }
            }
            println!(
                "MEMO_SCALE\t{}\t{:?}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                row[0], memo, row[2], row[3], row[5], entries, charge, misses, hits
            );
        }
    }
}
