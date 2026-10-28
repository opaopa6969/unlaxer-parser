use std::{
    collections::BTreeSet,
    fs,
    path::PathBuf,
    process::Command,
    sync::atomic::{AtomicUsize, Ordering},
};
use unlaxer_generator::adapters::{
    valid_id, valid_java_fqn, valid_rust_path, valid_version, AdapterBinding, AdapterRegistry,
    BuiltinAdapter,
};
use unlaxer_generator::{generate, lowering, portability::check};
use unlaxer_ubnf::{ast::TokenKind, parse_with_source};

static NEXT_TEMP: AtomicUsize = AtomicUsize::new(0);

#[test]
fn shared_diagnostic_oracle_covers_lowering_portability_and_authoring() {
    use serde_json::{json, Value};
    let cases: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/token-adapters/diagnostics.json"
    ))
    .unwrap();
    assert_eq!(46, cases.len());
    for case in cases {
        for ending in ["\n", "\r\n"] {
            let source = case["source"].as_str().unwrap().replace('\n', ending);
            let snapshot = parse_with_source(&source).unwrap();
            let grammar = &snapshot.ast().grammars[0];
            let (_, adapters) = AdapterRegistry::from_grammar(grammar);
            let contracts = unlaxer_generator::adapters::token_contract_diagnostics(grammar);
            let mut expected_messages = Vec::new();
            let mut expected_check = Vec::new();
            for (kind, actual) in [("adapters", adapters), ("contracts", contracts)] {
                let expected = case[kind].as_array().unwrap();
                assert_eq!(expected.len(), actual.len(), "{} {kind}", case["name"]);
                for (d, a) in expected.iter().zip(actual) {
                    assert_eq!(d["code"], a.code, "{}", case["name"]);
                    assert_eq!(d["subject"], a.subject, "{}", case["name"]);
                    let line = d["line"].as_u64().unwrap() as usize;
                    let offset: usize = source.split_inclusive('\n').take(line).map(str::len).sum();
                    let end = offset
                        + source[offset..]
                            .split('\n')
                            .next()
                            .unwrap()
                            .trim_end_matches('\r')
                            .len();
                    assert_eq!((offset, end), (a.span.byte_start, a.span.byte_end));
                    expected_messages.push((a.code.to_owned(), a.subject.clone()));
                    expected_check.push(json!({"code": d["code"], "severity": "error", "subject": d["subject"],
                        "span": {"start": source[..offset].chars().count(), "end": source[..end].chars().count()}}));
                }
            }
            let authoring: Vec<_> = unlaxer_generator::validation::collect(grammar)
                .into_iter()
                .map(|d| (d.code, d.message))
                .collect();
            let expected_authoring: Vec<_> = expected_messages
                .iter()
                .map(|(code, subject)| (code.clone(), format!("{code}: {subject}")))
                .collect();
            assert_eq!(expected_authoring, authoring, "{}", case["name"]);
            expected_check.sort_by_key(|d| d["span"]["start"].as_u64().unwrap());
            let expected = json!({"schemaVersion": 1, "target": "rust", "portable": expected_check.is_empty(),
                "structure": if expected_check.is_empty() { "passed" } else { "blocked" }, "diagnostics": expected_check});
            assert_eq!(
                expected,
                serde_json::from_str::<Value>(&check(&source).to_json()).unwrap(),
                "{}",
                case["name"]
            );
            if let Some((code, subject)) = expected_messages.first() {
                assert_eq!(
                    format!("{code}: {subject}"),
                    generate(&source).unwrap_err(),
                    "{}",
                    case["name"]
                );
            } else {
                assert_eq!(5, generate(&source).unwrap().len(), "{}", case["name"]);
            }
        }
    }
}

