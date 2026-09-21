//! Regression coverage for failure-diagnostic bookkeeping allocations. A memoized rule
//! installs a `FailureDiagnostic` frame, so this checks both the parse-wide expected set and
//! the memo frame while keeping process-wide allocation counting single-threaded.

use unlaxer_alloc_audit::{allocations, CountingAllocator};
use unlaxer_runtime::{share_grammar, Expr, Memoization, ParseContext, ParseOptions, Rule};

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

fn main() {
    let single = allocations_for_identical_failures(1);
    let repeated = allocations_for_identical_failures(REPEATED_FAILURES);
    println!(
        "diagnostic_allocation: 1 failure = {single} allocations, \
         {REPEATED_FAILURES} identical failures = {repeated} allocations"
    );
    assert!(
        single <= 11,
        "a first-bucket failure must not exceed the unbucketed baseline of 11 allocations"
    );
    assert_eq!(
        single, repeated,
        "identical failures at one position must not allocate per attempt"
    );
}
