//! Paired, process-isolated retention benchmark (#290). Counts requested live heap,
//! not RSS. Timings include counting-allocator overhead; do not generalize to P4.
use std::process::Command;
use std::time::Instant;
use unlaxer_alloc_audit::{peak_bytes, reset_peak_bytes, CountingAllocator};
use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Rule,
};

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

fn child() {
    for (token, count) in [("a", 200), ("a", 4000), ("a", 32000), ("😀", 32000)] {
        let grammar = share_grammar(vec![
            Rule {
                name: "root",
                expression: Expr::choice([Expr::Rule(1), Expr::Rule(1), Expr::literal(token)])
                    .repeat(0, None)
                    .then(Expr::Eof),
            },
            Rule {
                name: "failure",
                expression: Expr::literal(token).then(Expr::literal("!")),
            },
        ]);
        let input = token.repeat(count);
        for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
            // Warm the shared grammar outside the measurements.
            for run in 0..8 {
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(Memoization::SafeFailures)
                        .with_diagnostics(diagnostics),
                );
                let baseline = reset_peak_bytes();
                let start = Instant::now();
                let matched = context.parse_shared_grammar(&grammar, 0, false).unwrap();
                let nanos = start.elapsed().as_nanos();
                let peak = peak_bytes() - baseline;
                assert_eq!(matched.span.end, count);
                assert_eq!(context.remaining(), "");
                if run > 0 {
                    println!("{token}\t{count}\t{diagnostics:?}\t{run}\t{peak}\t{nanos}");
                }
            }
        }
    }
}

fn main() {
    if std::env::args().any(|arg| arg == "--child") {
        child();
        return;
    }
    let mut outputs = vec![];
    println!("policy\ttoken\tcode_points\tdiagnostics\trun\tpeak_extra_live_bytes\telapsed_ns");
    for (policy, enabled) in [("retained", "false"), ("evicting", "true")] {
        let output = Command::new(std::env::current_exe().unwrap())
            .arg("--child")
            .env("UNLAXER_MEMO_EVICT_BELOW_FRONTIER", enabled)
            .env("UNLAXER_MEMO_WINDOW", "1024")
            .env("UNLAXER_CANDIDATE_EXCLUSION", "off")
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let result = String::from_utf8(output.stdout).unwrap();
        for line in result.lines() {
            println!("{policy}\t{line}");
        }
        outputs.push(result);
    }
    assert_eq!(outputs[0].lines().count(), 56);
    assert_eq!(outputs[1].lines().count(), 56);
    for (before, after) in outputs[0].lines().zip(outputs[1].lines()) {
        let before: Vec<_> = before.split('\t').collect();
        let after: Vec<_> = after.split('\t').collect();
        assert_eq!(&before[..4], &after[..4]);
        let old_peak: usize = before[4].parse().unwrap();
        let new_peak: usize = after[4].parse().unwrap();
        if before[1] == "200" {
            assert_eq!(old_peak, new_peak);
        } else {
            assert!(
                new_peak * 4 < old_peak * 3,
                "peak did not drop by 25%: {old_peak} -> {new_peak}"
            );
        }
    }
}