#[test]
fn adapter_references_precede_later_generator_diagnostics() {
    // Java-host validateOrThrow reports TokenAdapterRegistry (including references)
    // before contracts, features, comments, whitespace, mappings and references.
    let cases: Vec<serde_json::Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/token-adapters/generate-order.json"
    ))
    .unwrap();
    assert_eq!(5, cases.len());
    for case in cases {
        for ending in ["\n", "\r\n"] {
            let source = case["source"].as_str().unwrap().replace('\n', ending);
            assert_eq!(
                case["first"].as_str().unwrap(),
                generate(&source).unwrap_err(),
                "{}",
                case["name"]
            );
        }
    }
}

struct Temp(PathBuf);

impl Temp {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-token-adapter-{}-{}",
            std::process::id(),
            NEXT_TEMP.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}

impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn grammar(settings: &str, tokens: &str, body: &str) -> String {
    format!(
        "grammar G {{ {settings} {tokens} @root @mapping(R,params=[value]) Root ::= {body} @value; }}"
    )
}

#[test]
fn schema_boundaries_are_strict_and_shared_with_ir_validation() {
    assert!(valid_id("a.1-b2"));
    assert!(!valid_id("1.a"));
    assert!(!valid_id("a..b"));
    assert_eq!(Some(1), valid_version("0001"));
    assert_eq!(None, valid_version("0"));
    assert_eq!(None, valid_version("2147483648"));
    assert!(valid_java_fqn("example.WordParser"));
    assert!(!valid_java_fqn("example.record"));
    assert!(!valid_java_fqn("example.WordParser;evil"));
    assert!(valid_rust_path("crate::word_token"));
    assert!(!valid_rust_path("crate::_"));
    assert!(!valid_rust_path("_::word_token"));
    assert!(!valid_rust_path("crate::gen"));
    assert!(!valid_rust_path("crate::word_token;evil"));
    assert!(!unlaxer_codegen::valid_custom_token_path("crate::_"));
}

#[test]
fn standalone_underscore_path_is_a_definition_error_at_the_setting() {
    let source = grammar(
        "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::_' }",
        "token STRING = ADAPTER('tinyexpression.string', version=1)",
        "STRING",
    );
    let report = check(&source);
    assert_eq!("blocked", report.structure);
    assert_eq!(1, report.diagnostics.len());
    assert_eq!("P-ADAPTER-DEFINITION", report.diagnostics[0].code);
    let span = report.diagnostics[0].span.unwrap();
    assert_eq!(
        &source[span.byte_start..span.byte_end],
        "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::_' }"
    );
    assert!(generate(&source)
        .unwrap_err()
        .contains("P-ADAPTER-DEFINITION"));
}

#[test]
fn frontend_retains_neutral_id_raw_version_and_token_span() {
    let source = grammar(
        "",
        "token STRING = ADAPTER('tinyexpression.string', version=2147483648)",
        "STRING",
    );
    let snapshot = parse_with_source(&source).unwrap();
    let token = &snapshot.ast().grammars[0].tokens[0];
    assert!(matches!(&token.kind, TokenKind::Adapter { id, version }
        if id == "tinyexpression.string" && version == "2147483648"));
    assert_eq!(
        &source[token.span.byte_start..token.span.byte_end],
        "token STRING = ADAPTER('tinyexpression.string', version=2147483648)"
    );
    let report = check(&source);
    assert_eq!("blocked", report.structure);
    assert_eq!("P-ADAPTER-DEFINITION", report.diagnostics[0].code);
    assert_eq!(Some(token.span), report.diagnostics[0].span);
}

