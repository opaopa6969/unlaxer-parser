//! Typed defaults for @eval. Handwritten semantics remain mandatory for unknown/manual kinds.
use std::collections::BTreeMap;
use std::fmt::Write;
use unlaxer_codegen::ir::{Cardinality, Field, GrammarIr, Kind, Mapping};
use unlaxer_ubnf::{AnnotationKind, GrammarDecl};

#[derive(Clone, Debug, PartialEq, Eq)]
struct Spec {
    kind: String,
    strategy: String,
    params: BTreeMap<String, String>,
}
fn automatic(spec: &Spec) -> bool {
    spec.strategy != "manual"
        && matches!(
            spec.kind.as_str(),
            "literal" | "passthrough" | "conditional" | "binary_arithmetic" | "variable_ref"
        )
}
fn specifications(grammar: &GrammarDecl) -> Result<BTreeMap<String, Spec>, String> {
    let mut specs = BTreeMap::new();
    for rule in &grammar.rules {
        let values: Vec<_> = rule
            .annotations
            .iter()
            .filter_map(|a| {
                if let AnnotationKind::Eval {
                    kind,
                    strategy,
                    params,
                } = &a.kind
                {
                    Some(Spec {
                        kind: kind.clone(),
                        strategy: strategy.clone(),
                        params: params
                            .iter()
                            .map(|p| (p.key.clone(), p.value.clone()))
                            .collect(),
                    })
                } else {
                    None
                }
            })
            .collect();
        if values.is_empty() {
            continue;
        }
        if values.len() != 1 {
            return Err(format!("E-EVAL: duplicate annotation on {}", rule.name));
        }
        let spec = &values[0];
        let (name, params) = rule
            .annotations
            .iter()
            .find_map(|a| {
                if let AnnotationKind::Mapping { class_name, params } = &a.kind {
                    Some((class_name, params))
                } else {
                    None
                }
            })
            .ok_or_else(|| format!("E-EVAL: requires @mapping on {}", rule.name))?;
        if rule
            .annotations
            .iter()
            .any(|a| matches!(a.kind, AnnotationKind::Skip | AnnotationKind::Enum))
        {
            return Err(format!(
                "E-EVAL: requires an emitted concrete mapping on {}",
                rule.name
            ));
        }
        let name = name.replace('.', "__");
        if let Some(previous) = specs.insert(name.clone(), spec.clone()) {
            if previous != *spec {
                return Err(format!(
                    "E-EVAL: conflicting annotations on shared mapping {name} on {}",
                    rule.name
                ));
            }
        }
        if !automatic(spec) {
            continue;
        }
        let fields: &[&str] = match spec.kind.as_str() {
            "literal" | "passthrough" => &["value"],
            "conditional" => &["condition", "thenExpr", "elseExpr"],
            "binary_arithmetic" => &["left", "op", "right"],
            "variable_ref" => &["name"],
            _ => unreachable!(),
        };
        if fields.iter().any(|name| !params.iter().any(|p| p == name)) {
            return Err(format!(
                "E-EVAL: missing fields [{}] on {}",
                fields.join(", "),
                rule.name
            ));
        }
    }
    Ok(specs)
}
pub(crate) fn validate(grammar: &GrammarDecl) -> Result<(), String> {
    specifications(grammar).map(|_| ())
}
fn quote(value: &str) -> String {
    let mut out = String::from("\"");
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c < ' ' || c == '\u{7f}' => write!(out, "\\u{{{:x}}}", u32::from(c)).unwrap(),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn field<'a>(mapping: &'a Mapping, name: &str) -> Result<&'a Field, String> {
    mapping
        .fields
        .iter()
        .find(|f| f.name == name)
        .ok_or_else(|| format!("E-EVAL: missing field {name} on {}", mapping.name))
}
fn require(
    mapping: &Mapping,
    name: &str,
    kind: Kind,
    cardinality: Cardinality,
) -> Result<(), String> {
    let f = field(mapping, name)?;
    if f.kind != kind || f.cardinality != cardinality {
        Err(format!("E-EVAL: invalid field {name} on {}", mapping.name))
    } else {
        Ok(())
    }
}
fn scalar(field: &Field, expression: &str) -> String {
    match &field.kind {
        Kind::Text => format!("EvalValue::Text({expression})"),
        Kind::Integer => format!("EvalValue::Integer(*{expression})"),
        Kind::Node => format!("EvalValue::Node({expression})"),
        Kind::NullableNode => unreachable!("nullable node only permitted as associative left"),
        Kind::Enum(name) => format!(
            "EvalValue::Enum {{ name: {}, text: {expression}.text() }}",
            quote(name)
        ),
        Kind::Value => format!("eval_value({expression})"),
    }
}
fn value(field: &Field, expression: &str) -> String {
    match field.cardinality {
        Cardinality::One => scalar(field, expression),
        Cardinality::Optional => format!(
            "EvalValue::Optional({expression}.map(|value| Box::new({})))",
            scalar(field, "value")
        ),
        Cardinality::Many => format!(
            "EvalValue::Many({expression}.iter().map(|value| {}).collect())",
            scalar(field, "value")
        ),
    }
}
fn operand(ir: &GrammarIr, field: &Field, expression: &str) -> String {
    match &field.kind {
        Kind::Node | Kind::NullableNode => format!("evaluate({expression}, self)"),
        Kind::Text => format!("self.evaluate_literal({expression})"),
        Kind::Integer => format!("self.evaluate_literal(&{expression}.to_string())"),
        Kind::Enum(_) => format!("self.evaluate_literal(&format!(\"{{:?}}\", {expression}))"),
        Kind::Value => {
            let mut arms=format!("match {expression} {{ AstValue::Text {{ text, .. }} => self.evaluate_literal(text), AstValue::Node(node) => evaluate(node, self),");
            for rule in &ir.rules {
                if !rule.enum_literals.is_empty() {
                    arms+=&format!(" AstValue::Enum{} {{ value, .. }} => self.evaluate_literal(&format!(\"{{:?}}\", value)),",rule.name);
                }
            }
            arms + " }"
        }
    }
}
pub(crate) fn apply(
    grammar: &GrammarDecl,
    ir: &GrammarIr,
    mut source: String,
) -> Result<String, String> {
    let specs = specifications(grammar)?;
    let (mut values, mut truth, mut binary, mut variable, mut auto) =
        (false, false, false, false, false);
    let mut mappings = vec![];
    for rule in &ir.rules {
        if let Some(mapping) = &rule.mapping {
            if !mappings.contains(&mapping) {
                mappings.push(mapping);
            }
        }
    }
    for mapping in &mappings {
        let Some(spec) = specs.get(&mapping.name) else {
            continue;
        };
        if !automatic(spec) {
            continue;
        }
        auto = true;
        let body = match spec.kind.as_str() {
            "literal" | "passthrough" => {
                values = true;
                let field = field(mapping, "value")?;
                let value = value(field, "r#value");
                if spec.kind == "passthrough" && field.cardinality == Cardinality::One {
                    format!("match {value} {{ EvalValue::Node(node) => evaluate(node, self), value => self.convert_value(value, span) }}")
                } else {
                    format!("self.convert_value({value}, span)")
                }
            }
            "conditional" => {
                truth = true;
                for name in ["condition", "thenExpr", "elseExpr"] {
                    require(mapping, name, Kind::Node, Cardinality::One)?;
                }
                "let condition = evaluate(r#condition, self);\n        if self.is_failure(&condition) { return condition; }\n        if self.is_truthy(&condition) { evaluate(r#thenExpr, self) } else { evaluate(r#elseExpr, self) }".into()
            }
            "variable_ref" => {
                variable = true;
                require(mapping, "name", Kind::Text, Cardinality::One)?;
                let prefix = spec
                    .params
                    .get("strip_prefix")
                    .map(String::as_str)
                    .unwrap_or("");
                format!(
                    "self.resolve_variable(r#name.strip_prefix({}).unwrap_or(r#name))",
                    quote(prefix)
                )
            }
            "binary_arithmetic" => {
                binary = true;
                let left = field(mapping, "left")?;
                let right = field(mapping, "right")?;
                require(mapping, "op", Kind::Text, Cardinality::Many)?;
                if left.cardinality != Cardinality::One || right.cardinality != Cardinality::Many {
                    return Err(format!(
                        "E-EVAL: binary operands require scalar left and list right on {}",
                        mapping.name
                    ));
                }
                let leaf = if left.kind == Kind::NullableNode {
                    "let Some(r#left) = r#left else { assert_eq!(r#op.len(), 1, \"invalid associative literal leaf\"); assert!(r#right.is_empty(), \"invalid associative literal leaf\"); return self.evaluate_literal(&r#op[0]); };\n        "
                } else {
                    ""
                };
                format!("{leaf}assert_eq!(r#op.len(), r#right.len(), \"binary operator/operand count mismatch\");\n        let mut current = {};\n        if self.is_failure(&current) {{ return current; }}\n        for (op, right) in r#op.iter().zip(r#right) {{\n            let next = {};\n            if self.is_failure(&next) {{ return next; }}\n            current = self.apply_binary(op, current, next);\n            if self.is_failure(&current) {{ return current; }}\n        }}\n        current",operand(ir,left,"r#left"),operand(ir,right,"right"))
            }
            _ => unreachable!(),
        };
        let mut signature = format!(
            "    fn {}(&mut self",
            unlaxer_codegen::evaluator_method_name(&mapping.name)
        );
        for field in &mapping.fields {
            signature += &format!(
                ", r#{}: {}",
                field.name,
                unlaxer_codegen::evaluator_parameter_type(field)
            );
        }
        signature += ", span: Span) -> Self::Output";
        let ignored = mapping
            .fields
            .iter()
            .map(|f| format!("&r#{}", f.name))
            .collect::<Vec<_>>()
            .join(", ");
        source = source.replace(
            &(signature.clone() + ";"),
            &format!("{signature} {{\n        let _ = ({ignored}, span);\n        {body}\n    }}"),
        );
    }
    if !auto {
        return Ok(source);
    }
    let mut helpers = String::new();
    if values {
        helpers +=
            "    fn convert_value(&mut self, value: EvalValue<'_>, span: Span) -> Self::Output;\n";
    }
    if truth {
        helpers += "    fn is_truthy(&mut self, value: &Self::Output) -> bool;\n";
    }
    if binary {
        helpers+="    fn evaluate_literal(&mut self, literal: &str) -> Self::Output;\n    fn apply_binary(&mut self, op: &str, left: Self::Output, right: Self::Output) -> Self::Output;\n";
    }
    if truth || binary {
        helpers += "    fn is_failure(&self, value: &Self::Output) -> bool;\n";
    }
    if variable {
        helpers += "    fn resolve_variable(&mut self, name: &str) -> Self::Output;\n";
    }
    source = source
        .replace(
            "}\n\n#[allow(non_snake_case)]\npub fn evaluate",
            &(helpers + "}\n\n#[allow(non_snake_case)]\npub fn evaluate"),
        )
        .replace("evaluate<S: Semantics>", "evaluate<S: Semantics + ?Sized>");
    if values {
        let mut ty="\n/// Borrowed typed literal input; containers preserve their shape.\n#[derive(Debug)]\npub enum EvalValue<'a> {\n    Text(&'a str),\n    Integer(i32),\n    Node(&'a Ast),\n    Enum { name: &'static str, text: &'static str },\n    Optional(Option<Box<EvalValue<'a>>>),\n    Many(Vec<EvalValue<'a>>),\n}\n".to_string();
        if mappings
            .iter()
            .any(|m| m.fields.iter().any(|f| f.kind == Kind::Value))
        {
            ty+="\nfn eval_value(value: &AstValue) -> EvalValue<'_> {\n    match value {\n        AstValue::Text { text, .. } => EvalValue::Text(text),\n        AstValue::Node(node) => EvalValue::Node(node),\n";
            for rule in &ir.rules {
                if !rule.enum_literals.is_empty() {
                    ty+=&format!("        AstValue::Enum{} {{ value, .. }} => EvalValue::Enum {{ name: {}, text: value.text() }},\n",rule.name,quote(&rule.name));
                }
            }
            ty += "    }\n}\n";
        }
        source = source.replace(
            "\n#[allow(non_snake_case)]\npub trait Semantics",
            &(ty + "\n#[allow(non_snake_case)]\npub trait Semantics"),
        );
    }
    Ok(source)
}
