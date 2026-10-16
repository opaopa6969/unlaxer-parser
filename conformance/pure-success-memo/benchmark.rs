// Isolated microbenchmark, not the production #197 workload.
use std::time::Instant;
use unlaxer_runtime::*;
fn driver(c: &mut ParseContext<'_>) -> ParseResult {
    let retries = *c.state::<usize>("retries").unwrap();
    for _ in 0..1000 {
        for _ in 0..retries {
            let _: Result<(), ParseError> = c.transaction(|c| {
                c.parse(&Expr::Rule(1))?;
                Err(ParseError {
                    offset: 0,
                    expected: vec![],
                })
            });
        }
        c.parse(&Expr::Rule(1))?;
        c.parse(&Expr::literal("?"))?;
    }
    c.parse(&Expr::Eof)
}
fn main() {
    println!("retries\tmemo\tmedian_us\tcheckpoints\tsuccess_hits");
    for retries in [0_usize, 8] {
        let input = ("a".repeat(16) + "?").repeat(1000);
        let grammar = share_grammar(vec![
            Rule {
                name: "driver",
                expression: Expr::Custom(driver),
            },
            Rule {
                name: "pure",
                expression: Expr::Sequence(
                    (0..16)
                        .map(|_| Expr::literal("a").capture_value())
                        .collect(),
                ),
            },
        ]);
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let mut samples = vec![];
            let mut metrics = (0, 0);
            for sample in 0..8 {
                let mut c =
                    ParseContext::with_options(&input, ParseOptions::with_memoization(memo));
                c.set_state("retries", retries);
                c.enable_checkpoint_metrics();
                let start = Instant::now();
                c.parse_shared_grammar(&grammar, 0, false).unwrap();
                assert_eq!((c.position(), c.matched_position()), (17000, 17000));
                let elapsed = start.elapsed().as_micros();
                metrics = (
                    c.snapshot_checkpoint_metrics().opened,
                    c.memoized_success_hits(),
                );
                if sample >= 3 {
                    samples.push(elapsed);
                }
            }
            samples.sort();
            println!(
                "{retries}\t{memo:?}\t{}\t{}\t{}",
                samples[2], metrics.0, metrics.1
            );
        }
    }
}