#[test]
fn builtins_resolve_to_the_existing_atomic_expressions() {
    for (id, expected) in [
        ("tinyexpression.string", "Expr::Choice(vec![Expr::Quoted"),
        ("tinyexpression.code-start", "Expr::CodeStart"),
        ("tinyexpression.code-end", "Expr::CodeEnd"),
        ("tinyexpression.long-code-block", "Expr::LongCodeBlock"),
    ] {
        let source = grammar("", &format!("token T = ADAPTER('{id}', version=1)"), "T");
        assert!(check(&source).portable, "{id}: {:?}", check(&source));
        let parser = generate(&source)
            .unwrap()
            .into_iter()
            .find(|file| file.relative_path == "parser.rs")
            .unwrap()
            .content;
        assert!(parser.contains(expected), "{id}: {parser}");
    }
    let source = grammar(
        "",
        "token T = ADAPTER('tinyexpression.string', version=1)",
        "T",
    );
    let snapshot = parse_with_source(&source).unwrap();
    let (registry, issues) = AdapterRegistry::from_grammar(&snapshot.ast().grammars[0]);
    assert!(issues.is_empty());
    assert_eq!(
        registry.resolve("tinyexpression.string", "1"),
        Ok(&AdapterBinding::Builtin(BuiltinAdapter::StringLiteral))
    );
}

#[test]
fn custom_registration_is_data_only_and_emits_a_validated_function_reference() {
    let settings = "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::word_token' }";
    let source = grammar(
        settings,
        "token W = ADAPTER('example.word', version=1)",
        "W",
    );
    let report = check(&source);
    assert!(report.portable, "{report:?}");
    let snapshot = parse_with_source(&source).unwrap();
    let (registry, issues) = AdapterRegistry::from_grammar(&snapshot.ast().grammars[0]);
    assert!(issues.is_empty());
    assert!(matches!(
        registry.resolve("example.word", "1"),
        Ok(AdapterBinding::Custom(_))
    ));
    let ir = lowering::lower(&snapshot.ast().grammars[0]).unwrap();
    assert!(format!("{:?}", ir.rules[0].body).contains("CustomToken(\"crate::word_token\")"));
    let parser = generate(&source)
        .unwrap()
        .into_iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(
        parser.contains("Expr::Custom(crate::word_token)"),
        "{parser}"
    );
}

#[test]
fn format2_contract_is_retained_and_rejects_nonportable_context_accessors() {
    let source = grammar(
        "@ubnf: v2 @tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::word_token' accepts: 'ASCII word' failure: 'no-consume' context: 'remaining,position' }",
        "token W = ADAPTER('example.word', version=1)",
        "W",
    );
    let snapshot = parse_with_source(&source).unwrap();
    let (registry, issues) = AdapterRegistry::from_grammar(&snapshot.ast().grammars[0]);
    assert!(issues.is_empty(), "{issues:?}");
    let Ok(AdapterBinding::Custom(adapter)) = registry.resolve("example.word", "1") else {
        panic!("expected custom adapter");
    };
    assert_eq!(adapter.accepts.as_deref(), Some("ASCII word"));
    assert_eq!(adapter.failure.as_deref(), Some("no-consume"));
    assert_eq!(
        adapter.context_accessors,
        BTreeSet::from(["position".into(), "remaining".into()])
    );
    assert!(check(&source).portable, "{:?}", check(&source));

    let invalid = source.replace("remaining,position", "captures");
    let report = check(&invalid);
    assert_eq!("blocked", report.structure);
    assert_eq!("P-ADAPTER-DEFINITION", report.diagnostics[0].code);
}

#[test]
fn invalid_and_duplicate_declarations_and_references_keep_source_spans() {
    let source = grammar(
        "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::word_token' } \
         @tokenAdapter: { id: 'example.word' version: '1' java: 'example.OtherParser' rust: 'crate::other' } \
         @tokenAdapter: { id: 'bad id' version: '1' java: 'example.Bad' rust: 'crate::bad' } \
         @tokenAdapter: { id: 'example.unsafe' version: '1' java: 'example.Unsafe' rust: 'crate::safe;evil' }",
        "token U = ADAPTER('unknown.word', version=1) \
         token V = ADAPTER('example.word', version=2) \
         token Z = ADAPTER('example.word', version=0)",
        "U");
    let report = check(&source);
    assert_eq!("blocked", report.structure);
    let codes: Vec<_> = report.diagnostics.iter().map(|d| d.code).collect();
    assert_eq!(
        vec![
            "P-ADAPTER-DUPLICATE",
            "P-ADAPTER-DEFINITION",
            "P-ADAPTER-DEFINITION",
            "P-ADAPTER-UNKNOWN",
            "P-ADAPTER-VERSION",
            "P-ADAPTER-DEFINITION"
        ],
        codes
    );
    for diagnostic in report.diagnostics {
        let span = diagnostic.span.unwrap();
        let text = &source[span.byte_start..span.byte_end];
        assert!(
            text.starts_with("@tokenAdapter:") || text.starts_with("token "),
            "{text}"
        );
    }
}

