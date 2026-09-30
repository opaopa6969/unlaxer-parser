use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};

static NEXT: AtomicUsize = AtomicUsize::new(0);

struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-skip-{}-{}",
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

fn run_generated(grammar: &str, probe: &str) {
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
    for file in unlaxer_generator::generate(grammar).unwrap() {
        fs::write(generated.join(file.relative_path), file.content).unwrap();
    }
    fs::write(temp.0.join("main.rs"), probe).unwrap();
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

#[test]
fn skipped_rule_preserves_cst_and_full_text_captures_without_descendant_ast() {
    let grammar = "grammar G { @root @mapping(Bundle,params=[head,maybe,items]) Document ::= Hidden @head ':' [ Hidden @maybe ] ':' { Hidden @items }; @skip @mapping(Ghost,params=[part]) Hidden ::= '😀' Child @part 'z'; @mapping(Child) Child ::= 'x'; }";
    assert!(unlaxer_generator::portability::check(grammar).portable);
    run_generated(
        grammar,
        r#"
mod generated;
use generated::ast::Ast;
fn main() {
    let input = "😀xz:😀xz:😀xz😀xz";
    let tree = generated::parser::parse_tree(input).unwrap();
    assert_eq!(tree.nodes[tree.root].span.end, input.chars().count());
    assert_eq!(tree.nodes.iter().filter(|node| node.rule == 1).count(), 4);
    assert_eq!(tree.nodes.iter().filter(|node| node.rule == 2).count(), 4);
    let ast = generated::mapper::map(&tree).unwrap();
    let Ast::Bundle { head, maybe, items, .. } = ast else { panic!("bundle") };
    assert_eq!(head, "😀xz");
    assert_eq!(maybe.as_deref(), Some("😀xz"));
    assert_eq!(items, vec!["😀xz", "😀xz"]);
    assert!(generated::parser::parse_tree("😀xy::").is_err());
}
"#,
    );
}

#[test]
fn skipped_root_and_transparent_alias_parse_but_map_to_no_ast() {
    for grammar in [
        "grammar G { @root @skip @mapping(Ghost) Root ::= 'x'; }",
        "grammar G { @root Root ::= (Hidden); @skip @mapping(Ghost) Hidden ::= 'x'; }",
    ] {
        assert!(unlaxer_generator::portability::check(grammar).portable);
        run_generated(
            grammar,
            r#"
mod generated;
fn main() {
    let tree = generated::parser::parse_tree("x").unwrap();
    assert_eq!(tree.nodes[tree.root].span.end, 1);
    assert_eq!(generated::mapper::map(&tree).unwrap_err(), "expected one value for root, got 0");
}
"#,
        );
    }
}

#[test]
fn skipped_mapping_does_not_claim_a_generated_name_or_field() {
    let grammar = "grammar G { @root @mapping(Visible) Root ::= 'v'; @skip @mapping(Visible.Inner,params=[span,semantics]) Hidden ::= 'a' @span 'b' @semantics; }";
    assert!(unlaxer_generator::portability::check(grammar).portable);
    let files = unlaxer_generator::generate(grammar).unwrap();
    let ast = files
        .iter()
        .find(|file| file.relative_path == "ast.rs")
        .unwrap();
    assert!(ast.content.contains("r#Visible"));
    assert!(!ast.content.contains("Visible.Inner"));
}

#[test]
fn skipped_branch_is_one_text_value_even_when_it_contains_a_mapped_child() {
    let grammar = "grammar G { @root @mapping(Bag,params=[value]) Root ::= Hidden @value | Child @value; @skip Hidden ::= '😀' Child 'z'; @mapping(Child) Child ::= 'x'; }";
    run_generated(
        grammar,
        r#"
mod generated;
use generated::ast::{Ast, AstValue};
fn main() {
    let skipped = generated::parser::parse_tree("😀xz").unwrap();
    let Ast::Bag { value, .. } = generated::mapper::map(&skipped).unwrap() else { panic!("bag") };
    assert!(matches!(value, AstValue::Text { text, span } if text == "😀xz" && span.start == 0 && span.end == 3));
    let mapped = generated::parser::parse_tree("x").unwrap();
    let Ast::Bag { value, .. } = generated::mapper::map(&mapped).unwrap() else { panic!("bag") };
    assert!(matches!(value, AstValue::Node(node) if matches!(*node, Ast::Child { .. })));
}
"#,
    );
}
