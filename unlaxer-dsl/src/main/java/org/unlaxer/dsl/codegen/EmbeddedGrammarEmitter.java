package org.unlaxer.dsl.codegen;

import java.util.*;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Opt-in bounded embedding declarations using the existing setting block syntax. */
public final class EmbeddedGrammarEmitter {
    private EmbeddedGrammarEmitter() {}
    public record Declaration(String rule, String body, String language, String packageId,
                              String version, String grammar, String entry) {}
    public static boolean enabled(GrammarDecl grammar) {
        return grammar.settings().stream().anyMatch(s -> Set.of("embedding", "embedded").contains(s.key()));
    }
    public static List<Declaration> declarations(GrammarDecl grammar) {
        var result = new ArrayList<Declaration>();
        var seen = new HashSet<String>();
        int profiles = 0;
        for (var setting : grammar.settings()) {
            if (setting.key().equals("embedding")) {
                if (++profiles > 1 || !(setting.value() instanceof StringSettingValue v) || !v.value().equals("enabled"))
                    throw invalid("embedding must be enabled once");
            }
            if (!setting.key().equals("embedded")) continue;
            if (!(setting.value() instanceof BlockSettingValue block)) throw invalid("embedded needs a block");
            var values = new LinkedHashMap<String, String>();
            for (var pair : block.entries()) if (pair.value().isEmpty() || values.put(pair.key(), pair.value()) != null)
                throw invalid("empty or duplicate embedding field");
            if (!values.keySet().equals(Set.of("rule", "body", "language", "package", "version", "grammar", "entry")))
                throw invalid("embedding fields must be rule/body/language/package/version/grammar/entry");
            String name = values.get("rule"), bodyName = values.get("body");
            if (!seen.add(name)) throw invalid("duplicate embedded rule");
            var rule = grammar.rules().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow(() -> invalid("unknown embedded rule"));
            RuleBody body = rule.body();
            if (body instanceof ChoiceBody choice && choice.alternatives().size() == 1) body = choice.alternatives().get(0);
            if (!(body instanceof SequenceBody sequence) || sequence.elements().stream()
                    .filter(e -> e.captureName().filter(bodyName::equals).isPresent()).count() != 1
                    || new CaptureBindingPlan(rule).sites(bodyName).size() != 1
                    || rule.annotations().stream().anyMatch(a -> a instanceof RecoveryAnnotation))
                throw invalid("embedding needs one direct body capture in an unrecovered sequence");
            // Collection/optional captures can denote multiple or absent ranges. This slice requires a scalar boundary.
            var element = sequence.elements().stream().filter(e -> e.captureName().filter(bodyName::equals).isPresent()).findFirst().orElseThrow().element();
            if (element instanceof OptionalElement || element instanceof RepeatElement || element instanceof OneOrMoreElement
                    || element instanceof BoundedRepeatElement || element instanceof SeparatedElement)
                throw invalid("embedding body must be a scalar capture");
            result.add(new Declaration(name, bodyName, values.get("language"), values.get("package"), values.get("version"), values.get("grammar"), values.get("entry")));
        }
        return List.copyOf(result);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("E-EMBEDDING: " + message); }
    private static String quote(String value) { return "\"" + ParserCodegenUtil.escapeString(value) + "\""; }
    static String javaApi(GrammarDecl grammar) {
        var declarations = declarations(grammar);
        if (!enabled(grammar)) return "";
        var out = new StringBuilder("    public static org.unlaxer.source.CstGrammar embeddedGrammar() {\n        return new org.unlaxer.source.CstGrammar(")
            .append(quote(grammar.name())).append(", java.util.Map.ofEntries(\n");
        var entries = new ArrayList<String>();
        var boundaries = new ArrayList<String>();
        for (var rule : grammar.rules()) {
            String parser = rule.name() + (rule.annotations().stream().anyMatch(a -> a instanceof RecoveryAnnotation) ? "RecoveryParser" : "Parser");
            entries.add("            java.util.Map.entry(" + quote(rule.name()) + ", Parser.get(" + parser + ".class))");
            boundaries.add(rule.name() + "Parser.class");
        }
        out.append(String.join(",\n", entries)).append("), java.util.List.of(\n");
        var bindings = new ArrayList<String>();
        for (var d : declarations) {
            var rule = grammar.rules().stream().filter(r -> r.name().equals(d.rule)).findFirst().orElseThrow();
            String capture = new CaptureBindingPlan(rule).sites(d.body).get(0).id();
            bindings.add("            new org.unlaxer.source.CstGrammar.Binding(" + d.rule + "Parser.class, java.util.Set.of(" + quote(capture)
                + "), new org.unlaxer.source.LanguageRegions.Language(" + String.join(", ", List.of(quote(d.language), quote(d.packageId), quote(d.version), quote(d.grammar), quote(d.entry))) + "))");
        }
        return out.append(String.join(",\n", bindings)).append("), java.util.Set.of(").append(String.join(", ", boundaries))
            .append("), token -> token.parser instanceof __CaptureBinding capture ? capture.captureBindings() : java.util.List.of(), ")
            .append(TokenStreamGrammar.whitespace(grammar)).append(");\n    }\n\n").toString();
    }
    public static String rustApi(GrammarDecl grammar, List<String> rules, boolean whitespace) {
        var declarations = declarations(grammar);
        if (!enabled(grammar)) return "";
        var out = new StringBuilder("\npub fn embedded_grammar() -> unlaxer_runtime::embedded::CstGrammar {\n    unlaxer_runtime::embedded::CstGrammar { name: ")
            .append(rustQuote(grammar.name())).append(".into(), grammar: std::sync::Arc::clone(grammar()), whitespace: ").append(whitespace).append(", entries: [\n");
        for (var rule : grammar.rules()) out.append("        (").append(rustQuote(rule.name())).append(".into(), ").append(rules.indexOf(rule.name())).append("),\n");
        out.append("    ].into_iter().collect(), bindings: vec![\n");
        for (var d : declarations) out.append("        unlaxer_runtime::embedded::Binding { rule: ").append(rules.indexOf(d.rule))
            .append(", capture: ").append(rustQuote(d.body)).append(".into(), language: unlaxer_runtime::source::Language { id: ").append(rustQuote(d.language))
            .append(".into(), package_id: ").append(rustQuote(d.packageId)).append(".into(), version: ").append(rustQuote(d.version))
            .append(".into(), grammar: ").append(rustQuote(d.grammar)).append(".into(), entry: ").append(rustQuote(d.entry)).append(".into() } },\n");
        return out.append("    ] }\n}\n").toString();
    }
    private static String rustQuote(String value) {
        var out = new StringBuilder("\"");
        value.codePoints().forEach(c -> {
            if (c == '\\' || c == '"') out.append('\\').appendCodePoint(c);
            else if (c < 32 || c == 127) out.append("\\u{").append(Integer.toHexString(c)).append('}');
            else out.appendCodePoint(c);
        });
        return out.append('"').toString();
    }
}
