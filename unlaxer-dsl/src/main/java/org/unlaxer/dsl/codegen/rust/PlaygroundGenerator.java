package org.unlaxer.dsl.codegen.rust;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFModuleLoader;

/** Self-contained browser project; both hosts use the same assets and vendored runtime. */
public final class PlaygroundGenerator {
    private PlaygroundGenerator() {}

    public static Map<String, String> generateProfile(Path path) throws IOException {
        var profile = org.unlaxer.source.LanguageProfile.parse(Files.readString(path));
        Path grammarPath = path.toAbsolutePath().getParent().resolve(profile.grammarFile());
        var file = UBNFModuleLoader.load(grammarPath);
        if (file.grammars().size() != 1) throw new IllegalArgumentException("profile requires exactly one grammar");
        var grammar = file.grammars().get(0);
        for (String entry : profile.entries().keySet()) {
            if (!profile.identity(entry).grammar().equals(grammar.name()) || grammar.rules().stream().noneMatch(rule -> rule.name().equals(entry))) {
                throw new IllegalArgumentException("profile grammar/entry mismatch");
            }
        }
        Map<String, String> files = new LinkedHashMap<>(generate(grammarPath));
        files.put("public/profile.tsv", profile.canonicalTsv());
        files.put("public/index.html", files.get("public/index.html").replace("<body>", "<body data-profile=\"profile.tsv\">"));
        return java.util.Collections.unmodifiableMap(files);
    }

    public static Map<String, String> generatePackage(Path manifest, String packageId) throws IOException {
        var selected = org.unlaxer.dsl.bootstrap.PackagedLanguageProfile.load(manifest, packageId);
        var readiness = PortabilityCheck.check(org.unlaxer.dsl.bootstrap.UBNFMapper.parseWithSource(selected.source()), selected.ast());
        if (!readiness.portable()) throw new IllegalArgumentException("Playground requires a Rust-portable grammar: " + readiness.diagnostics());
        Map<String, String> files = new LinkedHashMap<>(generate(selected.source(), selected.ast(), selected.vocabulary().toString() + "\n"));
        files.put("public/profile.tsv", selected.profile().canonicalTsv());
        files.put("public/package.json", selected.identity().toString() + "\n");
        files.put("public/index.html", files.get("public/index.html").replace("<body>", "<body data-profile=\"profile.tsv\">"));
        return java.util.Collections.unmodifiableMap(files);
    }

    public static Map<String, String> generate(Path path) throws IOException {
        String source = Files.readString(path);
        var readiness = PortabilityCheck.checkFile(path);
        if (!readiness.portable()) throw new IllegalArgumentException("Playground requires a Rust-portable grammar: " + readiness.diagnostics());
        UBNFAST.UBNFFile file;
        try { file = UBNFModuleLoader.load(path); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot resolve grammar module: " + e.getMessage(), e); }
        return generate(source, file, org.unlaxer.dsl.tooling.VocabularyOrigins.inspect(path).toString() + "\n");
    }

    private static Map<String, String> generate(String source, UBNFAST.UBNFFile file, String vocabulary) {
        var grammar = file.grammars().get(0);
        for (var token : grammar.tokens()) if (!(token instanceof UBNFAST.TokenDecl.Declarative)) {
            throw new IllegalArgumentException("Playground requires declarative token " + token.name() + " ::= expression; (host bindings are not executed)");
        }
        var ir = RustGrammarLowering.lower(grammar);
        Map<String, String> files = new LinkedHashMap<>();
        for (var generated : new RustBackend().generate(grammar)) files.put("src/generated/" + generated.relativePath(), generated.content());
        for (String name : new String[] {"Cargo.toml", "package.json", "build.mjs", "serve.mjs", "README.txt"}) {
            files.put(name, resource("playground/" + name));
        }
        files.put(".cargo/config.toml", resource("playground/config.toml"));
        files.put("runtime/Cargo.toml", resource("playground/runtime-Cargo.toml"));
        files.put("runtime/LICENSE", resource("playground/runtime/LICENSE"));
        files.put("runtime/UNICODE-LICENSE.txt", resource("playground/runtime/UNICODE-LICENSE.txt"));
        files.put("public/UNICODE-LICENSE.txt", resource("playground/runtime/UNICODE-LICENSE.txt"));
        for (String name : new String[] {"lib.rs", "scope.rs", "semantic.rs", "editor.rs", "editor_cst.rs", "editor_queries.rs", "language_queries.rs", "semantic_queries.rs", "semantic_project.rs", "semantic_rename.rs", "source_edits.rs", "source.rs", "shared_calls.rs", "language_profile.rs", "embedded.rs", "provider_protocol.rs", "provider_process.rs", "pipeline.rs", "lexing.rs", "first.rs", "lexical.rs", "long_code_fence.rs", "memo_retention_tests.rs", "semantic_query_cache.rs", "type_system.rs", "call_inference.rs", "semantic_rules.rs", "token.rs", "names.rs", "unicode_xid.rs"}) {
            files.put("runtime/src/" + name, resource("playground/runtime/src/" + name));
        }
        files.put("src/lib.rs", resource("playground/lib.rs")
            .replace("@@DOCS@@", ir.rules().stream().anyMatch(rule -> !rule.documentation().isEmpty()) ? "generated::parser::RULE_DOCS" : "&[]")
            .replace("@@NAME@@", RustBackend.quote(grammar.name())).replace("@@ROOT@@", Integer.toString(ir.root())));
        files.put("src/editor_adapter.rs", resource("playground/editor_adapter.rs"));
        files.put("src/region_adapter.rs", resource("playground/region_adapter.rs"));
        files.put("src/query_adapter.rs", resource("playground/query_adapter.rs"));
        for (String name : new String[] {"index.html", "playground.css", "playground.js", "worker.js"}) {
            files.put("public/" + name, resource("playground/" + name));
        }
        for (String name : new String[] {"index.html", "help.css", "help.js", "catalog.json"}) {
            files.put("public/help/" + name, resource("ubnf-help/" + name));
        }
        files.put("public/grammar.ubnf", source);
        files.put("public/vocabulary.json", vocabulary);
        return java.util.Collections.unmodifiableMap(files);
    }

    private static String resource(String path) {
        try (var input = PlaygroundGenerator.class.getResourceAsStream("/" + path)) {
            if (input == null) throw new IllegalStateException("Missing playground resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot read playground resource: " + path, e); }
    }
}
