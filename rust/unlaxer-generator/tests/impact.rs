use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};
use unlaxer_generator::impact::{compare, Location};

const BASE: &str = "grammar G { @root @mapping(Value,params=[value]) Root ::= 'x' @value; }";
static NEXT: AtomicUsize = AtomicUsize::new(0);

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "unlaxer-impact-{}-{}",
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

fn generated(source: &str, path: &str) -> String {
    unlaxer_generator::generate(source)
        .unwrap()
        .into_iter()
        .find(|file| file.relative_path == path)
        .unwrap()
        .content
}

fn slice<'a>(source: &'a str, location: &Location) -> &'a str {
    let start = source
        .char_indices()
        .nth(location.span.start)
        .map_or(source.len(), |(byte, _)| byte);
    let end = source
        .char_indices()
        .nth(location.span.end)
        .map_or(source.len(), |(byte, _)| byte);
    &source[start..end]
}

#[test]
fn identity_schema_uses_actual_generated_declarations_and_source_rules() {
    let report = compare(BASE, BASE);
    assert!(report.ok);
    assert!(!report.has_changes);
    assert!(report.changes.is_empty());
    let snapshot = report.before.as_ref().unwrap();
    assert_eq!("G", snapshot.grammar);
    assert_eq!("Ast", snapshot.ast_type);
    assert_eq!("Semantics", snapshot.evaluator_type);
    let node = &snapshot.nodes[0];
    assert_eq!("Value", node.name);
    assert_eq!("variant", node.kind);
    assert!(node.parents.is_empty() && node.variants.is_empty());
    assert_eq!("Root", node.origins[0].rule);
    let origin = &node.origins[0].span;
    assert_eq!(
        "@root @mapping(Value,params=[value]) Root ::= 'x' @value;",
        BASE.chars()
            .skip(origin.start)
            .take(origin.end - origin.start)
            .collect::<String>()
    );
    let ast = generated(BASE, "ast.rs");
    assert_eq!(
        "    r#Value { span: Span, r#value: String },",
        slice(&ast, &node.generated)
    );
    assert_eq!("r#value: String", slice(&ast, &node.fields[0].generated));
    assert_eq!("String", node.fields[0].r#type);
    assert_eq!("one", node.fields[0].cardinality);
    let method = &snapshot.methods[0];
    assert_eq!("eval_value", method.name);
    assert_eq!("Self::Output", method.return_type);
    assert!(method.required);
    assert_eq!(
        vec![("self", "&mut Self"), ("value", "&str"), ("span", "Span")],
        method
            .parameters
            .iter()
            .map(|param| (param.name.as_str(), param.r#type.as_str()))
            .collect::<Vec<_>>()
    );
    let evaluator = generated(BASE, "evaluator.rs");
    assert_eq!(
        "    fn eval_value(&mut self, r#value: &str, span: Span) -> Self::Output;",
        slice(&evaluator, &method.generated)
    );
    assert_eq!(1, node.generated.column);
    assert_eq!(1, method.generated.column);
    assert_eq!(
        ast[..ast.find("    r#Value {").unwrap()].lines().count() + 1,
        node.generated.line
    );
    assert!(report.to_json().contains("\"scope\":\"ast-semantics\""));
}

#[test]
fn optional_field_reports_type_cardinality_and_method_signature_not_position_noise() {
    let after =
        "grammar G { // 😀\r\n @root @mapping(Value,params=[value]) Root ::= [ 'x' ] @value; }";
    let report = compare(BASE, after);
    assert!(report.ok && report.has_changes, "{report:?}");
    let kinds = report
        .changes
        .iter()
        .map(|change| change.kind)
        .collect::<Vec<_>>();
    assert_eq!(
        vec![
            "FIELD_CARDINALITY_CHANGED",
            "FIELD_TYPE_CHANGED",
            "METHOD_SIGNATURE_CHANGED"
        ],
        kinds
    );
    let type_change = report
        .changes
        .iter()
        .find(|change| change.kind == "FIELD_TYPE_CHANGED")
        .unwrap();
    assert_eq!("Value.value", type_change.subject);
    assert_eq!(Some("String"), type_change.before.as_deref());
    assert_eq!(Some("Option<String>"), type_change.after.as_deref());
    assert_eq!(
        "r#value: String",
        slice(
            &generated(BASE, "ast.rs"),
            type_change.before_generated.as_ref().unwrap()
        )
    );
    assert_eq!(
        "r#value: Option<String>",
        slice(
            &generated(after, "ast.rs"),
            type_change.after_generated.as_ref().unwrap()
        )
    );
    let method = report
        .changes
        .iter()
        .find(|change| change.kind == "METHOD_SIGNATURE_CHANGED")
        .unwrap();
    assert_eq!(
        Some("(self:&mut Self,value:&str,span:Span)->Self::Output"),
        method.before.as_deref()
    );
    assert_eq!(
        Some("(self:&mut Self,value:Option<&str>,span:Span)->Self::Output"),
        method.after.as_deref()
    );
    let same_api_with_comment =
        "grammar G { // 😀\r\n @root @mapping(Value,params=[value]) Root ::= 'x' @value; }";
    assert!(!compare(BASE, same_api_with_comment).has_changes);
}

#[test]
fn new_node_adds_only_node_and_method_and_rule_name_changes_are_reported() {
    let added = "grammar G { @root @mapping(Value,params=[value]) Root ::= 'x' @value; @mapping(Extra,params=[x]) Extra ::= 'y' @x; }";
    let report = compare(BASE, added);
    assert_eq!(
        vec![("METHOD_ADDED", "eval_extra"), ("NODE_ADDED", "Extra")],
        report
            .changes
            .iter()
            .map(|change| (change.kind, change.subject.as_str()))
            .collect::<Vec<_>>()
    );
    assert!(report
        .changes
        .iter()
        .all(|change| change.before_generated.is_none() && change.after_generated.is_some()));
    let renamed = "grammar G { @root @mapping(Value,params=[value]) Entry ::= 'x' @value; }";
    let report = compare(BASE, renamed);
    assert_eq!(
        vec!["NODE_RULES_CHANGED"],
        report
            .changes
            .iter()
            .map(|change| change.kind)
            .collect::<Vec<_>>()
    );
    assert_eq!(Some("Root"), report.changes[0].before.as_deref());
    assert_eq!(Some("Entry"), report.changes[0].after.as_deref());
}

#[test]
fn failed_side_preserves_other_snapshot_and_suppresses_changes() {
    let report = compare("grammar Bad {", BASE);
    assert!(!report.ok && !report.has_changes);
    assert!(report.before.is_none() && report.after.is_some());
    assert!(report.changes.is_empty());
    assert_eq!("before", report.diagnostics[0].side);
    assert_eq!("I-SCHEMA", report.diagnostics[0].code);
    let report = compare(BASE, "grammar Bad {");
    assert!(report.before.is_some() && report.after.is_none());
    assert_eq!("after", report.diagnostics[0].side);
}

#[test]
fn cli_is_read_only_and_respects_exit_codes_without_path_or_compiler() {
    let temp = Directory::new();
    let before = temp.0.join("before.ubnf");
    let after = temp.0.join("after.ubnf");
    fs::write(&before, BASE).unwrap();
    fs::write(&after, BASE).unwrap();
    let invoke = |target: &str| {
        Command::new(env!("CARGO_BIN_EXE_unlaxer"))
            .args(["impact", "--target", target, "--before"])
            .arg(&before)
            .arg("--after")
            .arg(&after)
            .args(["--format", "json"])
            .env("PATH", "")
            .env("JAVA_HOME", "/nonexistent-unlaxer-java")
            .output()
            .unwrap()
    };
    let output = invoke("rust");
    assert_eq!(Some(0), output.status.code());
    assert!(output.stderr.is_empty());
    let text = String::from_utf8(output.stdout).unwrap();
    assert_eq!(1, text.lines().count());
    assert!(text.contains("\"ok\":true") && text.contains("\"hasChanges\":false"));
    assert_eq!(Some(2), invoke("java").status.code());
    fs::write(&after, "grammar Bad {").unwrap();
    let output = invoke("rust");
    assert_eq!(Some(3), output.status.code());
    assert!(String::from_utf8(output.stdout)
        .unwrap()
        .contains("\"side\":\"after\""));
    fs::remove_file(&after).unwrap();
    assert_eq!(Some(4), invoke("rust").status.code());
    let entries = fs::read_dir(&temp.0).unwrap().count();
    assert_eq!(1, entries);
}
