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

    public static Map<String, String> generate(Path path) throws IOException {
        String source = Files.readString(path);
        var readiness = PortabilityCheck.checkFile(path);
        if (!readiness.portable()) throw new IllegalArgumentException("Playground requires a Rust-portable grammar: " + readiness.diagnostics());
        UBNFAST.UBNFFile file;
        try { file = UBNFModuleLoader.load(path); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot resolve grammar module: " + e.getMessage(), e); }
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
        for (String name : new String[] {"lib.rs", "scope.rs", "semantic.rs", "source.rs", "semantic_project.rs", "lexing.rs", "first.rs", "lexical.rs", "long_code_fence.rs", "memo_retention_tests.rs"}) {
            files.put("runtime/src/" + name, resource("playground/runtime/src/" + name));
        }
        files.put("src/lib.rs", resource("playground/lib.rs")
            .replace("@@DOCS@@", ir.rules().stream().anyMatch(rule -> !rule.documentation().isEmpty()) ? "generated::parser::RULE_DOCS" : "&[]")
            .replace("@@NAME@@", RustBackend.quote(grammar.name())).replace("@@ROOT@@", Integer.toString(ir.root())));
        for (String name : new String[] {"index.html", "playground.css", "playground.js", "worker.js"}) {
            files.put("public/" + name, resource("playground/" + name));
        }
        for (String name : new String[] {"index.html", "help.css", "help.js", "catalog.json"}) {
            files.put("public/help/" + name, resource("ubnf-help/" + name));
        }
        files.put("public/grammar.ubnf", source);
        return java.util.Collections.unmodifiableMap(files);
    }

    private static String resource(String path) {
        try (var input = PlaygroundGenerator.class.getResourceAsStream("/" + path)) {
            if (input == null) throw new IllegalStateException("Missing playground resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot read playground resource: " + path, e); }
    }
}
