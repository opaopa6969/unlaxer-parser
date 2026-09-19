package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.CodePointLength;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.dsl.runtime.ScopeStore.ReferenceInfo;
import org.unlaxer.dsl.runtime.ScopeStore.Severity;
import org.unlaxer.dsl.runtime.ScopeStore.SymbolDiagnostic;
import org.unlaxer.dsl.runtime.ScopeStore.SymbolInfo;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.EmptyParser;

/** Shared operation corpus for transactional Java and Rust scope stores. */
public class ScopeStoreConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void transactionsRestoreScopeStateAndBothCursors() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)",
            Boolean.getBoolean("rustConformance"));

        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/scope-store");
        JsonArray corpus = JsonParser.parseString(
            Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();

        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib",
            "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
            "-o", runtime.toString()), ""));
        Path probe = temporary.getRoot().toPath().resolve("probe.rs");
        Files.writeString(probe, rustProbe(corpus));
        Path executable = temporary.getRoot().toPath().resolve("probe");
        success(run(List.of("rustc", "--edition=2021", "--extern",
            "unlaxer_runtime=" + runtime, probe.toString(), "-o", executable.toString()), ""));
        ProcessResult rustResult = run(List.of(executable.toString()), "");
        success(rustResult);
        List<String> rustLines = rustResult.output().lines().toList();

        var expected = new ArrayList<JsonObject>();
        var java = new ArrayList<JsonObject>();
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String fixtureName = fixture.get("name").getAsString();
            String source = fixture.get("source").getAsString();
            for (JsonElement runElement : fixture.getAsJsonArray("runs")) {
                JsonObject run = runElement.getAsJsonObject();
                String runName = run.get("id").getAsString();
                var retained = new TreeMap<String, List<SymbolInfo>>();
                try (var context = new ParseContext(StringSource.createRootSource(source))) {
                    executeJava(fixtureName, runName, run.getAsJsonArray("operations"), context,
                        retained, expected, java);
                }
            }
        }

        assertEquals("Rust observation count", expected.size(), rustLines.size());
        var rust = rustLines.stream()
            .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        for (int i = 0; i < expected.size(); i++) {
            JsonObject oracle = expected.get(i);
            String label = observationLabel(oracle);
            assertEquals(label + " Rust state/cursors", oracle, rust.get(i));
        }
        var report = new ArrayList<>(List.of("fixture\trun\tobserve\texpected\tjava\trust"));
        for (int i = 0; i < expected.size(); i++) {
            JsonObject oracle = expected.get(i);
            String label = observationLabel(oracle);
            assertEquals(label + " Java state/cursors", oracle, java.get(i));
            report.add(oracle.get("fixture").getAsString() + "\t" + oracle.get("run").getAsString()
                + "\t" + oracle.get("observe").getAsString() + "\t" + oracle.get("state")
                + "\t" + java.get(i).get("state") + "\t" + rust.get(i).get("state"));
        }
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-scope-store.tsv"), report, StandardCharsets.UTF_8);
    }

    private String observationLabel(JsonObject observation) {
        return observation.get("fixture").getAsString() + "/"
            + observation.get("run").getAsString() + "/"
            + observation.get("observe").getAsString();
    }

    private void executeJava(String fixture, String run, JsonArray operations, ParseContext context,
            Map<String, List<SymbolInfo>> retained, List<JsonObject> expected,
            List<JsonObject> actual) {
        for (JsonElement element : operations) {
            JsonObject operation = element.getAsJsonObject();
            switch (operation.get("op").getAsString()) {
                case "advance" -> {
                    int points = operation.get("points").getAsInt();
                    assertTrue(fixture + " advance exceeds source", context.getConsumedPosition().value()
                        + points <= context.getSource().codePointLength().value());
                    context.consume(new CodePointLength(points));
                }
                case "enter" -> ScopeStore.enter(context);
                case "leave" -> ScopeStore.leave(context);
                case "declare" -> ScopeStore.declare(context,
                    operation.get("name").getAsString(), operation.get("offset").getAsInt());
                case "addReference" -> ScopeStore.addReference(context,
                    operation.get("name").getAsString(), operation.get("offset").getAsInt(),
                    operation.get("length").getAsInt());
                case "addDiagnostic" -> ScopeStore.addDiagnostic(context,
                    operation.get("message").getAsString(), operation.get("offset").getAsInt(),
                    operation.get("length").getAsInt(),
                    Severity.valueOf(operation.get("severity").getAsString()));
                case "clearDiagnostics" -> ScopeStore.clearDiagnostics(context);
                case "retainCurrent" -> retained.put(operation.get("slot").getAsString(),
                    List.copyOf(sortedSymbols(ScopeStore.declaredInCurrentScope(context))));
                case "observe" -> {
                    JsonObject oracle = envelope(fixture, run, operation.get("id").getAsString(),
                        operation.getAsJsonObject("expected"));
                    expected.add(oracle);
                    actual.add(envelope(fixture, run, operation.get("id").getAsString(),
                        javaState(context, operation.getAsJsonArray("names"), retained)));
                }
                case "transaction" -> {
                    Parser parser = new EmptyParser();
                    context.begin(parser);
                    executeJava(fixture, run, operation.getAsJsonArray("operations"), context,
                        retained, expected, actual);
                    if (operation.get("result").getAsString().equals("commit")) {
                        context.commit(parser, TokenKind.consumed);
                    } else {
                        context.rollback(parser);
                    }
                }
                default -> fail("unknown operation: " + operation);
            }
        }
    }

    private JsonObject javaState(ParseContext context, JsonArray names,
            Map<String, List<SymbolInfo>> retained) {
        var state = new JsonObject();
        state.addProperty("depth", ScopeStore.currentScopeDepth(context));
        var cursor = new JsonArray();
        cursor.add(context.getConsumedPosition().value());
        cursor.add(context.getMatchedPosition().value());
        state.add("cursor", cursor);
        var resolved = new JsonArray();
        for (JsonElement nameElement : names) {
            String name = nameElement.getAsString();
            var item = new JsonObject();
            item.addProperty("query", name);
            item.addProperty("declared", ScopeStore.isDeclared(context, name));
            item.add("symbol", ScopeStore.resolve(context, name)
                .<JsonElement>map(this::symbol).orElse(com.google.gson.JsonNull.INSTANCE));
            resolved.add(item);
        }
        state.add("resolved", resolved);
        state.add("declaredCurrent", symbols(sortedSymbols(ScopeStore.declaredInCurrentScope(context))));
        state.add("allDeclarations", symbols(ScopeStore.getAllDeclarations(context)));
        state.add("references", references(ScopeStore.getAllReferences(context)));
        state.add("diagnostics", diagnostics(ScopeStore.getDiagnostics(context)));
        var retainedJson = new JsonObject();
        retained.forEach((slot, values) -> retainedJson.add(slot, symbols(values)));
        state.add("retained", retainedJson);
        return state;
    }

    private List<SymbolInfo> sortedSymbols(List<SymbolInfo> values) {
        return values.stream().sorted(Comparator.comparing(SymbolInfo::name)
            .thenComparingInt(SymbolInfo::sourceOffset)).toList();
    }

    private JsonArray symbols(List<SymbolInfo> values) {
        var result = new JsonArray();
        values.forEach(value -> result.add(symbol(value)));
        return result;
    }

    private JsonObject symbol(SymbolInfo value) {
        var result = new JsonObject();
        result.addProperty("name", value.name());
        result.addProperty("sourceOffset", value.sourceOffset());
        return result;
    }

    private JsonArray references(List<ReferenceInfo> values) {
        var result = new JsonArray();
        for (ReferenceInfo value : values) {
            var item = new JsonObject();
            item.addProperty("name", value.name());
            item.addProperty("offset", value.offset());
            item.addProperty("length", value.length());
            result.add(item);
        }
        return result;
    }

    private JsonArray diagnostics(List<SymbolDiagnostic> values) {
        var result = new JsonArray();
        for (SymbolDiagnostic value : values) {
            var item = new JsonObject();
            item.addProperty("message", value.message());
            item.addProperty("offset", value.offset());
            item.addProperty("length", value.length());
            item.addProperty("severity", value.severity().name());
            result.add(item);
        }
        return result;
    }

    private JsonObject envelope(String fixture, String run, String observe, JsonObject state) {
        var result = new JsonObject();
        result.addProperty("fixture", fixture);
        result.addProperty("run", run);
        result.addProperty("observe", observe);
        result.add("state", state);
        return result;
    }

    private String rustProbe(JsonArray corpus) {
        var out = new StringBuilder("""
            use std::collections::BTreeMap;
            use unlaxer_runtime::{json_string, ParseContext, ParseError, ReferenceInfo, Severity,
                SymbolDiagnostic, SymbolInfo};

            fn symbol(value: &SymbolInfo) -> String {
                format!(r#"{{\"name\":{},\"sourceOffset\":{}}}"#, json_string(&value.name), value.source_offset)
            }
            fn symbols(values: &[SymbolInfo], sorted: bool) -> String {
                let mut values = values.to_vec();
                if sorted { values.sort_by(|a, b| a.name.cmp(&b.name).then(a.source_offset.cmp(&b.source_offset))); }
                format!("[{}]", values.iter().map(symbol).collect::<Vec<_>>().join(","))
            }
            fn references(values: &[ReferenceInfo]) -> String {
                format!("[{}]", values.iter().map(|value| format!(
                    r#"{{\"name\":{},\"offset\":{},\"length\":{}}}"#,
                    json_string(&value.name), value.offset, value.length)).collect::<Vec<_>>().join(","))
            }
            fn severity(value: &Severity) -> &'static str {
                match value { Severity::Error => "ERROR", Severity::Warning => "WARNING",
                    Severity::Info => "INFO", Severity::Hint => "HINT" }
            }
            fn diagnostics(values: &[SymbolDiagnostic]) -> String {
                format!("[{}]", values.iter().map(|value| format!(
                    r#"{{\"message\":{},\"offset\":{},\"length\":{},\"severity\":{}}}"#,
                    json_string(&value.message), value.offset, value.length,
                    json_string(severity(&value.severity)))).collect::<Vec<_>>().join(","))
            }
            fn state(context: &ParseContext<'_>, names: &[&str],
                    retained: &BTreeMap<String, Vec<SymbolInfo>>) -> String {
                let scope = context.scopes();
                let resolved = names.iter().map(|name| {
                    let found = scope.resolve(name);
                    format!(r#"{{\"query\":{},\"declared\":{},\"symbol\":{}}}"#,
                        json_string(name), scope.is_declared(name),
                        found.map(symbol).unwrap_or_else(|| "null".to_owned()))
                }).collect::<Vec<_>>().join(",");
                let retained = retained.iter().map(|(slot, values)| format!("{}:{}",
                    json_string(slot), symbols(values, false))).collect::<Vec<_>>().join(",");
                format!(concat!(r#"{{\"depth\":{},\"cursor\":[{},{}],\"resolved\":[{}],"#,
                    r#"\"declaredCurrent\":{},\"allDeclarations\":{},\"references\":{},"#,
                    r#"\"diagnostics\":{},\"retained\":{{{}}}}}"#),
                    scope.current_scope_depth(), context.position(), context.matched_position(), resolved,
                    symbols(&scope.declared_in_current_scope(), true),
                    symbols(scope.all_declarations(), false), references(scope.all_references()),
                    diagnostics(scope.diagnostics()), retained)
            }

            fn main() {
            """);
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixtureObject = fixtureElement.getAsJsonObject();
            String fixture = fixtureObject.get("name").getAsString();
            String source = fixtureObject.get("source").getAsString();
            for (JsonElement runElement : fixtureObject.getAsJsonArray("runs")) {
                JsonObject run = runElement.getAsJsonObject();
                String runName = run.get("id").getAsString();
                out.append("    {\n        let mut context = ParseContext::new(")
                    .append(rustString(source)).append(");\n")
                    .append("        let mut retained: BTreeMap<String, Vec<SymbolInfo>> = BTreeMap::new();\n");
                emitRustOperations(out, fixture, runName, run.getAsJsonArray("operations"), 2);
                out.append("    }\n");
            }
        }
        return out.append("}\n").toString();
    }

    private void emitRustOperations(StringBuilder out, String fixture, String run, JsonArray operations,
            int indentLevel) {
        String indent = "    ".repeat(indentLevel);
        for (JsonElement element : operations) {
            JsonObject operation = element.getAsJsonObject();
            switch (operation.get("op").getAsString()) {
                case "advance" -> out.append(indent).append("assert!(context.advance(")
                    .append(operation.get("points").getAsInt()).append("));\n");
                case "enter" -> out.append(indent).append("context.scopes_mut().enter();\n");
                case "leave" -> out.append(indent).append("context.scopes_mut().leave();\n");
                case "declare" -> out.append(indent).append("context.scopes_mut().declare(")
                    .append(rustString(operation.get("name").getAsString())).append(", ")
                    .append(operation.get("offset").getAsInt()).append(");\n");
                case "addReference" -> out.append(indent)
                    .append("context.scopes_mut().add_reference(")
                    .append(rustString(operation.get("name").getAsString())).append(", ")
                    .append(operation.get("offset").getAsInt()).append(", ")
                    .append(operation.get("length").getAsInt()).append(");\n");
                case "addDiagnostic" -> out.append(indent)
                    .append("context.scopes_mut().add_diagnostic(")
                    .append(rustString(operation.get("message").getAsString())).append(", ")
                    .append(operation.get("offset").getAsInt()).append(", ")
                    .append(operation.get("length").getAsInt()).append(", Severity::")
                    .append(rustSeverity(operation.get("severity").getAsString())).append(");\n");
                case "clearDiagnostics" -> out.append(indent)
                    .append("context.scopes_mut().clear_diagnostics();\n");
                case "retainCurrent" -> out.append(indent).append("retained.insert(")
                    .append(rustString(operation.get("slot").getAsString()))
                    .append(".to_owned(), { let mut values = context.scopes().declared_in_current_scope(); ")
                    .append("values.sort_by(|a, b| a.name.cmp(&b.name).then(a.source_offset.cmp(&b.source_offset))); values });\n");
                case "observe" -> {
                    String names = String.join(", ", operation.getAsJsonArray("names").asList().stream()
                        .map(name -> rustString(name.getAsString())).toList());
                    out.append(indent).append("println!(\"{{\\\"fixture\\\":{},\\\"run\\\":{},")
                        .append("\\\"observe\\\":{},\\\"state\\\":{}}}\", json_string(")
                        .append(rustString(fixture)).append("), json_string(")
                        .append(rustString(run)).append("), json_string(")
                        .append(rustString(operation.get("id").getAsString())).append("), state(&context, &[")
                        .append(names).append("], &retained));\n");
                }
                case "transaction" -> {
                    out.append(indent).append("let _: Result<(), ParseError> = context.transaction(|context| {\n");
                    emitRustOperations(out, fixture, run, operation.getAsJsonArray("operations"), indentLevel + 1);
                    if (operation.get("result").getAsString().equals("commit")) {
                        out.append(indent).append("    Ok(())\n");
                    } else {
                        out.append(indent).append("    Err(context.error(\"fixture rollback\"))\n");
                    }
                    out.append(indent).append("});\n");
                }
                default -> throw new AssertionError("unknown operation: " + operation);
            }
        }
    }

    private String rustSeverity(String severity) {
        return severity.substring(0, 1) + severity.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private String rustString(String value) {
        return new JsonPrimitive(value).toString();
    }

    private record ProcessResult(int code, String output) {}

    private ProcessResult run(List<String> command, String input) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                terminateProcessTree(process);
                fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) terminateProcessTree(process);
        }
    }

    private void terminateProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroy();
        process.destroy();
        process.waitFor(2, TimeUnit.SECONDS);
        for (int i = descendants.size() - 1; i >= 0; i--) {
            ProcessHandle descendant = descendants.get(i);
            if (descendant.isAlive()) descendant.destroyForcibly();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private void success(ProcessResult result) {
        assertEquals(result.output(), 0, result.code());
    }
}
