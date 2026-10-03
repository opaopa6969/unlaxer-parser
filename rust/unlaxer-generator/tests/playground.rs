use std::fs;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};
static NEXT: AtomicUsize = AtomicUsize::new(0);
struct Scratch(std::path::PathBuf);
impl Scratch {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-playground-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn run(input: &Path, output: &Path, check: bool) -> std::process::Output {
    let mut command = Command::new(env!("CARGO_BIN_EXE_unlaxer"));
    command
        .arg("playground")
        .arg("--grammar")
        .arg(input)
        .arg("--output")
        .arg(output);
    if check {
        command.arg("--check");
    }
    command.output().unwrap()
}
const GRAMMAR: &str = "grammar Demo { @root @mapping(Value, params=[text]) @doc('hello と書く') Start ::= 'hello' @text; }";

#[test]
fn project_is_self_contained_deterministic_and_never_overwrites() {
    let scratch = Scratch::new();
    let input = scratch.0.join("grammar.ubnf");
    fs::write(&input, GRAMMAR).unwrap();
    let output = scratch.0.join("project");
    let result = run(&input, &output, false);
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    assert!(output.join("runtime/src/lib.rs").is_file());
    assert!(fs::read_to_string(output.join("src/lib.rs"))
        .unwrap()
        .contains("generated::parser::RULE_DOCS"));
    assert_eq!(run(&input, &output, false).status.code(), Some(4));
    assert!(run(&input, &output, true).status.success());
    fs::write(output.join("public/index.html"), "handwritten").unwrap();
    assert_eq!(run(&input, &output, true).status.code(), Some(4));
    assert_eq!(
        fs::read_to_string(output.join("public/index.html")).unwrap(),
        "handwritten"
    );
}

#[test]
fn host_binding_is_rejected_without_files() {
    let scratch = Scratch::new();
    let input = scratch.0.join("grammar.ubnf");
    fs::write(&input, "grammar Host { token NUMBER = org.unlaxer.parser.elementary.NumberParser @root @mapping(Value, params=[text]) Start ::= NUMBER @text; }").unwrap();
    let output = scratch.0.join("project");
    let result = run(&input, &output, false);
    assert_eq!(result.status.code(), Some(3));
    assert!(String::from_utf8_lossy(&result.stderr).contains("declarative"));
    assert!(!output.exists());
}

#[cfg(unix)]
#[test]
fn symlink_ancestor_is_rejected() {
    let scratch = Scratch::new();
    let input = scratch.0.join("grammar.ubnf");
    fs::write(&input, GRAMMAR).unwrap();
    let real = scratch.0.join("real");
    fs::create_dir(&real).unwrap();
    let link = scratch.0.join("link");
    std::os::unix::fs::symlink(&real, &link).unwrap();
    assert_eq!(
        run(&input, &link.join("project"), false).status.code(),
        Some(4)
    );
    assert_eq!(fs::read_dir(real).unwrap().count(), 0);
}
