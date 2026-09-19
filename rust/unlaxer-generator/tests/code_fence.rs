use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::atomic::{AtomicUsize, Ordering};

const GRAMMAR: &str = r#"
grammar CodeFence {
  @whitespace: javaStyle
  token START = org.unlaxer.tinyexpression.parser.javalang.CodeStartParser
  token END = org.unlaxer.tinyexpression.parser.javalang.CodeEndParser
  @root @mapping(Value, params=[value])
  Root ::= (START | END) @value;
}
"#;

static NEXT: AtomicUsize = AtomicUsize::new(0);

struct Directory(PathBuf);

impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-code-fence-{}-{}",
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
fn exact_tiny_expression_classes_lower_to_atomic_fence_expressions() {
    let files = unlaxer_generator::generate(GRAMMAR).unwrap();
    let parser = &files
        .iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(
        parser.contains("Expr::Choice(vec![Expr::CodeStart, Expr::CodeEnd])"),
        "{parser}"
    );

    for (exact, rejected) in [
        (
            "org.unlaxer.tinyexpression.parser.javalang.CodeStartParser",
            "CodeStartParser",
        ),
        (
            "org.unlaxer.tinyexpression.parser.javalang.CodeStartParser",
            "other.CodeStartParser",
        ),
        (
            "org.unlaxer.tinyexpression.parser.javalang.CodeEndParser",
            "CodeEndParser",
        ),
        (
            "org.unlaxer.tinyexpression.parser.javalang.CodeEndParser",
            "other.CodeEndParser",
        ),
    ] {
        let grammar = GRAMMAR.replace(exact, rejected);
        let error = unlaxer_generator::generate(&grammar).unwrap_err();
        assert!(error.contains("unsupported external token"), "{error}");
    }
}

#[test]
fn native_generated_code_fence_parser_compiles_and_runs_without_java() {
    let directory = Directory::new();
    let grammar = directory.0.join("CodeFence.ubnf");
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
use unlaxer_runtime::{Expr, ParseContext};

fn main() {
    for source in ["```java:a.B", "```java:a.b.Co\n", "```", "```\r\n"] {
        assert!(generated::parser::parse_tree(source).is_ok(), "{source:?}");
    }
    for source in [
        "``` java:a.B", "```java :a.B", "```java: a.B", "```java:a. B",
        "```java:a.B trailing", "``` trailing", "x```\n",
    ] {
        assert!(generated::parser::parse_tree(source).is_err(), "{source:?}");
    }

    let source = String::from("😀\n```java:a.B\ntail");
    let mut context = ParseContext::new(&source);
    context.parse(&Expr::literal("😀\n")).unwrap();
    let matched = generated::parser::parse_context(&mut context).unwrap();
    assert_eq!(matched.span.start, 2);
    assert_eq!(context.position(), 14);
    assert_eq!(context.matched_position(), 14);
    assert_eq!(context.remaining(), "tail");
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
