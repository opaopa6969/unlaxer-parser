use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};

use unlaxer_codegen::ir::Expression;
use unlaxer_codegen::ir::RecoveryMode;

static NEXT: AtomicUsize = AtomicUsize::new(0);

struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-capture-equality-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

fn lower(source: &str) -> unlaxer_codegen::GrammarIr {
    let grammar = unlaxer_ubnf::parse(source).unwrap();
    unlaxer_generator::lowering::lower(&grammar.grammars[0]).unwrap()
}

#[test]
fn recovery_modes_wrap_rules_and_use_ordered_follow_tokens() {
    let source = "grammar G { @root @mapping(Root) Root ::= Auto ';' Skip '!' Sync; @recovery(auto) Auto ::= 'a'; @recovery(skip) Skip ::= 'b'; @recovery(sync=';,}') Sync ::= 'c'; }";
    let ir = lower(source);
    assert!(matches!(&ir.rules[1].body,
        Expression::Recovery { mode: RecoveryMode::BeforeSync, tokens, message, .. }
        if tokens == &[";".to_owned()] && message == "syntax error: skipped to sync point"));
    assert!(matches!(&ir.rules[2].body,
        Expression::Recovery { mode: RecoveryMode::Skip, tokens, .. }
        if tokens == &["!".to_owned()]));
    assert!(matches!(&ir.rules[3].body,
        Expression::Recovery { mode: RecoveryMode::Sync, tokens, .. }
        if tokens == &[";".to_owned(), "}".to_owned()]));
    let generated = unlaxer_generator::generate(source).unwrap();
    assert_eq!(generated.len(), 5);
    let parser = generated
        .iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap();
    assert!(parser
        .content
        .contains("unlaxer_runtime::RecoveryMode::BeforeSync"));
    let mapper = generated
        .iter()
        .find(|file| file.relative_path == "mapper.rs")
        .unwrap();
    assert!(mapper.content.contains("cannot map recovered syntax"));
}

#[test]
fn predictive_reference_to_recoverable_rule_is_not_pruned() {
    let source = "grammar G { @root @mapping(Root,params=[value]) @predictiveChoice Root ::= Recoverable @value | 'x' @value; @recovery(sync=';') Recoverable ::= 'a'; }";
    let ir = lower(source);
    let Expression::PredictiveChoice { predictors, .. } = &ir.rules[0].body else {
        panic!("expected predictive choice");
    };
    assert!(matches!(predictors[0], unlaxer_codegen::ir::Predictor::Any));
    assert_eq!(unlaxer_generator::generate(source).unwrap().len(), 5);
}

#[test]
fn scope_less_backref_lowers_to_outer_capture_equality() {
    let source = "grammar G { @root @mapping(Pair,params=[item]) @backref(name=item) Root ::= 'a' @item 'b' @item; }";
    let ir = lower(source);
    assert!(
        matches!(ir.rules[0].body, Expression::CaptureEquality { ref name, .. } if name == "item")
    );
    let parser = unlaxer_generator::generate(source)
        .unwrap()
        .into_iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap();
    assert!(parser.content.contains("Expr::compare_captures(\"item\", "));
}

#[test]
fn scoped_backref_keeps_rule_effects_and_invalid_targets_are_rejected() {
    let source = "grammar G { @root @mapping(Pair,params=[item]) @scopeTree(mode=lexical) @backref(name=item) Root ::= 'a' @item; }";
    let ir = lower(source);
    assert!(matches!(ir.rules[0].body, Expression::RuleEffects { .. }));
    assert!(!unlaxer_generator::generate(source)
        .unwrap()
        .iter()
        .any(|file| file.content.contains("Expr::compare_captures")));
    for source in [
        "grammar G { @root @backref(name=missing) Root ::= 'x'; }",
        "grammar G { @root @backref(name=item) @backref(name=item) Root ::= 'x' @item; }",
    ] {
        assert!(unlaxer_generator::generate(source).is_err(), "{source}");
    }
}

#[test]
fn capture_equality_only_root_generates_compilable_empty_ast() {
    let source = "grammar G { @root @backref(name=item) Root ::= 'a' @item 'b' @item; }";
    let temp = Temp::new();
    let runtime = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../unlaxer-runtime/src/lib.rs");
    let runtime_lib = temp.0.join("libunlaxer_runtime.rlib");
    let output = Command::new("rustc")
        .args([
            "--edition=2021",
            "--crate-type=rlib",
            "--crate-name=unlaxer_runtime",
        ])
        .arg(runtime)
        .arg("-o")
        .arg(&runtime_lib)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let generated = temp.0.join("generated");
    fs::create_dir(&generated).unwrap();
    for file in unlaxer_generator::generate(source).unwrap() {
        fs::write(generated.join(file.relative_path), file.content).unwrap();
    }
    fs::write(
        temp.0.join("main.rs"),
        r#"
mod generated;
use unlaxer_runtime::Severity;
fn main() {
    let tree = generated::parser::parse_tree("ab").unwrap();
    assert_eq!(generated::mapper::map(&tree).unwrap_err(), "expected one value for root, got 0");
    let diagnostics = tree.scopes().diagnostics();
    assert_eq!(diagnostics.len(), 1);
    assert_eq!(diagnostics[0].message, "back-reference mismatch: expected 'a' but got 'b'");
    assert_eq!((diagnostics[0].offset, diagnostics[0].length), (1, 1));
    assert_eq!(diagnostics[0].severity, Severity::Error);
}
"#,
    )
    .unwrap();
    let output = Command::new("rustc")
        .arg("--edition=2021")
        .arg(temp.0.join("main.rs"))
        .arg("--extern")
        .arg(format!("unlaxer_runtime={}", runtime_lib.display()))
        .arg("-o")
        .arg(temp.0.join("probe"))
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let output = Command::new(temp.0.join("probe")).output().unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
}
