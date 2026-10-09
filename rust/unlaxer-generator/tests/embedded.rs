use std::fs;
use std::path::PathBuf;
use std::process::Command;
fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../docs/fixtures/embedded-grammars")
}
fn success(command: &mut Command) {
    let output = command.output().unwrap();
    assert!(
        output.status.success(),
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}
#[test]
fn generated_nested_grammars_execute_shared_source_position_corpus() {
    let output =
        std::env::temp_dir().join(format!("unlaxer-embedded-generated-{}", std::process::id()));
    fs::create_dir(&output).unwrap();
    struct Cleanup(PathBuf);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    let _cleanup = Cleanup(output.clone());
    for (name, module) in [
        ("FormulaInfo", "formula"),
        ("TinyExpression", "tiny"),
        ("Java", "java"),
    ] {
        let source = fs::read_to_string(fixtures().join(format!("{name}.ubnf"))).unwrap();
        let directory = output.join(module);
        fs::create_dir(&directory).unwrap();
        for file in unlaxer_generator::generate(&source).unwrap() {
            fs::write(directory.join(file.relative_path), file.content).unwrap();
        }
    }
    let runtime = output.join("libunlaxer_runtime.rlib");
    success(
        Command::new("rustc")
            .args([
                "--edition=2021",
                "--crate-type=rlib",
                "--crate-name=unlaxer_runtime",
            ])
            .arg(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../unlaxer-runtime/src/lib.rs"))
            .arg("-o")
            .arg(&runtime),
    );
    fs::copy(fixtures().join("probe.rs"), output.join("main.rs")).unwrap();
    success(
        Command::new("rustc")
            .args(["--edition=2021", "--extern"])
            .arg(format!("unlaxer_runtime={}", runtime.display()))
            .arg(output.join("main.rs"))
            .arg("-o")
            .arg(output.join("probe")),
    );
    success(
        Command::new(output.join("probe"))
            .arg(fixtures().join("cases.tsv"))
            .arg(fixtures().join("editor.tsv")),
    );
}
#[test]
fn malformed_declarations_share_rejection_corpus() {
    for line in fs::read_to_string(fixtures().join("invalid.tsv"))
        .unwrap()
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let (name, source) = line.split_once('\t').unwrap();
        assert!(
            unlaxer_generator::generate(source)
                .unwrap_err()
                .starts_with("E-EMBEDDING"),
            "{name}"
        );
    }
}
