use std::collections::HashSet;
use std::fmt::Write;
use unlaxer_codegen::ir::GrammarIr;
use unlaxer_ubnf::ast::{AnnotationKind, GrammarDecl};
const TEMPLATE: &str =
    include_str!("../../../unlaxer-dsl/src/main/resources/rust/preferred-snapshot.rs.txt");

pub(crate) fn emit(
    grammar: &GrammarDecl,
    ir: &GrammarIr,
    parser: &str,
    ast: &str,
) -> (String, String) {
    let calls = crate::preferred_plan::calls(grammar);
    let mut identity = String::new();
    for (i, rule) in grammar.rules.iter().enumerate() {
        let mapping = rule
            .annotations
            .iter()
            .find_map(|a| match &a.kind {
                AnnotationKind::Mapping { class_name, .. } => Some(class_name.as_str()),
                _ => None,
            })
            .unwrap_or("");
        let recovery = rule
            .annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::Recovery { .. }));
        writeln!(
            identity,
            "\nrule:{i}:{}:{recovery}:{mapping}",
            ir.rules[i].skip
        )
        .unwrap();
        for (target, depth) in &calls[i] {
            write!(identity, "{target}:{depth},").unwrap();
        }
        identity.push('\n');
        for constraint in &ir.rules[i].type_constraints {
            writeln!(
                identity,
                "typeof:{}:{}",
                constraint.capture.name, constraint.reference.name
            )
            .unwrap();
        }
    }
    let mut hash = 0xcbf29ce484222325u64;
    for byte in parser
        .split("\n/// Ordered rule documentation;")
        .next()
        .unwrap()
        .bytes()
        .chain(ast.bytes())
        .chain(identity.bytes())
    {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    let marker = format!("#preferred-schema:{hash:x}");
    let instrumented = if grammar.rules.iter().flat_map(|r| &r.annotations).any(|a| {
        matches!(
            a.kind,
            AnnotationKind::LeftAssoc | AnnotationKind::RightAssoc
        )
    }) || !crate::common_fields::enabled(grammar)
        && grammar
            .rules
            .iter()
            .any(|rule| crate::common_fields::is_sum(grammar, rule))
    {
        Err(unlaxer_codegen::GenerateError(
            "sum family pending #569".into(),
        ))
    } else {
        unlaxer_codegen::preferred::parser(ir, &calls, &marker)
    };
    let mut parser =
        include_str!("../../../unlaxer-dsl/src/main/resources/rust/preferred-parser.rs.txt")
            .replace("$ROOT", &ir.root.to_string())
            .replace("$WHITESPACE", &ir.java_whitespace.to_string());
    match instrumented {
        Ok(source) => write!(parser, "\n/// Grammar with private candidate metadata, suitable for an explicit alternate entry.\npub fn mapping_grammar() -> Option<&'static SharedGrammar> {{ Some(__preferred_cst::grammar()) }}\n#[allow(dead_code)]\nmod __preferred_cst {{\n{source}}}\n").unwrap(),
        Err(_) => parser.push_str("\n/// None when this specialized lowering is not supported by preferred snapshots.\npub fn mapping_grammar() -> Option<&'static SharedGrammar> { None }\n"),
    }
    let mut out=format!("\nconst __PREFERRED_ROOT: usize = {};\nconst __PREFERRED_RULE_COUNT: usize = {};\nconst __PREFERRED_MARKER: &str = {marker:?};\n",ir.root,ir.rules.len());
    writeln!(
        out,
        "const __PREFERRED_STRICT_ROOT: bool = {};",
        grammar.rules[ir.root]
            .annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::Mapping { .. }))
    )
    .unwrap();
    let recovered: Vec<_> = grammar
        .rules
        .iter()
        .enumerate()
        .filter(|(_, r)| {
            r.annotations
                .iter()
                .any(|a| matches!(a.kind, AnnotationKind::Recovery { .. }))
        })
        .map(|(i, _)| i.to_string())
        .collect();
    out.push_str(&predicate("__preferred_recovery_wrapper", &recovered));
    let eligible: Vec<_> = ir
        .rules
        .iter()
        .enumerate()
        .filter(|(i, r)| {
            !r.skip
                && (r.mapping.is_some()
                    || grammar.rules[*i]
                        .annotations
                        .iter()
                        .any(|a| matches!(a.kind, AnnotationKind::Mapping { .. })))
        })
        .map(|(i, _)| i.to_string())
        .collect();
    let skipped: Vec<_> = ir
        .rules
        .iter()
        .enumerate()
        .filter(|(_, r)| r.skip)
        .map(|(i, _)| i.to_string())
        .collect();
    out.push_str(&predicate("__preferred_candidate", &eligible));
    out.push_str(&predicate("__preferred_skipped", &skipped));
    out.push_str("fn __preferred_name(ast: &Ast) -> &'static str { match *ast {\n");
    let mut seen = HashSet::new();
    for mapping in ir.rules.iter().filter_map(|r| r.mapping.as_ref()) {
        if !seen.insert(&mapping.name) {
            continue;
        }
        let name = grammar
            .rules
            .iter()
            .flat_map(|r| &r.annotations)
            .filter_map(|a| match &a.kind {
                AnnotationKind::Mapping { class_name, .. } => Some(class_name),
                _ => None,
            })
            .find(|name| name.replace('.', "__") == mapping.name)
            .unwrap_or(&mapping.name);
        let simple = name.rsplit('.').next().unwrap();
        writeln!(out, "    Ast::r#{} {{ .. }} => {simple:?},", mapping.name).unwrap();
    }
    out.push_str("} }\n");
    out.push_str(TEMPLATE);
    (parser, out)
}
fn predicate(name: &str, ids: &[String]) -> String {
    let body = if ids.is_empty() {
        "let _ = rule; false".into()
    } else {
        format!("matches!(rule, {})", ids.join(" | "))
    };
    format!("fn {name}(rule: usize) -> bool {{ {body} }}\n")
}
