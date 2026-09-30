use std::{
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
    assert!(!check(&repeat).portable);
    assert!(generate(&repeat)
        .unwrap_err()
        .contains("nullable unbounded repetition"));

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
    context.parse(&unlaxer_runtime::Expr::Literal("word"))
}
fn main() {
    assert!(generated::parser::parse_tree("word").is_ok());
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
