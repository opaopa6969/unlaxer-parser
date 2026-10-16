package org.unlaxer.dsl.codegen.rust;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.CommonFieldSchema;

import java.util.*;

/** Borrowed family views preserve the existing owned Ast variants and evaluator API. */
final class CommonFieldEmitter {
    static String emit(GrammarDecl grammar, GrammarIR ir) {
        var out = new StringBuilder();
        for (var family : CommonFieldSchema.build(grammar)) {
            var name = CommonFieldSchema.rustName(family.name());
            var view = name + "Fields";
            var fields = new ArrayList<GrammarIR.Field>();
            for (var field : family.fields()) {
                GrammarIR.Field contract = null;
                for (var variant : family.variants()) {
                    var mapping =
                            ir.mappings().stream()
                                    .filter(
                                            m ->
                                                    m.name()
                                                            .equals(
                                                                    CommonFieldSchema.rustName(
                                                                            variant)))
                                    .findFirst()
                                    .orElseThrow();
                    var actual =
                            mapping.fields().stream()
                                    .filter(f -> f.name().equals(field.name()))
                                    .findFirst()
                                    .orElseThrow();
                    if (contract != null && !contract.equals(actual))
                        throw new IllegalArgumentException(
                                "E-COMMONFIELD-MISMATCH: "
                                        + family.rule()
                                        + ": incompatible field "
                                        + field.name());
                    contract = actual;
                }
                fields.add(contract);
            }
            if (ir.mappings().stream().anyMatch(m -> m.name().equals(view))
                    || ir.rules().stream()
                            .anyMatch(r -> !r.enumLiterals().isEmpty() && r.name().equals(view)))
                throw new IllegalArgumentException(
                        "E-COMMONFIELD-MISMATCH: " + family.rule() + ": view name collision");
            out.append(
                            "\n"
                                    + "#[allow(non_camel_case_types)]\n"
                                    + "#[derive(Debug, Clone, Copy)]\n"
                                    + "pub struct ")
                    .append(view)
                    .append("<'a> {\n");
            for (var field : fields)
                out.append("    pub r#")
                        .append(field.name())
                        .append(": &'a ")
                        .append(RustBackend.fieldType(field, false))
                        .append(",\n");
            out.append("}\n\n#[allow(non_snake_case)]\nimpl Ast {\n    pub fn common_")
                    .append(RustGrammarLowering.methodName(name).substring(5))
                    .append("(&self) -> Option<")
                    .append(view)
                    .append("<'_>> {\n        match self {\n");
            for (var variant : family.variants()) {
                out.append("            Self::r#")
                        .append(CommonFieldSchema.rustName(variant))
                        .append(" { ");
                for (var field : fields) out.append("r#").append(field.name()).append(", ");
                out.append(".. } => Some(").append(view).append(" { ");
                for (var field : fields) out.append("r#").append(field.name()).append(", ");
                out.append("}),\n");
            }
            out.append(
                    "            #[allow(unreachable_patterns)]\n"
                            + "            _ => None,\n"
                            + "        }\n"
                            + "    }\n"
                            + "}\n");
        }
        return out.toString();
    }
}
