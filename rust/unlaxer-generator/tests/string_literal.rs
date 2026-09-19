use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::atomic::{AtomicUsize, Ordering};

const GRAMMAR: &str = r#"
grammar StringLiteral {
  token STRING = org.unlaxer.tinyexpression.parser.StringLiteralParser
  @root @mapping(Value, params=[value])
  Root ::= STRING @value;
}
"#;

static NEXT: AtomicUsize = AtomicUsize::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-string-literal-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}

impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("..")
}

fn success(output: Output) -> Output {
    assert!(
        output.status.success(),
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    output
}

#[test]
fn exact_tiny_expression_class_lowers_to_ordered_quote_choice() {
    let files = unlaxer_generator::generate(GRAMMAR).unwrap();
    let parser = &files
        .iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(
        parser.contains("Expr::Choice(vec![Expr::Quoted('\\u{22}'), Expr::Quoted('\\u{27}')])"),
        "{parser}"
    );

    for parser_class in ["StringLiteralParser", "other.StringLiteralParser"] {
        let grammar = GRAMMAR.replace(
            "org.unlaxer.tinyexpression.parser.StringLiteralParser",
            parser_class,
        );
        let error = unlaxer_generator::generate(&grammar).unwrap_err();
        assert!(error.contains("unsupported external token"), "{error}");
    }
}

#[test]
fn native_generated_string_literal_parser_compiles_and_evaluates() {
    let directory = Directory::new();
    let grammar = directory.0.join("StringLiteral.ubnf");
    let generated = directory.0.join("generated");
    fs::write(&grammar, GRAMMAR).unwrap();

    success(
        Command::new(env!("CARGO_BIN_EXE_unlaxer"))
            .args(["generate", "--grammar"])
            .arg(&grammar)
            .arg("--output")
            .arg(&generated)
            .env("PATH", "")
            .env("JAVA_HOME", "/nonexistent-unlaxer-java")
            .output()
            .unwrap(),
    );

    let runtime = directory.0.join("libunlaxer_runtime.rlib");
    success(
        Command::new("rustc")
            .args([
                "--edition=2021",
                "--crate-type=rlib",
                "--crate-name=unlaxer_runtime",
            ])
            .arg(repo().join("unlaxer-runtime/src/lib.rs"))
            .arg("-o")
            .arg(&runtime)
            .output()
            .unwrap(),
    );

    fs::write(
        directory.0.join("main.rs"),
        r#"
mod generated;
use generated::evaluator::{evaluate, Semantics};
use unlaxer_runtime::Span;

struct Eval;

impl Semantics for Eval {
    type Output = String;
    fn eval_value(&mut self, value: &str, _: Span) -> String { value.into() }
}

fn main() {
    for (source, expected) in [
        ("\"double\"", "\"double\""),
        ("'single'", "single"),
        ("\"escaped\\\"quote\"", "\"escaped\\\"quote\""),
        (r"'escaped\'quote'", r"escaped\'quote"),
        ("\"\"", "\"\""),
        ("''", ""),
    ] {
        let tree = generated::parser::parse_tree(source).unwrap();
        let ast = generated::mapper::map(&tree).unwrap();
        assert_eq!(evaluate(&ast, &mut Eval), expected, "{source}");
    }
    for source in ["bare", "\"unterminated", "'unterminated", "\"ok\"tail", "'ok'tail"] {
        assert!(generated::parser::parse_tree(source).is_err(), "{source}");
    }
}
"#,
    )
    .unwrap();

    success(
        Command::new("rustc")
            .arg("--edition=2021")
            .arg(directory.0.join("main.rs"))
            .arg("--extern")
            .arg(format!("unlaxer_runtime={}", runtime.display()))
            .arg("-o")
            .arg(directory.0.join("probe"))
            .output()
            .unwrap(),
    );
    success(Command::new(directory.0.join("probe")).output().unwrap());
}
