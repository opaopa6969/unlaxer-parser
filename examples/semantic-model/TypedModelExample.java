package example.semantic;

import java.util.*;
import org.unlaxer.dsl.semantic.SemanticModel;
import org.unlaxer.dsl.semantic.SemanticModel.*;

/** Adapter for model.ubnf, compiled together with its generated Java sources. */
public final class TypedModelExample {
    public static SemanticModel model(String source, long version) {
        var parsed = TypedModelMapper.parseWithSourceMap(source);
        java.util.function.Function<Object, Span> position = node -> {
            int[] p = parsed.sourceSpanOf(node).orElseThrow(); return new Span(p[0], p[1]);
        };
        var types = new ArrayList<Type>(); var symbols = new ArrayList<Symbol>();
        var signatures = new ArrayList<Signature>(); var calls = new ArrayList<Call>();
        for (var node : parsed.ast().items()) {
            var span = position.apply(node);
            if (node instanceof TypedModelAST.TypeDecl t) {
                var fields = t.fields().stream().map(value -> {
                    var f = (TypedModelAST.Field) value;
                    return new Field(f.name(), f.type(), position.apply(f));
                }).toList();
                types.add(new Type(t.name(), TypeKind.valueOf(t.kind().toUpperCase(Locale.ROOT)), t.parents(), fields, span));
            } else if (node instanceof TypedModelAST.ValueDecl v) {
                symbols.add(new Symbol("value:"+span.start(), v.name(), v.type(), "root", span, span.end()));
            } else if (node instanceof TypedModelAST.FunctionDecl f) {
                signatures.add(new Signature("fn:"+span.start(), f.name(), f.parameters(), f.result(), span));
            }
        }
        for (var node : parsed.ast().items()) {
            if (!(node instanceof TypedModelAST.Call c)) continue;
            var span = position.apply(c);
            var arguments = new ArrayList<Argument>();
            for (var value : c.arguments()) {
                var a = (TypedModelAST.Argument) value; var at = position.apply(a);
                String type = symbols.stream().filter(s -> s.name().equals(a.value()) && s.visibleFrom() <= at.start())
                    .findFirst().map(Symbol::type).orElse(SemanticModel.UNKNOWN);
                arguments.add(new Argument(type, at));
            }
            calls.add(new Call("call:"+span.start(), signatures.stream().filter(s -> s.name().equals(c.name()))
                .map(Signature::id).toList(), arguments, span));
        }
        return new SemanticModel("memory:typed-model", version, source, types,
            List.of(new Scope("root", null, new Span(0, source.codePointCount(0, source.length())))), symbols, signatures, calls);
    }
}
