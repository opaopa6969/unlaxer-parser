//! Nesting limits of the native generator, pinned at the boundaries that the Java host
//! shares (#740) or documents as a frontend difference (#741, docs/java-parser-limits.md).

fn recursive_plus(depth: usize) -> String {
    format!(
        "grammar Pbt {{ @package: pbt.corpus @root @mapping(N0, params=[x]) R0 ::= (H H) @x ;\n  H ::= 'n' R0 | 'y' {}H{}+ | 'a' ; }}\n",
        "(".repeat(depth),
        ")".repeat(depth)
    )
}

fn recursive_separator(depth: usize) -> String {
    format!(
        "grammar Pbt {{ @package: pbt.corpus @root @mapping(N0, params=[x]) R0 ::= (T T) @x ;\n  T ::= 'n' T | 'a' (',' % {}U{}) ; U ::= 'u' T ; }}\n",
        "(".repeat(depth),
        ")".repeat(depth)
    )
}

fn outcome(source: &str) -> Result<(), String> {
    unlaxer_generator::generate(source).map(|_| ())
}

#[test]
fn recursive_shape_analysis_shares_the_structural_depth() {
    for grammar in [recursive_plus, recursive_separator] {
        // Java rejected 85..121 with E-RECURSIVE-SHAPE before #740; both hosts accept them now.
        for depth in [84, 85, 86, 121] {
            assert_eq!(outcome(&grammar(depth)), Ok(()), "depth {depth}");
        }
        let error = outcome(&grammar(122)).unwrap_err();
        assert_eq!(error, "structural analysis depth exceeds 256");
    }
}

#[test]
fn frontend_nesting_limit_precedes_lowering() {
    // The rule body of H plus 127 groups is the 128th body level of the native frontend.
    assert_eq!(
        outcome(&recursive_plus(127)).unwrap_err(),
        "structural analysis depth exceeds 256"
    );
    let error = outcome(&recursive_plus(128)).unwrap_err();
    assert!(error.ends_with("UBNF nesting limit exceeded"), "{error}");
    // The separator operand sits one body level deeper.
    let error = outcome(&recursive_separator(127)).unwrap_err();
    assert!(error.ends_with("UBNF nesting limit exceeded"), "{error}");
}
