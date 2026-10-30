#[path = "support/mid_parse_diagnostics.rs"]
mod probe;

fn data(text: &str) -> Vec<&str> {
    text.lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .collect()
}

#[test]
fn intermediate_lower_bound_and_exact_final_java_oracle() {
    let java = data(include_str!(
        "../../../conformance/chain-reset-after-failure/mid-parse-java.tsv"
    ));
    let differences = data(include_str!(
        "../../../conformance/chain-reset-after-failure/mid-parse-observations.tsv"
    ));
    let actual = probe::observations();
    assert_eq!(java.len(), 384);
    assert_eq!(java.len(), differences.len());
    assert_eq!(java.len(), actual.len());
    for ((java, difference), actual) in java.iter().zip(differences).zip(actual) {
        let mut expected: Vec<_> = java.split('\t').collect();
        let difference: Vec<_> = difference.split('\t').collect();
        assert_eq!(difference.len(), 7);
        assert_eq!(&expected[..6], &difference[..6]);
        let java_cp: usize = difference[5].parse().unwrap();
        let rust_cp: usize = difference[6].parse().unwrap();
        assert!(
            rust_cp <= java_cp,
            "intermediate lower bound: {difference:?}"
        );
        if ["failure-before", "failure-at-head", "failure-ahead"]
            .iter()
            .any(|shape| expected[0].contains(shape))
        {
            assert_eq!(rust_cp, java_cp, "actual failure: {difference:?}");
        }
        expected[5] = difference[6];
        assert_eq!(expected.join("\t"), actual, "final oracle: {java}");
    }
}

#[test]
fn native_peg_entry_retains_its_separate_success_frontier() {
    use unlaxer_runtime::*;
    for head in ["a", "a😀"] {
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let input = format!("{head}zb");
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                context
                    .parse(&Expr::Sequence(vec![
                        Expr::Literal(head),
                        Expr::Literal("z"),
                    ]))
                    .unwrap();
                assert_eq!(context.position(), head.chars().count() + 1);
                assert_eq!(context.failure().offset, 0);
            }
        }
    }
}
