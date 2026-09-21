//! Regression coverage for failure-diagnostic bookkeeping allocations. A memoized rule
//! installs a `FailureDiagnostic` frame, so this checks both the parse-wide expected set and
//! the memo frame while keeping process-wide allocation counting single-threaded.

use unlaxer_alloc_audit::{allocations, CountingAllocator};
use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Rule,
};

#[global_allocator]
static GLOBAL: CountingAllocator = CountingAllocator;

const REPEATED_FAILURES: usize = 1_024;

fn allocations_for_identical_failures(failures: usize) -> usize {
    let grammar = share_grammar(vec![Rule {
        name: "root",
        expression: Expr::choice((0..failures).map(|_| Expr::literal("expected"))),
    }]);
    let mut context = ParseContext::with_options(
        "actual",
        ParseOptions::with_memoization(Memoization::SafeFailures),
    );

    let before = allocations();
    let error = context
        .parse_shared_grammar(&grammar, 0, false)
        .unwrap_err();
    let after = allocations();

    assert_eq!(error.offset, 0);
    assert_eq!(error.expected, vec!["expected"]);
    after.allocations - before.allocations
}

fn check_repeated_interned_failures_allocate_nothing() {
    let mut context = ParseContext::new("actual");
    // Exercise both the inline first name and the hash table, including a name
    // originally supplied by an owned string through the public custom-parser API.
    context.error("first");
    context.error(&String::from("expected"));
    for name in ["first", "expected"] {
        let parser = Expr::literal(name).optional();
        let before = allocations();
        for _ in 0..REPEATED_FAILURES {
            context.parse(&parser).unwrap();
        }
        let count = allocations().allocations - before.allocations;
        assert_eq!(count, 0, "an interned name must not allocate again");
        println!("interned {name}: {REPEATED_FAILURES} identical failures = {count} allocations");
    }
    assert_eq!(context.failure().expected, vec!["expected", "first"]);
}

fn check_deferred_success_failures_allocate_nothing() {
    // A fresh context: unlike the interned-name contract above, no diagnostic
    // allocation or warmup is permitted, even for the first failed alternative.
    let parser = Expr::literal("expected").optional();
    let mut counts = Vec::new();
    for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
        let mut context = ParseContext::with_options(
            "actual",
            ParseOptions::default().with_diagnostics(diagnostics),
        );
        let before = allocations();
        for _ in 0..REPEATED_FAILURES {
            context.parse(&parser).unwrap();
        }
        let count = allocations().allocations - before.allocations;
        counts.push(count);
        println!("{diagnostics:?}: {REPEATED_FAILURES} failures on success = {count} allocations");
    }
    assert!(counts[0] > counts[1]);
    assert_eq!(counts[1], 0);
}

fn check_deferred_success_skips_memo_diagnostic_allocations() {
    let grammar = share_grammar(vec![
        Rule {
            name: "root",
            expression: Expr::choice([Expr::Rule(1), Expr::Rule(1), Expr::literal("actual")]),
        },
        Rule {
            name: "failure",
            expression: Expr::choice([Expr::literal("first"), Expr::literal("second")]),
        },
    ]);
    let mut counts = Vec::new();
    for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
        let mut context = ParseContext::with_options(
            "actual",
            ParseOptions::with_memoization(Memoization::SafeFailures).with_diagnostics(diagnostics),
        );
        let before = allocations();
        context.parse_shared_grammar(&grammar, 0, false).unwrap();
        let count = allocations().allocations - before.allocations;
        counts.push(count);
        println!("{diagnostics:?}: successful memoized choice = {count} allocations");
    }
    assert!(counts[1] < counts[0]);
}

fn main() {
    let single = allocations_for_identical_failures(1);
    let repeated = allocations_for_identical_failures(REPEATED_FAILURES);
    println!(
        "diagnostic_allocation: 1 failure = {single} allocations, \
         {REPEATED_FAILURES} identical failures = {repeated} allocations"
    );
    assert!(
        single <= 9,
        "a first-bucket failure with an inline expected ID must not exceed 9 allocations"
    );
    assert_eq!(
        single, repeated,
        "identical failures at one position must not allocate per attempt"
    );
    check_repeated_interned_failures_allocate_nothing();
    check_deferred_success_failures_allocate_nothing();
    check_deferred_success_skips_memo_diagnostic_allocations();
}
