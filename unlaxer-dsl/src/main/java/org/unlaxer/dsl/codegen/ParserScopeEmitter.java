package org.unlaxer.dsl.codegen;

import java.util.stream.Collectors;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Scope effects select grammar capture sites, never the first matching parser class. */
final class ParserScopeEmitter {
    private ParserScopeEmitter() {}

    static String helpers(GrammarDecl grammar) {
        boolean scope = grammar.rules().stream().anyMatch(rule -> rule.annotations().stream()
            .anyMatch(annotation -> annotation instanceof ScopeTreeAnnotation));
        if (grammar.rules().stream().noneMatch(rule -> rule.annotations().stream().anyMatch(annotation ->
                annotation instanceof DeclaresAnnotation || scope && annotation instanceof BackrefAnnotation))) {
            return "";
        }
        String boundaries = grammar.rules().stream().map(rule -> rule.name() + "Parser.class")
            .collect(Collectors.joining(", "));
        return """
                private static final java.util.Set<Class<?>> __SCOPE_RULE_BOUNDARIES = java.util.Set.of(%s);
                private record __ScopeCapture(String name, int offset, int length) {}

                private static java.util.List<org.unlaxer.Token> __scopeCaptureSites(
                        org.unlaxer.Token root, java.util.Set<String> bindings) {
                    var sites = new java.util.ArrayList<org.unlaxer.Token>();
                    __collectScopeCaptureSites(root, bindings, sites, true);
                    return sites;
                }
                private static void __collectScopeCaptureSites(org.unlaxer.Token token,
                        java.util.Set<String> bindings, java.util.List<org.unlaxer.Token> sites, boolean root) {
                    if (token == null) return;
                    if (!root && __SCOPE_RULE_BOUNDARIES.contains(token.parser.getClass())) return;
                    for (org.unlaxer.Token child : token.filteredChildren) {
                        __collectScopeCaptureSites(child, bindings, sites, false);
                    }
                    // Captures complete inner-first. Several grammar sites may share one wrapper.
                    if (token.parser instanceof __CaptureBinding capture) {
                        for (String binding : capture.captureBindings()) {
                            if (bindings.contains(binding)) sites.add(token);
                        }
                    }
                }
                private static __ScopeCapture __scopeCaptureValue(org.unlaxer.Token token) {
                    if (token.source == null) return new __ScopeCapture("", 0, 0);
                    String raw = token.source.sourceAsString();
                    int start = 0, end = raw.length();
                    while (start < end && raw.charAt(start) <= 0x20) start++;
                    while (end > start && raw.charAt(end - 1) <= 0x20) end--;
                    return new __ScopeCapture(raw.substring(start, end),
                        token.source.offsetFromRoot().value() + raw.codePointCount(0, start),
                        raw.codePointCount(start, end));
                }

            """.formatted(boundaries);
    }

    static String action(ParserGenerator.GenContext ctx, RuleDecl rule, String capture, boolean declare) {
        String ids = ctx.captureBindings.get(rule.name()).sites(capture).stream()
            .map(site -> "\"" + ParserCodegenUtil.escapeString(site.id()) + "\"")
            .collect(Collectors.joining(", "));
        var w = new IndentedWriter(3);
        w.line("// Scope capture \"" + ParserCodegenUtil.escapeString(capture) + "\" at its grammar sites.");
        w.line("for (org.unlaxer.Token __site : __scopeCaptureSites(");
        w.line("        tokens.isEmpty() ? null : tokens.get(0), java.util.Set.of(" + ids + "))) {");
        w.indent();
        w.line("__ScopeCapture __symbol = __scopeCaptureValue(__site);");
        w.line("if (__symbol.name().isEmpty()) continue;");
        if (declare) {
            w.line("org.unlaxer.dsl.runtime.ScopeStore.declare(ctx, __symbol.name(), __symbol.offset());");
        } else {
            w.line("org.unlaxer.dsl.runtime.ScopeStore.addReference(ctx, __symbol.name(), __symbol.offset(), __symbol.length());");
            w.line("if (!org.unlaxer.dsl.runtime.ScopeStore.isDeclared(ctx, __symbol.name())) {");
            w.indent();
            w.line("org.unlaxer.dsl.runtime.ScopeStore.addDiagnostic(ctx,");
            w.line("    \"未定義のシンボル: '\" + __symbol.name() + \"'\",");
            w.line("    __symbol.offset(), __symbol.length(), org.unlaxer.dsl.runtime.ScopeStore.Severity.WARNING);");
            w.dedent();
            w.line("}");
        }
        w.dedent();
        w.line("}");
        return w.build();
    }
}
