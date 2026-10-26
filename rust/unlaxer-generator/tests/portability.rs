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
    // `@comment: { line: '//' }` (#712), `''` (#713) and `@custom` (#715, W-ANNOTATION-IGNORED)
    // are portable.
    assert_eq!(9, report.diagnostics.len());
    let docs: Vec<_> = report
        .diagnostics
        .iter()
        .filter(|d| d.subject == "doc")
        .collect();
    assert!(docs.is_empty(), "@doc is retained as portable metadata");
    for (code, expected) in [
        ("P-IMPORT", 1),
        ("P-SETTING", 1),
        ("P-WHITESPACE", 2),
        ("P-EXTERNAL-TOKEN", 1),
        ("P-TOKEN-KIND", 0),
        ("P-MAPPING-TYPE", 0),
        ("P-FIELD-NAME", 0),
        ("P-ANNOTATION", 0),
        ("P-INTERLEAVE", 1),
        ("P-SCOPE-MODE", 1),
        ("P-TYPEOF", 0),
        ("P-QUALIFIED-REFERENCE", 2),
        ("P-EMPTY-LITERAL", 0),
    ] {
        assert_eq!(
            expected,
            report.diagnostics.iter().filter(|d| d.code == code).count(),
            "{code}"
        );
    }
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

#[test]
fn imported_diagnostics_retain_only_root_positions_and_distinct_occurrences() {
    let cases: serde_json::Value =
        serde_json::from_str(include_str!("fixtures/portability/imports.json")).unwrap();
    for case in cases.as_array().unwrap() {
        for ending in ["LF", "CRLF"] {
            let directory = Directory::new();
            let files = case["files"].as_object().unwrap();
            for (name, source) in files {
                let path = directory.0.join(name);
                fs::create_dir_all(path.parent().unwrap()).unwrap();
                let source = source.as_str().unwrap();
                fs::write(
                    path,
                    if ending == "LF" {
                        source.into()
                    } else {
                        source.replace('\n', "\r\n")
                    },
                )
                .unwrap();
            }
            let path = directory.0.join("main.ubnf");
            let report = unlaxer_generator::portability::check_file(&path);
            let actual: serde_json::Value = serde_json::from_str(&report.to_json()).unwrap();
            assert_eq!(
                case["expected"][ending], actual,
                "{} / {ending}",
                case["name"]
            );
            assert_eq!(report, unlaxer_generator::portability::check_file(&path));
            let output = run(
                &directory,
                &["check", "--target", "rust", "--grammar", "main.ubnf"],
            );
            assert_eq!(
                Some(if report.portable { 0 } else { 3 }),
                output.status.code()
            );
            assert!(output.stderr.is_empty());
            assert_eq!(
                actual,
                serde_json::from_slice::<serde_json::Value>(&output.stdout).unwrap()
            );
            let source = fs::read_to_string(path).unwrap();
            for diagnostic in &report.diagnostics {
                if let Some(span) = diagnostic.span {
                    assert_eq!(
                        source[span.byte_start..span.byte_end],
                        source
                            .chars()
                            .skip(span.codepoint_start)
                            .take(span.codepoint_end - span.codepoint_start)
                            .collect::<String>()
                    );
                }
            }
            for (name, source) in files {
                let source = source.as_str().unwrap();
                assert_eq!(
                    if ending == "LF" {
                        source.into()
                    } else {
                        source.replace('\n', "\r\n")
                    },
                    fs::read_to_string(directory.0.join(name)).unwrap()
                );
            }
        }
    }
}

#[test]
fn structural_diagnostic_priority_matches_shared_oracle() {
    let cases: serde_json::Value =
        serde_json::from_str(include_str!("fixtures/portability/structure-priority.json")).unwrap();
    for case in cases.as_array().unwrap() {
        let source = if let Some(path) = case["sourceFile"].as_str() {
            fs::read_to_string(
                Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join("../..")
                    .join(path),
            )
            .unwrap()
        } else {
            case["source"].as_str().unwrap().into()
        };
        if case["name"] == "ir-error-only" {
            let file = unlaxer_ubnf::parse(&source).unwrap();
            let ir = unlaxer_generator::lowering::lower(&file.grammars[0])
                .expect("this fixture must reach IR validation");
            assert!(unlaxer_codegen::validate_ir(&ir)
                .unwrap_err()
                .to_string()
                .contains("duplicate or empty recovery sync token"));
        }
        for ending in ["LF", "CRLF"] {
            let directory = Directory::new();
            let source = if ending == "LF" {
                source.clone()
            } else {
                source.replace('\n', "\r\n")
            };
            let path = directory.0.join("main.ubnf");
            fs::write(&path, &source).unwrap();
            let expected = &case["expected"][ending];
            let report = unlaxer_generator::portability::check_file(&path);
            assert_eq!(
                expected,
                &serde_json::from_str::<serde_json::Value>(&report.to_json()).unwrap(),
                "{} / {ending}",
                case["name"]
            );
            for _ in 0..2 {
                let output = run(
                    &directory,
                    &[
                        "check",
                        "--target",
                        "rust",
                        "--grammar",
                        "main.ubnf",
                        "--format",
                        "json",
                    ],
                );
                assert_eq!(
                    Some(if report.portable { 0 } else { 3 }),
                    output.status.code(),
                    "{} / {ending}",
                    case["name"]
                );
                assert!(output.stderr.is_empty());
                assert_eq!(
                    expected,
                    &serde_json::from_slice::<serde_json::Value>(&output.stdout).unwrap()
                );
            }
            assert_eq!(source, fs::read_to_string(path).unwrap());
            assert_eq!(1, fs::read_dir(&directory.0).unwrap().count());
        }
    }
}

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
