//! Atoms that cannot leave state behind on failure run without a checkpoint (#239). These tests
//! pin that the observable outcome of such an atom is identical whether or not a checkpoint
//! wraps it, and that the atoms which do advance before failing keep their wrapper.

use unlaxer_runtime::{Expr, ParseContext};

/// Each atom paired with an input on which it fails.
fn failing_atoms() -> Vec<(Expr, &'static str)> {
    vec![
        (Expr::Literal("ab"), "ax"),
        (Expr::Number, "+x"),
        (Expr::Identifier, "1abc"),
        (Expr::Backreference("name"), "zzz"),
        (Expr::Eof, "rest"),
        (Expr::Error("boom"), "anything"),
        (Expr::Any, ""),
        (Expr::CharRange('a', 'c'), "z"),
        (Expr::Except("z"), "z"),
        (Expr::Until("end"), "no terminator here"),
        (
            Expr::JavaLookahead {
                pattern: "x",
                positive: true,
            },
            "y",
        ),
    ]
}

fn observe(
    context: &mut ParseContext<'_>,
    expression: &Expr,
) -> (String, usize, usize, Option<usize>) {
    let error = context
        .parse(expression)
        .expect_err("atom must fail on this input");
    (
        format!("{error:?}"),
        context.position(),
        context.matched_position(),
        context.state::<usize>("marker").copied(),
    )
}

#[test]
fn unwrapped_atoms_fail_exactly_like_wrapped_ones() {
    for (atom, input) in failing_atoms() {
        let mut direct = ParseContext::new(input);
        direct.set_state("marker", 7usize);
        let mut wrapped = ParseContext::new(input);
        wrapped.set_state("marker", 7usize);
        let sequence = Expr::Sequence(vec![atom.clone()]);
        let outcome = observe(&mut direct, &atom);
        assert_eq!(
            outcome,
            observe(&mut wrapped, &sequence),
            "{atom:?} on {input:?}"
        );
        assert_eq!(outcome.1, 0, "{atom:?} must not advance on failure");
        assert_eq!(
            outcome.2, 0,
            "{atom:?} must not move the match cursor on failure"
        );
        assert_eq!(outcome.3, Some(7), "{atom:?} must keep user state");
        assert_eq!(direct.scopes().current_scope_depth(), 0);
    }
}

#[test]
fn unwrapped_atoms_open_no_checkpoint_of_their_own() {
    let mut context = ParseContext::new("y");
    context.enable_checkpoint_metrics();
    assert!(context.parse(&Expr::Literal("x")).is_err());
    // parse() opens one transaction; the literal itself no longer opens a second one.
    assert_eq!(context.snapshot_checkpoint_metrics().opened, 1);

    let mut wrapped = ParseContext::new("y");
    wrapped.enable_checkpoint_metrics();
    assert!(wrapped
        .parse(&Expr::Sequence(vec![Expr::Literal("x")]))
        .is_err());
    // parse() plus the sequence; the literal inside still opens none.
    assert_eq!(wrapped.snapshot_checkpoint_metrics().opened, 2);
}

#[test]
fn atoms_that_advance_before_failing_keep_their_checkpoint() {
    let mut context = ParseContext::new("\"unterminated");
    context.enable_checkpoint_metrics();
    assert!(context.parse(&Expr::Quoted('"')).is_err());
    assert_eq!(
        context.position(),
        0,
        "the wrapper must restore the opening quote"
    );
    assert_eq!(context.snapshot_checkpoint_metrics().opened, 2);
}
