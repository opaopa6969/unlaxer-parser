package org.unlaxer.dsl.codegen;

import java.util.*;
import org.unlaxer.context.NameSnapshot;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Pure grammar validation and stable requirements; no provider or snapshot lookup during generation. */
public final class NamePredicates {
    private NamePredicates() {}
    public static boolean enabled(GrammarDecl grammar) {
        return grammar.rules().stream().anyMatch(rule -> annotation(rule).isPresent());
    }
    public static java.util.Optional<NamePredicateAnnotation> annotation(RuleDecl rule) {
        return rule.annotations().stream().filter(NamePredicateAnnotation.class::isInstance)
            .map(NamePredicateAnnotation.class::cast).findFirst();
    }
    public static List<NameSnapshot.Requirement> requirements(GrammarDecl grammar) {
        var versions = new LinkedHashMap<String,String>();
        for (var rule : grammar.rules()) annotation(rule).ifPresent(predicate -> {
            String previous=versions.putIfAbsent(predicate.snapshot(),predicate.version());
            if(previous!=null && !previous.equals(predicate.version())) throw invalid("VERSION",rule.name());
        });
        if(versions.size()>64) throw invalid("LIMIT","at most 64 snapshots");
        return versions.entrySet().stream().map(entry -> new NameSnapshot.Requirement(entry.getKey(),entry.getValue())).toList();
    }
    public static void requireValid(GrammarDecl grammar) {
        if(!enabled(grammar)) return;
        var semantics=new SemanticCardinality(grammar);
        for(var rule:grammar.rules()) {
            var annotations=rule.annotations().stream().filter(NamePredicateAnnotation.class::isInstance).map(NamePredicateAnnotation.class::cast).toList();
            if(annotations.size()>1) throw invalid("DUPLICATE",rule.name());
            if(annotations.isEmpty()) continue;
            var predicate=annotations.get(0);
            try {new NameSnapshot.Requirement(predicate.snapshot(),predicate.version());}
            catch(IllegalArgumentException invalid) {throw invalid("IDENTITY",rule.name());}
            if(!List.of("type","value","resolved").contains(predicate.kind())) throw invalid("KIND",rule.name());
            if(rule.annotations().stream().anyMatch(annotation -> annotation instanceof LeftAssocAnnotation || annotation instanceof RightAssocAnnotation || annotation instanceof RecoveryAnnotation))
                throw invalid("CONFLICT",rule.name());
            var shape=semantics.captureShape(rule,predicate.name());
            if(shape==null || shape.kind()!=SemanticCardinality.Kind.TEXT || shape.count()!=SemanticCardinality.Count.ONE)
                throw invalid("CAPTURE",rule.name()+":"+predicate.name());
        }
        requirements(grammar);
        if(!GrammarValidator.detectLeftRecursionIssues(grammar).isEmpty()) throw invalid("LEFT-RECURSION",grammar.name());
    }
    private static IllegalArgumentException invalid(String code,String subject) {
        return new IllegalArgumentException("E-NAME-PREDICATE-"+code+": "+subject);
    }
    static String wrapper(ParserGenerator.GenContext context, RuleDecl rule) {
        var predicate=annotation(rule).orElseThrow();
        String sites=context.captureBindings.get(rule.name()).sites(predicate.name()).stream()
            .map(site -> "\""+ParserCodegenUtil.escapeString(site.id())+"\"").collect(java.util.stream.Collectors.joining(", "));
        return """
                public static class %sNamePredicateParser extends org.unlaxer.parser.combinator.NamePredicateParser {
                    private static final long serialVersionUID = 1L;
                    public %sNamePredicateParser() {
                        super(Parser.get(%sParser.class), "%s", "%s", "%s");
                    }
                    @Override protected java.util.List<org.unlaxer.Token> nameCaptureSites(org.unlaxer.Token root) {
                        return __scopeCaptureSites(root, java.util.Set.of(%s));
                    }
                }

            """.formatted(rule.name(),rule.name(),rule.name(),ParserCodegenUtil.escapeString(predicate.snapshot()),
                ParserCodegenUtil.escapeString(predicate.version()),ParserCodegenUtil.escapeString(predicate.kind()),sites);
    }
    static String javaRequirements(GrammarDecl grammar) {
        return "java.util.List.of("+requirements(grammar).stream().map(requirement ->
            "new org.unlaxer.context.NameSnapshot.Requirement(\""+ParserCodegenUtil.escapeString(requirement.id())+"\", \""+ParserCodegenUtil.escapeString(requirement.version())+"\")")
            .collect(java.util.stream.Collectors.joining(", "))+")";
    }
}