#[test]
fn custom_tokens_are_conservatively_nullable_and_not_memo_safe() {
    let settings = "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::word_token' }";
    let tokens = "token W = ADAPTER('example.word', version=1)";
    let repeat = grammar(settings, tokens, "W*");
    assert!(check(&repeat).portable);
    assert!(generate(&repeat).is_ok());
    // Unknown/custom progress still cannot justify a left-recursive leading edge.
    let cycle = grammar(settings, tokens, "W Root");
    assert!(generate(&cycle).unwrap_err().contains("left recursion"));

    let custom_memo = grammar(&format!("{settings} @memoSafeToken: W"), tokens, "W");
    assert_eq!("failed", check(&custom_memo).structure);
    assert!(generate(&custom_memo)
        .unwrap_err()
        .contains("builtin Adapter"));

    let builtin_memo = grammar(
        "@memoSafeToken: W",
        "token W = ADAPTER('tinyexpression.string', version=1)",
        "W",
    );
    assert!(check(&builtin_memo).portable);
}

#[test]
fn custom_function_binding_compiles_and_runs_only_in_the_generated_host() {
    let temp = Temp::new();
    let source = grammar(
        "@tokenAdapter: { id: 'example.word' version: '1' java: 'example.WordParser' rust: 'crate::word_token' }",
        "token W = ADAPTER('example.word', version=1)", "W");
    let generated = temp.0.join("generated");
    fs::create_dir(&generated).unwrap();
    for file in generate(&source).unwrap() {
        fs::write(generated.join(file.relative_path), file.content).unwrap();
    }
    let runtime_source =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../unlaxer-runtime/src/lib.rs");
    let runtime_lib = temp.0.join("libunlaxer_runtime.rlib");
    let output = Command::new("rustc")
        .args([
            "--edition=2021",
            "--crate-name=unlaxer_runtime",
            "--crate-type=rlib",
        ])
        .arg(runtime_source)
        .arg("-o")
        .arg(&runtime_lib)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    fs::write(
        temp.0.join("main.rs"),
        r#"
mod generated;
fn word_token(context: &mut unlaxer_runtime::ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    context.parse(&unlaxer_runtime::Expr::Literal("word").recover(
        unlaxer_runtime::RecoveryMode::Sync,
        [";"],
        "provider recovery",
    ))
}
fn main() {
    let normal = generated::parser::parse_tree("word").unwrap();
    assert!(normal.recoveries().is_empty());
    assert!(generated::mapper::map(&normal).is_ok());
    let recovered = generated::parser::parse_tree("other;").unwrap();
    assert_eq!(recovered.recoveries().len(), 1);
    assert_eq!(recovered.recoveries()[0].message, "provider recovery");
    assert_eq!(generated::mapper::map(&recovered).unwrap_err(), "cannot map recovered syntax");
    assert!(generated::parser::parse_tree("other").is_err());
}
"#,
    )
    .unwrap();
    let output = Command::new("rustc")
        .args(["--edition=2021", "-A", "warnings"])
        .arg(temp.0.join("main.rs"))
        .arg("--extern")
        .arg(format!("unlaxer_runtime={}", runtime_lib.display()))
        .arg("-o")
        .arg(temp.0.join("run"))
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let output = Command::new(temp.0.join("run")).output().unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
}
