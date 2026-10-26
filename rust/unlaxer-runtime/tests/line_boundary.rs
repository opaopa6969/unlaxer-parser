#[path = "../../../conformance/line-boundary/probe.rs"]
mod probe;

#[test]
fn classic_line_boundaries_match_independent_oracle() {
    assert!(probe::verify() > 0);
}

#[test]
fn guarded_repeat_does_not_claim_terminated_repeat_cst_parity() {
    probe::verify_compositions();
}

#[test]
fn choice_lookahead_and_retry_preserve_cst_and_diagnostics() {
    probe::verify_transactions();
}

#[test]
fn lexical_bol_bof_eol_use_the_same_code_point_boundaries() {
    probe::verify_lexical_boundaries();
}

#[test]
fn repeat_choice_keeps_going_after_a_matched_terminator() {
    probe::verify_repeat_choice();
}

#[test]
fn char_class_repeat_matches_consumed_but_not_terminator_commit() {
    probe::verify_char_class();
}
