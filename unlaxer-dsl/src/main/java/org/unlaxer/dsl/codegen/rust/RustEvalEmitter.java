package org.unlaxer.dsl.codegen.rust;

import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.codegen.EvalSchema;
import static org.unlaxer.dsl.codegen.rust.GrammarIR.*;

/** Adds typed evaluation defaults without adding frontend metadata to the public parser IR. */
final class RustEvalEmitter {
    private RustEvalEmitter() {}
    static String apply(GrammarDecl grammar, GrammarIR ir, String source) {
        Map<String, EvalAnnotation> specs = EvalSchema.specifications(grammar);
        boolean values = false, truth = false, binary = false, variable = false, automatic = false;
        for (var mapping : ir.mappings()) {
            var spec = specs.entrySet().stream().filter(e -> e.getKey().replace(".","__").equals(mapping.name())).map(Map.Entry::getValue).findFirst().orElse(null);
            if (!EvalSchema.automatic(spec)) continue;
            automatic = true;
            String body;
            switch (spec.kind()) {
                case "literal", "passthrough" -> {
                    values = true;
                    var field = field(mapping, "value");
                    String value = value(field, "r#value");
                    body = spec.kind().equals("passthrough") && field.cardinality() == Cardinality.ONE
                        ? "match " + value + " { EvalValue::Node(node) => evaluate(node, self), value => self.convert_value(value, span) }"
                        : "self.convert_value(" + value + ", span)";
                }
                case "conditional" -> {
                    truth = true;
                    for (String name : new String[]{"condition", "thenExpr", "elseExpr"}) require(mapping,name,Kind.NODE,Cardinality.ONE);
                    body = "let condition = evaluate(r#condition, self);\n        if self.is_failure(&condition) { return condition; }\n        if self.is_truthy(&condition) { evaluate(r#thenExpr, self) } else { evaluate(r#elseExpr, self) }";
                }
                case "variable_ref" -> {
                    variable = true;
                    require(mapping,"name",Kind.TEXT,Cardinality.ONE);
                    String prefix = spec.params().getOrDefault("strip_prefix", "");
                    body = "self.resolve_variable(r#name.strip_prefix(" + RustBackend.quote(prefix) + ").unwrap_or(r#name))";
                }
                case "binary_arithmetic" -> {
                    binary = true;
                    var left = field(mapping,"left"); var right = field(mapping,"right");
                    require(mapping,"op",Kind.TEXT,Cardinality.MANY);
                    if (left.cardinality()!=Cardinality.ONE || right.cardinality()!=Cardinality.MANY)
                        throw new IllegalArgumentException("E-EVAL: binary operands require scalar left and list right on " + mapping.name());
                    String leaf = left.kind()==Kind.NULLABLE_NODE ? "let Some(r#left) = r#left else { assert_eq!(r#op.len(), 1, \"invalid associative literal leaf\"); assert!(r#right.is_empty(), \"invalid associative literal leaf\"); return self.evaluate_literal(&r#op[0]); };\n        " : "";
                    body = leaf + "assert_eq!(r#op.len(), r#right.len(), \"binary operator/operand count mismatch\");\n        let mut current = " + operand(ir,left,"r#left") + ";\n        if self.is_failure(&current) { return current; }\n        for (op, right) in r#op.iter().zip(r#right) {\n            let next = " + operand(ir,new Field("right",right.kind(),Cardinality.ONE,right.enumName()),"right") + ";\n            if self.is_failure(&next) { return next; }\n            current = self.apply_binary(op, current, next);\n            if self.is_failure(&current) { return current; }\n        }\n        current";
                }
                default -> throw new AssertionError();
            }
            String signature = "    fn " + RustGrammarLowering.methodName(mapping.name()) + "(&mut self";
            for (var field : mapping.fields()) signature += ", r#"+field.name()+": "+RustBackend.fieldType(field,true);
            signature += ", span: Span) -> Self::Output";
            String ignored = mapping.fields().stream().map(f -> "&r#"+f.name()).collect(java.util.stream.Collectors.joining(", "));
            source = source.replace(signature+";", signature+" {\n        let _ = ("+ignored+", span);\n        "+body+"\n    }");
        }
        if (!automatic) return source;
        String helpers = "";
        if (values) helpers += "    fn convert_value(&mut self, value: EvalValue<'_>, span: Span) -> Self::Output;\n";
        if (truth) helpers += "    fn is_truthy(&mut self, value: &Self::Output) -> bool;\n";
        if (binary) helpers += "    fn evaluate_literal(&mut self, literal: &str) -> Self::Output;\n    fn apply_binary(&mut self, op: &str, left: Self::Output, right: Self::Output) -> Self::Output;\n";
        if (truth || binary) helpers += "    fn is_failure(&self, value: &Self::Output) -> bool;\n";
        if (variable) helpers += "    fn resolve_variable(&mut self, name: &str) -> Self::Output;\n";
        source = source.replace("}\n\n#[allow(non_snake_case)]\npub fn evaluate", helpers+"}\n\n#[allow(non_snake_case)]\npub fn evaluate")
            .replace("evaluate<S: Semantics>","evaluate<S: Semantics + ?Sized>");
        if (values) {
            String type = "\n/// Borrowed typed literal input; containers preserve their shape.\n#[derive(Debug)]\npub enum EvalValue<'a> {\n    Text(&'a str),\n    Integer(i32),\n    Node(&'a Ast),\n    Enum { name: &'static str, text: &'static str },\n    Optional(Option<Box<EvalValue<'a>>>),\n    Many(Vec<EvalValue<'a>>),\n}\n";
            if (ir.mappings().stream().flatMap(m -> m.fields().stream()).anyMatch(f -> f.kind()==Kind.VALUE)) {
                type += "\nfn eval_value(value: &AstValue) -> EvalValue<'_> {\n    match value {\n        AstValue::Text { text, .. } => EvalValue::Text(text),\n        AstValue::Node(node) => EvalValue::Node(node),\n";
                for (var rule : ir.rules()) if (!rule.enumLiterals().isEmpty())
                    type += "        AstValue::Enum"+rule.name()+" { value, .. } => EvalValue::Enum { name: "+RustBackend.quote(rule.name())+", text: value.text() },\n";
                type += "    }\n}\n";
            }
            source = source.replace("\n#[allow(non_snake_case)]\npub trait Semantics",type+"\n#[allow(non_snake_case)]\npub trait Semantics");
        }
        return source;
    }
    private static Field field(Mapping mapping, String name) {
        return mapping.fields().stream().filter(f -> f.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("E-EVAL: missing field "+name+" on "+mapping.name()));
    }
    private static void require(Mapping mapping, String name, Kind kind, Cardinality cardinality) {
        var value = field(mapping,name);
        if (value.kind()!=kind || value.cardinality()!=cardinality)
            throw new IllegalArgumentException("E-EVAL: invalid field "+name+" on "+mapping.name());
    }
    private static String value(Field field, String expression) {
        if (field.cardinality()==Cardinality.OPTIONAL)
            return "EvalValue::Optional("+expression+".map(|value| Box::new("+scalar(field,"value")+")))";
        if (field.cardinality()==Cardinality.MANY)
            return "EvalValue::Many("+expression+".iter().map(|value| "+scalar(field,"value")+").collect())";
        return scalar(field,expression);
    }
    private static String scalar(Field field, String expression) {
        return switch(field.kind()) {
            case TEXT -> "EvalValue::Text("+expression+")";
            case NESTED -> throw new IllegalArgumentException("E-NESTED-SHAPE: autoEval requires remaining #583 implementation");
            case INTEGER -> "EvalValue::Integer(*"+expression+")";
            case NODE -> "EvalValue::Node("+expression+")";
            case NULLABLE_NODE -> throw new IllegalArgumentException("nullable node only permitted as associative left");
            case ENUM -> "EvalValue::Enum { name: "+RustBackend.quote(field.enumName())+", text: "+expression+".text() }";
            case VALUE -> "eval_value("+expression+")";
        };
    }
    private static String operand(GrammarIR ir, Field field, String expression) {
        return switch(field.kind()) {
            case NODE, NULLABLE_NODE -> "evaluate("+expression+", self)";
            case TEXT -> "self.evaluate_literal("+expression+")";
            case NESTED -> throw new IllegalArgumentException("E-NESTED-SHAPE: autoEval requires remaining #583 implementation");
            case INTEGER -> "self.evaluate_literal(&"+expression+".to_string())";
            case ENUM -> "self.evaluate_literal(&format!(\"{:?}\", "+expression+"))";
            case VALUE -> {
                String arms = "match "+expression+" { AstValue::Text { text, .. } => self.evaluate_literal(text), AstValue::Node(node) => evaluate(node, self),";
                for (var rule : ir.rules()) if (!rule.enumLiterals().isEmpty())
                    arms += " AstValue::Enum"+rule.name()+" { value, .. } => self.evaluate_literal(&format!(\"{:?}\", value)),";
                yield arms+" }";
            }
        };
    }
}
