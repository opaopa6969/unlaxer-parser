package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

/** Independent shared observations for name-dependent runtime selection, not a generated C++ grammar. */
public class NameSnapshotConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private static final NameSnapshot.Requirement REQUIREMENT = new NameSnapshot.Requirement("cxx23", "v1");

    @Test public void immutableNamesAndFatalUnknownAgreeAcrossHosts() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        var rows = JsonParser.parseString(Files.readString(repo.resolve("spec-corpus/name-snapshots/runtime.json"))).getAsJsonArray();
        Path lib = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", lib.toString()));
        Path probe = temporary.getRoot().toPath().resolve("probe.rs");
        Files.writeString(probe, rustProbe(rows));
        Path executable = temporary.getRoot().toPath().resolve("probe");
        run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + lib, probe.toString(), "-o", executable.toString()));
        var observations = run(List.of(executable.toString())).lines().map(JsonParser::parseString).toList();
        var report = new ArrayList<>(List.of("case\tmemo\tdiagnostics\texpected\tjava\trust"));
        int index = 0;
        for (var rowValue : rows) {
            var row = rowValue.getAsJsonObject();
            for (Memoization memo : Memoization.values()) for (var diagnostics : List.of(
                    ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE)) {
                Map<String, List<String>> bindings = new HashMap<>();
                row.getAsJsonObject("bindings").entrySet().forEach(entry -> bindings.put(entry.getKey(),
                    new ArrayList<>(entry.getValue().getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList())));
                String input = row.get("input").getAsString(), lexeme = row.get("lexeme").getAsString();
                Parser name1 = new WordParser(lexeme), name2 = new WordParser(lexeme);
                Parser declaration = predicate(new Chain(name1, new WordParser("(a);")), name1, "type");
                Parser expression = predicate(new Chain(name2, new Choice(new WordParser("(a);"), new WordParser("(a)++;"))), name2, "resolved");
                var choice = new Choice(declaration, expression, new WordParser(input));
                var scope = new NameResolutionScope(choice, List.of(REQUIREMENT));
                try (var context = ParseContext.withBindings(StringSource.createRootSource(input), bindings,
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                    bindings.values().forEach(List::clear); bindings.clear();
                    int count = row.getAsJsonObject("expected").get("accepted").getAsBoolean() ? 1 : 2;
                    for (int retry = 0; retry < count; retry++) {
                        var parsed = scope.parse(context);
                        var actual = new JsonObject();
                        actual.addProperty("accepted", parsed.isSucceeded());
                        actual.addProperty("cursor", context.position()); actual.addProperty("matched", context.matchedPosition());
                        String selected = !parsed.isSucceeded() ? "none" : context.getChosen(choice).orElseThrow() == declaration ? "declaration" : "expression";
                        actual.addProperty("selection", selected);
                        var failure = NameResolution.failure(context);
                        actual.addProperty("kind", failure.map(NameResolution.Failure::kind).orElse("none"));
                        JsonElement span = JsonNull.INSTANCE;
                        if (failure.isPresent()) { var pair = new JsonArray(); pair.add(failure.get().start()); pair.add(failure.get().end()); span = pair; }
                        actual.add("span", span);
                        actual.add("cst", parsed.isSucceeded() ? new JsonPrimitive(parsed.getConsumed().source.sourceAsString()) : JsonNull.INSTANCE);
                        if (!parsed.isSucceeded()) { assertTrue(context.getCurrent().getTokens().isEmpty()); assertTrue(context.getChosen(choice).isEmpty()); }
                        var expected = row.getAsJsonObject("expected");
                        String label = row.get("name") + "/" + memo + "/" + diagnostics + "/" + retry;
                        assertEquals(label + " Java", expected, actual);
                        assertEquals(label + " Rust", expected, observations.get(index));
                        report.add(label + "\t" + memo + "\t" + diagnostics + "\t" + expected + "\t" + actual + "\t" + observations.get(index++));
                    }
                }
            }
        }
        assertEquals(index, observations.size());
        Files.write(Path.of("target/rust-name-snapshots.tsv"), report);
    }
    private Parser predicate(Parser child, Parser site, String kind) {
        return new NamePredicateParser(child, "cxx23", "v1", kind) {
            private static final long serialVersionUID = 1L;
            @Override protected List<Token> nameCaptureSites(Token root) {
                List<Token> sites = new ArrayList<>(); NameSnapshotConformanceTest.this.collect(root, site, sites); return sites;
            }
        };
    }
    private void collect(Token token, Parser site, List<Token> sites) {
        if (token.parser == site) sites.add(token);
        for (var child : token.filteredChildren) collect(child, site, sites);
    }
    private String owned(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return "String::from_utf8(vec![" + java.util.stream.IntStream.range(0, bytes.length)
            .mapToObj(i -> Integer.toString(Byte.toUnsignedInt(bytes[i]))).collect(java.util.stream.Collectors.joining(",")) + "]).unwrap()";
    }
    private String rustProbe(JsonArray rows) {
        var calls = new StringBuilder();
        for (var rowValue : rows) {
            var row = rowValue.getAsJsonObject();
            calls.append("{let input=").append(owned(row.get("input").getAsString())).append(";let lexeme=Box::leak(")
                .append(owned(row.get("lexeme").getAsString())).append(".into_boxed_str());let mut bindings=BTreeMap::new();\n");
            for (var entry : row.getAsJsonObject("bindings").entrySet()) {
                calls.append("bindings.insert(").append(owned(entry.getKey())).append(",vec![");
                for (var value : entry.getValue().getAsJsonArray()) calls.append(owned(value.getAsString())).append(',');
                calls.append("]);\n");
            }
            calls.append("observe(&input,lexeme,bindings,").append(row.getAsJsonObject("expected").get("accepted").getAsBoolean() ? "1" : "2").append(");}\n");
        }
        return """
            use std::collections::BTreeMap;
            use unlaxer_runtime::{Expr, ParseContext, ParseOptions, Memoization, Diagnostics, json_string};
            use unlaxer_runtime::names::Requirement;
            fn gate(child: Expr, kind: &'static str) -> Expr {
                Expr::NamePredicate { child:Box::new(child), snapshot:"cxx23", version:"v1", capture:"name", kind }
            }
            fn observe(input:&str, lexeme:&'static str, bindings:BTreeMap<String,Vec<String>>, count:usize) {
                for memo in [Memoization::Off,Memoization::SafeFailures] {
                    for diagnostics in [Diagnostics::Detailed,Diagnostics::DetailedOnFailure] {
                        let name=|| Expr::Literal(lexeme).capture("name");
                        let declaration=gate(Expr::sequence([name(),Expr::Literal("(a);")]),"type").capture("declaration");
                        let expression=gate(Expr::sequence([name(),Expr::choice([Expr::Literal("(a);"),Expr::Literal("(a)++;")])]),"resolved").capture("expression");
                        let fallback=Expr::Literal(Box::leak(input.to_owned().into_boxed_str()));
                        let scope=Expr::NameResolutionScope { child:Box::new(Expr::choice([declaration,expression,fallback])), requirements:vec![Requirement::new("cxx23","v1").unwrap()] };
                        let mut context=ParseContext::with_bindings(input,bindings.clone(),ParseOptions::with_memoization(memo).with_diagnostics(diagnostics));
                        for _ in 0..count {
                            let result=context.parse(&scope);
                            let selected=if let Ok(ref parsed)=result { if parsed.captures.iter().any(|capture| capture.name=="declaration") {"declaration"} else {"expression"} } else {"none"};
                            let (kind,span)=context.name_failure().map(|failure|(failure.kind,format!("[{},{}]",failure.span.start,failure.span.end))).unwrap_or(("none","null".into()));
                            let cst=if let Ok(ref parsed)=result { json_string(context.text(parsed.span).unwrap()) } else { assert!(context.captured("name").is_none()); "null".into() };
                            println!(r#"{{"accepted":{},"cursor":{},"matched":{},"selection":{},"kind":{},"span":{},"cst":{}}}"#,result.is_ok(),context.position(),context.matched_position(),json_string(selected),json_string(kind),span,cst);
                        }
                    }
                }
            }
            fn main() {
            """ + calls + "}\n";
    }
    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            process.getOutputStream().close();
            assertTrue(command.toString(), process.waitFor(60, TimeUnit.SECONDS));
            String output = Files.readString(log); assertEquals(output, 0, process.exitValue()); return output;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
