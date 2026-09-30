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
                fields.add(new Field(field.name(), type, field.cardinality().name().toLowerCase(Locale.ROOT),
                    ApiImpact.location("ast.rs", ast, fieldStart, fieldStart + declaration.length())));
                parameters.add(new Parameter(field.name(), RustBackend.fieldType(field, true)));
            }
            var origins = ApiImpact.origins(source, mapping.name());
            nodes.add(new Node(mapping.name(), "variant", fields, List.of(), List.of(), origins,
                ApiImpact.location("ast.rs", ast, start, end)));
            parameters.add(new Parameter("span", "Span"));
            String method = RustGrammarLowering.methodName(mapping.name());
            int methodStart = requireIndex(evaluator, "    fn " + method + "(", 0);
            methods.add(new Method(method, "Self::Output", parameters, true, origins,
                ApiImpact.location("evaluator.rs", evaluator, methodStart, evaluator.indexOf('\n', methodStart))));
        }
        return new Snapshot(grammar.name(), "Ast", "Semantics", nodes, methods);
    }
    private static int requireIndex(String source, String declaration, int from) {
        int found = source.indexOf(declaration, from);
        if (found < 0) throw new IllegalStateException("generated declaration missing: " + declaration);
        return found;
    }
}
