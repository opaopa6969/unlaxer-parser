package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;

/** A distinct sum keeps legacy flat Object fields and their consumers unchanged. */
final class NestedMixedEmitter {
    private NestedMixedEmitter() {}
    static String ast(GrammarDecl grammar) {
        String ast=grammar.name()+"AST";
        var variants=new java.util.ArrayList<String>(java.util.List.of("NestedValue.Text","NestedValue.Integer","NestedValue.Node"));
        for(var rule:grammar.rules())if(EnumSchema.isEnum(rule))variants.add("NestedValue.Enum"+rule.name());
        var out=new StringBuilder("    /** One typed nested scalar occurrence; wrappers retain source identity. */\n    sealed interface NestedValue permits "+String.join(", ",variants)+" {\n");
        out.append("        record Text(java.lang.String value) implements NestedValue { public Text { java.util.Objects.requireNonNull(value); } }\n")
            .append("        record Integer(int value) implements NestedValue {}\n")
            .append("        record Node(").append(ast).append(" value) implements NestedValue { public Node { java.util.Objects.requireNonNull(value); } }\n");
        for(var rule:grammar.rules())if(EnumSchema.isEnum(rule))out.append("        record Enum").append(rule.name()).append('(').append(ast).append('.').append(rule.name())
            .append(" value) implements NestedValue { public Enum").append(rule.name()).append(" { java.util.Objects.requireNonNull(value); } }\n");
        return out.append("    }\n\n").toString();
    }
    static String mapper(GrammarDecl grammar) {
        String ast=grammar.name()+"AST", value=ast+".NestedValue";
        var out=new StringBuilder("    private static java.lang.String nestedMixedSourceText(Token token) {\n        if (MAPPING_GUARD != null) MAPPING_GUARD.text(token);\n        return token.source.sourceAsString();\n    }\n\n");
        out.append("    private static ").append(value).append(" mapNestedValue(Token token) {\n        guardVisit(token);\n");
        out.append("        if (hasCaptureBinding(token, \"#nested:text\")) return registerNodeSourceSpan(new ").append(value).append(".Text(nestedMixedSourceText(token)), token);\n")
            .append("        if (hasCaptureBinding(token, \"#nested:integer\")) {\n            try { return registerNodeSourceSpan(new ").append(value).append(".Integer(java.lang.Integer.parseInt(nestedMixedSourceText(token).trim())), token); }\n")
            .append("            catch (java.lang.NumberFormatException error) { throw new java.lang.IllegalArgumentException(\"invalid i32 nested mixed capture at \" + token.source.offsetFromRoot().value(), error); }\n        }\n");
        for(var rule:grammar.rules())if(EnumSchema.isEnum(rule))out.append("        if (token.parser instanceof ").append(grammar.name()).append("Parsers.").append(rule.name()).append("Parser) return registerNodeSourceSpan(new ")
            .append(value).append(".Enum").append(rule.name()).append('(').append(ast).append('.').append(rule.name()).append(".fromText(tokenTextCompat(token))), token);\n");
        out.append("        var node=mapToken(token);\n        return node==null?null:registerNodeSourceSpan(new ").append(value).append(".Node(node), token);\n    }\n\n");
        return out.toString();
    }
}
