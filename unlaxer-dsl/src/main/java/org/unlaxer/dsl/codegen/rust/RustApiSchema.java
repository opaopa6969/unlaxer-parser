package org.unlaxer.dsl.codegen.rust;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.impact.ApiImpact;
import org.unlaxer.dsl.impact.ApiImpact.*;

/** Schema of the real Rust emitter: no second type/cardinality inference algorithm. */
public final class RustApiSchema {
    private RustApiSchema() {}
    public static Snapshot build(UBNFSourceSnapshot source) {
        var grammar = source.ast().grammars().get(0);
        var ir = RustGrammarLowering.lower(grammar);
        var files = new RustBackend().generate(grammar);
        String ast = files.stream().filter(file -> file.relativePath().equals("ast.rs")).findFirst().orElseThrow().content();
        String evaluator = files.stream().filter(file -> file.relativePath().equals("evaluator.rs")).findFirst().orElseThrow().content();
        List<Node> nodes = new ArrayList<>();
        List<Method> methods = new ArrayList<>();
        for (var mapping : ir.mappings()) {
            String nodePrefix = "    r#" + mapping.name() + " { span: Span";
            int start = requireIndex(ast, nodePrefix, 0);
            int end = ast.indexOf('\n', start);
            List<Field> fields = new ArrayList<>();
            List<Parameter> parameters = new ArrayList<>();
            parameters.add(new Parameter("self", "&mut Self"));
            for (var field : mapping.fields()) {
                String type = RustBackend.fieldType(field, false);
                String declaration = "r#" + field.name() + ": " + type;
                int fieldStart = requireIndex(ast, declaration, start);
                if (fieldStart + declaration.length() > end) throw new IllegalStateException("field outside variant declaration");
                fields.add(new Field(field.name(), type, (field.kind()==GrammarIR.Kind.NESTED && !field.nested().containers().isEmpty() ? field.nested().containers().get(0) : field.cardinality()).name().toLowerCase(Locale.ROOT),
                    ApiImpact.location("ast.rs", ast, fieldStart, fieldStart + declaration.length())));
                parameters.add(new Parameter(field.name(), RustBackend.fieldType(field, true)));
            }
            var origins = rustOrigins(source, mapping.name());
            nodes.add(new Node(mapping.name(), "variant", fields, List.of(), List.of(), origins,
                ApiImpact.location("ast.rs", ast, start, end)));
            parameters.add(new Parameter("span", "Span"));
            String method = RustGrammarLowering.methodName(mapping.name());
            int methodStart = requireIndex(evaluator, "    fn " + method + "(", 0);
            methods.add(new Method(method, "Self::Output", parameters, evaluator.substring(methodStart, evaluator.indexOf('\n',methodStart)).endsWith(";"), origins,
                ApiImpact.location("evaluator.rs", evaluator, methodStart, evaluator.indexOf('\n', methodStart))));
        }
        for(var rule:ir.rules()) if(!rule.enumLiterals().isEmpty()) {
            int start=requireIndex(ast,"pub enum "+rule.name()+" {",0);
            var variants=rule.enumLiterals().stream().map(org.unlaxer.dsl.codegen.EnumSchema::constantName).sorted().toList();
            nodes.add(new Node(rule.name(),"enum",List.of(),List.of(),variants,ApiImpact.origins(source,rule.name()),
                ApiImpact.location("ast.rs",ast,start,ast.indexOf('\n',start))));
        }
        if (org.unlaxer.dsl.codegen.NestedCaptures.hasMixed(grammar)) {
            int start=requireIndex(ast,"pub enum NestedValue {",0);
            var variants=new ArrayList<String>(List.of("Text","Integer","Node"));
            for(var rule:ir.rules())if(!rule.enumLiterals().isEmpty())variants.add("Enum"+rule.name());
            nodes.add(new Node("NestedValue","enum",List.of(),List.of(),variants.stream().sorted().toList(),ApiImpact.origins(source,null),
                ApiImpact.location("ast.rs",ast,start,ast.indexOf('\n',start))));
            for(String variant:variants) {
                String name=variant.equals("Text")?"text":variant.equals("Node")?"0":"value";
                String type=switch(variant) {case "Text"->"String";case "Integer"->"i32";case "Node"->"Box<Ast>";default->variant.substring(4);};
                String prefix="    "+variant+(variant.equals("Node")?"(":" {");
                int variantStart=requireIndex(ast,prefix,start),variantEnd=ast.indexOf('\n',variantStart);
                var fields=new ArrayList<Field>();
                String declaration=variant.equals("Node")?type:name+": "+type;
                int fieldStart=requireIndex(ast,declaration,variantStart);
                fields.add(new Field(name,type,"one",ApiImpact.location("ast.rs",ast,fieldStart,fieldStart+declaration.length())));
                if(!variant.equals("Node")) {
                    int spanStart=requireIndex(ast,"span: Span",variantStart);
                    fields.add(new Field("span","Span","one",ApiImpact.location("ast.rs",ast,spanStart,spanStart+10)));
                }
                nodes.add(new Node("NestedValue."+variant,"variant",fields,List.of("NestedValue"),List.of(),ApiImpact.origins(source,null),
                    ApiImpact.location("ast.rs",ast,variantStart,variantEnd)));
            }
        }
        var signatures = java.util.regex.Pattern.compile("(?m)^    fn ([A-Za-z_][A-Za-z_0-9]*)\\(([^\\n]*)\\) -> ([^;{]+);").matcher(evaluator);
        while (signatures.find()) {
            String name=signatures.group(1);
            if (methods.stream().anyMatch(m -> m.name().equals(name))) continue;
            var parameters=new ArrayList<Parameter>();
            for (String argument:signatures.group(2).split(", ")) {
                if (argument.equals("&mut self")) parameters.add(new Parameter("self","&mut Self"));
                else if (argument.equals("&self")) parameters.add(new Parameter("self","&Self"));
                else {int colon=argument.indexOf(": ");parameters.add(new Parameter(argument.substring(0,colon),argument.substring(colon+2)));}
            }
            methods.add(new Method(name,signatures.group(3),parameters,true,ApiImpact.origins(source,null),
                ApiImpact.location("evaluator.rs",evaluator,signatures.start(),signatures.end())));
        }
        for (var family : org.unlaxer.dsl.codegen.CommonFieldSchema.build(grammar)) {
            String name =
                    org.unlaxer.dsl.codegen.CommonFieldSchema.rustName(family.name()) + "Fields";
            int start = requireIndex(ast, "pub struct " + name + "<'a> {", 0);
            var first =
                    ir.mappings().stream()
                            .filter(
                                    m ->
                                            m.name()
                                                    .equals(
                                                            org.unlaxer.dsl.codegen
                                                                    .CommonFieldSchema.rustName(
                                                                    family.variants().get(0))))
                            .findFirst()
                            .orElseThrow();
            var fields = new ArrayList<Field>();
            for (var contract : family.fields()) {
                var field =
                        first.fields().stream()
                                .filter(f -> f.name().equals(contract.name()))
                                .findFirst()
                                .orElseThrow();
                String type = "&'a " + RustBackend.fieldType(field, false);
                String declaration = "r#" + field.name() + ": " + type;
                int fieldStart = requireIndex(ast, declaration, start);
                fields.add(
                        new Field(
                                field.name(),
                                type,
                                (field.kind()==GrammarIR.Kind.NESTED && !field.nested().containers().isEmpty() ? field.nested().containers().get(0) : field.cardinality()).name().toLowerCase(Locale.ROOT),
                                ApiImpact.location(
                                        "ast.rs",
                                        ast,
                                        fieldStart,
                                        fieldStart + declaration.length())));
            }
            var origins =
                    source.ast().grammars().get(0).rules().stream()
                            .filter(r -> r.name().equals(family.rule()))
                            .map(
                                    r -> {
                                        var span = source.spanOf(r).orElseThrow();
                                        return new Origin(
                                                r.name(), new Span(span.start(), span.end()));
                                    })
                            .toList();
            nodes.add(
                    new Node(
                            name,
                            "struct",
                            fields,
                            List.of(),
                            family.variants().stream()
                                    .map(org.unlaxer.dsl.codegen.CommonFieldSchema::rustName)
                                    .sorted()
                                    .toList(),
                            origins,
                            ApiImpact.location("ast.rs", ast, start, ast.indexOf('\n', start))));
            String method =
                    "common_"
                            + RustGrammarLowering.methodName(
                                            org.unlaxer.dsl.codegen.CommonFieldSchema.rustName(
                                                    family.name()))
                                    .substring(5);
            int methodStart = requireIndex(ast, "    pub fn " + method + "(", 0);
            methods.add(
                    new Method(
                            method,
                            "Option<" + name + "<'_>>",
                            List.of(new Parameter("self", "&Self")),
                            false,
                            origins,
                            ApiImpact.location(
                                    "ast.rs", ast, methodStart, ast.indexOf('\n', methodStart))));
        }
        return new Snapshot(grammar.name(), "Ast", "Semantics", nodes, methods);
    }
    private static List<Origin> rustOrigins(UBNFSourceSnapshot source, String name) {
        boolean dotted =
                source.ast().grammars().get(0).rules().stream()
                        .flatMap(r -> r.annotations().stream())
                        .filter(
                                org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation.class
                                        ::isInstance)
                        .map(org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation.class::cast)
                        .anyMatch(
                                m ->
                                        m.className().contains(".")
                                                && org.unlaxer.dsl.codegen.CommonFieldSchema
                                                        .rustName(m.className())
                                                        .equals(name));
        if (!dotted) return ApiImpact.origins(source, name);
        return source.ast().grammars().get(0).rules().stream()
                .filter(
                        r ->
                                r.annotations().stream()
                                        .noneMatch(
                                                org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation
                                                                .class
                                                        ::isInstance))
                .filter(
                        rule ->
                                rule.annotations().stream()
                                        .filter(
                                                org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation
                                                                .class
                                                        ::isInstance)
                                        .map(
                                                org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation
                                                                .class
                                                        ::cast)
                                        .anyMatch(
                                                m ->
                                                        org.unlaxer.dsl.codegen.CommonFieldSchema
                                                                .rustName(m.className())
                                                                .equals(name)))
                .map(
                        rule -> {
                            var span = source.spanOf(rule).orElseThrow();
                            return new Origin(rule.name(), new Span(span.start(), span.end()));
                        })
                .sorted(java.util.Comparator.comparing(Origin::rule))
                .toList();
    }

    private static int requireIndex(String source, String declaration, int from) {
        int found = source.indexOf(declaration, from);
        if (found < 0) throw new IllegalStateException("generated declaration missing: " + declaration);
        return found;
    }
}
