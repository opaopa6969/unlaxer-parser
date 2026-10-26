//! frontend の全条件付き除外を、元 fixture を読む実行テストへ結ぶ (#784)。
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::process::Command;

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn manifest() -> Value {
    serde_json::from_str(
        &std::fs::read_to_string(root().join("spec-corpus/execution/manifest.json")).unwrap(),
    )
    .unwrap()
}

fn corpus() -> Vec<(String, Value)> {
    let mut rows = Vec::new();
    for path in std::fs::read_dir(root().join("unlaxer-dsl/src/test/resources/spec-corpus/ubnf"))
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|e| e == "json"))
    {
        let document: Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        for row in document["cases"].as_array().unwrap() {
            rows.push((
                path.file_name().unwrap().to_str().unwrap().to_owned(),
                row.clone(),
            ));
        }
    }
    rows
}

fn rows(scope: &str) -> Vec<(Value, Value)> {
    let data = manifest();
    let corpus = corpus();
    let declared = data["cases"].as_array().unwrap();
    let actual: BTreeSet<_> = corpus
        .iter()
        .filter(|(_, row)| matches!(row["scope"].as_str(), Some("validate" | "runtime")))
        .map(|(_, row)| row["id"].as_str().unwrap())
        .collect();
    let expected: BTreeSet<_> = declared
        .iter()
        .map(|row| row["id"].as_str().unwrap())
        .collect();
    assert_eq!(actual, expected, "新規除外 corpus は実行先の宣言が必要");
    assert_eq!(declared.len(), expected.len(), "実行先の重複");
    declared
        .iter()
        .filter(|row| row["scope"] == scope)
        .map(|row| {
            let (file, original) = corpus.iter().find(|(_, c)| c["id"] == row["id"]).unwrap();
            assert_eq!(&row["file"], file);
            assert_eq!(row["scope"], original["scope"]);
            assert_eq!(
                row["drivers"],
                json!([
                    format!("java-{scope}"),
                    format!("java-host-{scope}"),
                    format!("native-{scope}")
                ])
            );
            let driver = &data["drivers"][format!("native-{scope}")];
            assert_eq!(driver["test"], format!("{scope}_corpus_on_native"));
            assert_eq!(
                driver["path"],
                "rust/unlaxer-generator/tests/spec_corpus_execution.rs"
            );
            (row.clone(), original.clone())
        })
        .collect()
}

fn receipt(scope: &str, row: &Value, original: &Value, checks: usize) {
    println!(
        "SPEC-CORPUS {}",
        json!({"driver":format!("native-{scope}"), "id":row["id"], "input":original["input"], "checks":checks})
    );
}

#[test]
fn validate_corpus_on_native() {
    let mut executed = BTreeSet::new();
    let rows = rows("validate");
    assert!(!rows.is_empty());
    for (row, original) in &rows {
        let id = row["id"].as_str().unwrap();
        let source = original["input"].as_str().unwrap();
        let code = original["expectation"]["code"].as_str().unwrap();
        let file =
            unlaxer_ubnf::parse(source).unwrap_or_else(|e| panic!("{id}: 構文では受理する: {e}"));
        let mut checks = 0;
        for message in [
            unlaxer_generator::lowering::lower(&file.grammars[0]).unwrap_err(),
            unlaxer_generator::generate(source).unwrap_err(),
        ] {
            if let Some(difference) = row.get("difference") {
                assert!(difference["issue"].as_u64().unwrap() > 0);
                assert_eq!(
                    message,
                    difference["native"].as_str().unwrap(),
                    "{id}: 既知差が変化した"
                );
                assert!(!message.starts_with(code), "{id}: 解消した既知差を削除する");
            } else {
                assert_eq!(message.split(':').next().unwrap(), code, "{id}");
            }
            if let Some(cp) = row.get("cp") {
                assert!(
                    message.ends_with(&format!("at CP {}..{}", cp[0], cp[1])),
                    "{id}: {message}"
                );
            }
            checks += 1;
        }
        assert_eq!(checks, 2, "{id}: lowering と generate の両方が必要");
        assert!(executed.insert(id));
        receipt("validate", row, original, checks);
    }
    assert_eq!(executed.len(), rows.len());
}

struct Work(PathBuf);
impl Work {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "spec-corpus-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Work {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.0).unwrap();
    }
}
fn run(command: &mut Command) -> String {
    let output = command.output().unwrap();
    assert!(
        output.status.success(),
        "{command:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    String::from_utf8(output.stdout).unwrap()
}

#[test]
fn runtime_corpus_on_native() {
    let work = Work::new();
    let runtime = work.0.join("libunlaxer_runtime.rlib");
    run(Command::new("rustc")
        .args([
            "--edition=2021",
            "--crate-type=rlib",
            "--crate-name=unlaxer_runtime",
        ])
        .arg(root().join("rust/unlaxer-runtime/src/lib.rs"))
        .arg("-o")
        .arg(&runtime));
    let rows = rows("runtime");
    assert!(!rows.is_empty());
    let mut executed = BTreeSet::new();
    for (index, (row, original)) in rows.iter().enumerate() {
        let id = row["id"].as_str().unwrap();
        let mut source = original["input"].as_str().unwrap().to_owned();
        // Rust generation needs a root AST node. Only entry metadata is added; bodies stay exact.
        let needle = format!(" {} ::=", row["entry"].as_str().unwrap());
        assert_eq!(source.matches(&needle).count(), 1);
        let root_annotation = if row["add_root"] == true {
            " @root"
        } else {
            ""
        };
        source = source.replace(
            &needle,
            &format!("{root_annotation} @mapping(SpecResult){needle}"),
        );
        let directory = work.0.join(index.to_string());
        let generated = directory.join("generated");
        std::fs::create_dir_all(&generated).unwrap();
        for file in unlaxer_generator::generate(&source).unwrap_or_else(|e| panic!("{id}: {e}")) {
            std::fs::write(generated.join(file.relative_path), file.content).unwrap();
        }
        std::fs::copy(
            root().join("spec-corpus/execution/probe.rs"),
            directory.join("main.rs"),
        )
        .unwrap();
        let binary = directory.join("probe");
        run(Command::new("rustc")
            .args(["--edition=2021", "--extern"])
            .arg(format!("unlaxer_runtime={}", runtime.display()))
            .arg(directory.join("main.rs"))
            .arg("-o")
            .arg(&binary));
        let samples = row["samples"].as_array().unwrap();
        assert!(!samples.is_empty(), "{id}");
        let mut checks = 0;
        for sample in samples {
            let actual: Value = serde_json::from_str(&run(
                Command::new(&binary).arg(sample["input"].as_str().unwrap())
            ))
            .unwrap();
            assert_eq!(actual["prefix"], sample["prefix"], "{id}: {sample}");
            if let Some(hint) = sample.get("hint") {
                assert!(
                    actual["diagnostic"]["expected"]
                        .as_array()
                        .unwrap()
                        .contains(hint),
                    "{id}: {actual}"
                );
                assert_eq!(actual["diagnostic"]["offset"], sample["hint_cp"], "{id}");
            }
            checks += 1;
        }
        assert_eq!(checks, samples.len(), "{id}: 入力の実行漏れ");
        assert!(executed.insert(id));
        receipt("runtime", row, original, checks);
    }
    assert_eq!(executed.len(), rows.len());
}
