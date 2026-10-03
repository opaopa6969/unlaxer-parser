use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};
use unlaxer_generator::portability::check;

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/portability")
}

#[test]
fn shared_corpus_inventory_and_readiness_are_deterministic() {
    for line in include_str!("fixtures/portability/expected.tsv").lines() {
        let fields: Vec<_> = line.split('\t').collect();
        let source = fs::read_to_string(fixtures().join(fields[0])).unwrap();
        for source in [source.clone(), source.replace('\n', "\r\n")] {
            let report = check(&source);
            assert_eq!(fields[1], report.structure, "{}", fields[0]);
            assert_eq!(
                fields[2].parse::<usize>().unwrap(),
                report.diagnostics.len(),
                "{}",
                fields[0]
            );
            assert_eq!(report.portable, report.structure == "passed");
            assert_eq!(report, check(&source));
            for diagnostic in &report.diagnostics {
                if diagnostic.code == "P-SYNTAX" {
                    assert!(diagnostic.span.is_none());
                    continue;
                }
                let span = diagnostic.span.unwrap();
                assert!(span.codepoint_end > span.codepoint_start);
                assert_eq!(
                    source[span.byte_start..span.byte_end],
                    source
                        .chars()
                        .skip(span.codepoint_start)
                        .take(span.codepoint_end - span.codepoint_start)
                        .collect::<String>()
                );
            }
            if report.portable {
                assert_eq!(5, unlaxer_generator::generate(&source).unwrap().len());
            }
        }
    }
}

#[test]
fn inventory_reports_later_nested_and_equal_valued_occurrences() {
    let source = include_str!("fixtures/portability/multi-gap.ubnf");
    let report = check(source);
    assert_eq!(19, report.diagnostics.len());
    let docs: Vec<_> = report
        .diagnostics
        .iter()
        .filter(|d| d.subject == "doc")
        .collect();
    assert!(docs.is_empty(), "@doc is retained as portable metadata");
    for (code, expected) in [
        ("P-IMPORT", 1),
        ("P-SETTING", 2),
        ("P-WHITESPACE", 2),
        ("P-EXTERNAL-TOKEN", 1),
        ("P-TOKEN-KIND", 2),
        ("P-MAPPING-TYPE", 0),
        ("P-FIELD-NAME", 0),
        ("P-ANNOTATION", 5),
        ("P-INTERLEAVE", 1),
        ("P-SCOPE-MODE", 1),
        ("P-TYPEOF", 1),
        ("P-QUALIFIED-REFERENCE", 2),
        ("P-EMPTY-LITERAL", 1),
    ] {
        assert_eq!(
            expected,
            report.diagnostics.iter().filter(|d| d.code == code).count(),
            "{code}"
        );
    }
    let typeof_diag = report
        .diagnostics
        .iter()
        .find(|d| d.code == "P-TYPEOF")
        .unwrap();
    let span = typeof_diag.span.unwrap();
    assert_eq!("@typeof(a)", &source[span.byte_start..span.byte_end]);
    assert!(!report.to_json().contains("P-ERROR-ELEMENT"));
}

#[test]
fn generator_rejects_mixed_associativity_too() {
    let source = include_str!("fixtures/portability/mixed-associativity.ubnf");
    assert_eq!("failed", check(source).structure);
    assert!(unlaxer_generator::generate(source)
        .unwrap_err()
        .contains("mixes associativity"));
}

#[test]
fn long_rule_chains_return_a_report_instead_of_overflowing() {
    for length in [8, 64, 96, 260, 2500] {
        let mut source = String::from(
            "grammar Chain { @root @mapping(Root, params=[value]) Start ::= R0 @value;\n",
        );
        for index in 0..length {
            source.push_str(&format!("R{index} ::= R{};\n", index + 1));
        }
        source.push_str(&format!("R{length} ::= 'x';\n}}\n"));
        let report = check(&source);
        assert_eq!(length <= 64, report.portable);
        if !report.portable {
            assert_eq!("failed", report.structure);
            assert_eq!(1, report.diagnostics.len());
            assert_eq!("P-STRUCTURE", report.diagnostics[0].code);
        }
    }
}

#[test]
fn supported_nested_groups_are_not_confused_with_alias_depth() {
    for depth in [64, 96] {
        let source = format!(
            "grammar Nested {{ @root @mapping(Root, params=[value]) Start ::= {}'x'{} @value;\n}}\n",
            "(".repeat(depth), ")".repeat(depth),
        );
        let report = check(&source);
        assert!(report.portable, "{report:?}");
    }
}

static NEXT: AtomicUsize = AtomicUsize::new(0);
struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-check-{}-{}",
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

fn run(directory: &Directory, args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_unlaxer"))
        .args(args)
        .current_dir(&directory.0)
        .env("PATH", "")
        .env("JAVA_HOME", "/missing-java")
        .output()
        .unwrap()
}

#[test]
fn cli_is_read_only_without_java_and_has_stable_exit_codes() {
    let dir = Directory::new();
    fs::write(
        dir.0.join("good.ubnf"),
        include_str!("fixtures/portability/valid.ubnf"),
    )
    .unwrap();
    fs::write(
        dir.0.join("bad.ubnf"),
        include_str!("fixtures/portability/multi-gap.ubnf"),
    )
    .unwrap();
    fs::write(
        dir.0.join("missing.ubnf"),
        "this import must never be opened",
    )
    .unwrap();
    fs::write(dir.0.join("sentinel.rs"), "handwritten").unwrap();
    for (file, code) in [("good.ubnf", 0), ("bad.ubnf", 3)] {
        let output = run(
            &dir,
            &[
                "check",
                "--target",
                "rust",
                "--grammar",
                file,
                "--format",
                "json",
            ],
        );
        assert_eq!(Some(code), output.status.code());
        assert!(output.stderr.is_empty());
        let expected = check(&fs::read_to_string(dir.0.join(file)).unwrap()).to_json() + "\n";
        assert_eq!(expected.as_bytes(), output.stdout);
    }
    for args in [
        vec!["check"],
        vec!["check", "--target", "java", "--grammar", "good.ubnf"],
        vec!["check", "--target", "rust", "--grammar", ""],
        vec![
            "check",
            "--target",
            "rust",
            "--grammar",
            "good.ubnf",
            "--output",
            "generated",
        ],
        vec![
            "check",
            "--target",
            "rust",
            "--grammar",
            "good.ubnf",
            "--format",
            "text",
        ],
        vec![
            "check",
            "--target",
            "rust",
            "--grammar",
            "good.ubnf",
            "--format",
            "json",
            "--format",
            "json",
        ],
        vec![
            "check",
            "--target",
            "rust",
            "--target",
            "rust",
            "--grammar",
            "good.ubnf",
        ],
        vec![
            "check",
            "--target",
            "rust",
            "--grammar",
            "good.ubnf",
            "--help",
        ],
    ] {
        let output = run(&dir, &args);
        assert_eq!(Some(2), output.status.code(), "{args:?}");
        assert!(output.stdout.is_empty());
        assert!(!output.stderr.is_empty());
    }
    let output = run(
        &dir,
        &["check", "--target", "rust", "--grammar", "absent.ubnf"],
    );
    assert_eq!(Some(4), output.status.code());
    assert!(output.stdout.is_empty());
    assert_eq!(Some(0), run(&dir, &["check", "--help"]).status.code());
    assert_eq!(
        "handwritten",
        fs::read_to_string(dir.0.join("sentinel.rs")).unwrap()
    );
    assert_eq!(4, fs::read_dir(&dir.0).unwrap().count());
}
