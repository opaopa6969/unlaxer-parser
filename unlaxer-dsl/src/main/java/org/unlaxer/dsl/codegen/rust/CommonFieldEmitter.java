package org.unlaxer.dsl.codegen.rust;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.CommonFieldSchema;
import org.unlaxer.dsl.codegen.NestedCaptures;

import java.util.*;

/** Borrowed family views preserve the existing owned Ast variants and evaluator API. */
final class CommonFieldEmitter {
    static List<CommonFieldSchema.Family> families(GrammarDecl grammar) {
        // A nested field keeps its recursive representation even when its Java type
        // is identical to an ordinary field. Check this before eval/enum/lowering.
        return CommonFieldSchema.build(grammar, (leaf, field) ->
                NestedCaptures.enabled(leaf) ? NestedCaptures.field(grammar, leaf, field) : null);
    }

    static void validate(List<CommonFieldSchema.Family> families, GrammarIR ir) {
        for (var family : families) fields(family, ir);
    }

    static String emit(GrammarDecl grammar, GrammarIR ir) {
        var out = new StringBuilder();
        for (var family : families(grammar)) {
            var name = CommonFieldSchema.rustName(family.name());
            var view = name + "Fields";
            var fields = fields(family, ir);
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

    private static List<GrammarIR.Field> fields(CommonFieldSchema.Family family, GrammarIR ir) {
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
                    throw CommonFieldSchema.mismatch(
                            family.rule(), "incompatible field " + field.name());
                contract = actual;
            }
            fields.add(contract);
        }
        String view = CommonFieldSchema.rustName(family.name()) + "Fields";
        if (ir.mappings().stream().anyMatch(m -> m.name().equals(view))
                || ir.rules().stream()
                        .anyMatch(r -> !r.enumLiterals().isEmpty() && r.name().equals(view)))
            throw CommonFieldSchema.mismatch(family.rule(), "view name collision");
        return List.copyOf(fields);
    }
}
