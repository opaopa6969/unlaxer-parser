package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Opt-in locally, mandatory in Rust CI. Runs both implementations; no mocked compiler failures. */
public class RustConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String PACKAGE = "org.example.evolution.";
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void primitiveTokensPreserveCapturesAndCodePointSpans() throws Exception {
        tokenCorpus("primitives");
    }

    @Test public void lexicalTokensPreserveRawTextAndCodePointSpans() throws Exception {
        tokenCorpus("lexical");
    }

    @Test public void mixedValueFixturesPreserveJavaObjectFieldsAndValues() throws Exception {
        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/mixed-values");
        String source = Files.readString(fixtures.resolve("Mixed.ubnf"));
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        for (String mode : List.of("two", "one")) {
        var grammar = UBNFMapper.parse(mode.equals("one") ? source.replace(" | OtherRule", "") : source).grammars().get(0);
        try (var loader = compileJava(grammar, List.of())) {
            var mapper = loader.loadClass("org.example.mixed.MixedMapper");
            var root = loader.loadClass("org.example.mixed.MixedAST$Root");
            assertEquals(Object.class, root.getMethod("head").getReturnType());
            assertEquals("java.util.Optional<java.lang.Object>", root.getMethod("maybe").getGenericReturnType().getTypeName());
            assertEquals("java.util.List<java.lang.Object>", root.getMethod("items").getGenericReturnType().getTypeName());
            for (var entry : corpus) {
                var row = entry.getAsJsonObject();
                String input = row.get("input").getAsString();
                var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                boolean accepted = mixedAccepted(row, mode);
                assertEquals(mode + " " + row, accepted, diagnostic.isEmpty());
                if (accepted) {
                    Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                    JsonObject ast = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                    assertEquals(row.toString(), row.get("value").getAsString(), mixedValueOracle(ast));
                    for (var item : row.getAsJsonArray("texts")) {
                        var text = item.getAsJsonArray();
                        String raw = input.substring(input.offsetByCodePoints(0, text.get(0).getAsInt()),
                            input.offsetByCodePoints(0, text.get(1).getAsInt())).strip();
                        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) raw = raw.substring(1, raw.length() - 1);
                        assertEquals(row + " independent text span fixture", text.get(2).getAsString(), raw);
                    }
                }
            }
        }
        }
    }

    @Test public void mixedTextAndNodesPreserveCardinalityValuesCursorsAndSpans() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/mixed-values");
        String source = Files.readString(fixtures.resolve("Mixed.ubnf"));
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of("mode\tinput_json\tjava_prefix\tjava_ast\trust"));
        for (String mode : List.of("two", "one")) {
        var grammar = UBNFMapper.parse(mode.equals("one") ? source.replace(" | OtherRule", "") : source).grammars().get(0);
        Path dir = temporary.newFolder().toPath();
        Path generated = Files.createDirectory(dir.resolve("generated"));
        for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
        Files.writeString(dir.resolve("main.rs"), Files.readString(fixtures.resolve("probe.rs.txt")));
        success(rustCompile(dir, library));
        var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", corpus.asList().stream()
            .map(row -> java.util.HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString()
                .getBytes(StandardCharsets.UTF_8))).toList()) + "\n");
        success(actual);
        var lines = actual.output().lines().toList();
        assertEquals(corpus.size(), lines.size());
        try (var loader = compileJava(grammar, List.of())) {
            var mapper = loader.loadClass("org.example.mixed.MixedMapper");
            var parser = (org.unlaxer.parser.Parser) loader.loadClass("org.example.mixed.MixedParsers")
                .getMethod("getRootParser").invoke(null);
            for (int i = 0; i < corpus.size(); i++) {
                var row = corpus.get(i).getAsJsonObject();
                String input = row.get("input").getAsString();
                var result = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                var prefix = new JsonArray();
                try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                    prefix.add(parser.parse(context).isSucceeded());
                    prefix.add(context.getConsumedPosition().value());
                    prefix.add(context.getMatchedPosition().value());
                }
                assertEquals(row + " prefix cursors", prefix, result.get("prefix"));
                var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                boolean accepted = mixedAccepted(row, mode);
                assertEquals(mode + " " + row + " Java acceptance", accepted, diagnostic.isEmpty());
                assertEquals(mode + " " + row + " Rust acceptance", accepted, !result.get("ast").isJsonNull());
                JsonElement java = JsonNull.INSTANCE;
                if (accepted) {
                    Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                    java = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                    assertEquals(row + " all fields/node spans", java, result.get("ast"));
                    assertEquals(row + " independent Java value", row.get("value").getAsString(), mixedValueOracle(java));
                    assertEquals(row + " Rust semantics after CST/input drop", row.get("value"), result.get("value"));
                    assertEquals(row + " independent text code-point spans", row.get("texts"), result.get("texts"));
                }
                report.add(mode + "\t" + row.get("input") + "\t" + prefix + "\t" + java + "\t" + result);
            }
        }
        Files.writeString(dir.resolve("main.rs"), """
            mod generated;
            struct Stale;
            impl generated::evaluator::Semantics for Stale { type Output = (); }
            fn main() {}
            """);
        var stale = rustCompile(dir, library);
        assertNotEquals(stale.output(), 0, stale.code());
        assertTrue(stale.output(), stale.output().contains("E0046"));
        assertTrue(stale.output(), stale.output().contains("eval_root"));
        }
        Files.write(Path.of("target/rust-mixed-values.tsv"), report, StandardCharsets.UTF_8);
    }

    private boolean mixedAccepted(JsonObject row, String mode) {
        return row.has("value") && (!row.has("modes") || row.getAsJsonArray("modes").contains(new JsonPrimitive(mode)));
    }

    private String mixedValueOracle(JsonElement value) {
        if (value.isJsonNull()) return "-";
        if (value.isJsonPrimitive()) return "T:" + value.getAsString();
        var ast = value.getAsJsonObject();
        var fields = ast.getAsJsonObject("fields");
        return switch (ast.get("type").getAsString()) {
            case "Leaf" -> "L:" + fields.get("value").getAsString();
            case "OtherLeaf" -> "O:" + fields.get("value").getAsString();
            case "Root" -> mixedValueOracle(fields.get("head")) + "|" + mixedValueOracle(fields.get("maybe"))
                + "|" + String.join(",", fields.getAsJsonArray("items").asList().stream().map(this::mixedValueOracle).toList());
            default -> throw new AssertionError("Unexpected mixed value: " + value);
        };
    }

    @Test public void rightAssociativeRecursionPreservesValuesCursorsAndAllSpans() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/right-associative");
        String source = Files.readString(fixtures.resolve("Power.ubnf"));
        String probeTemplate = Files.readString(fixtures.resolve("probe.rs.txt"));
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of("mode\tinput_json\tjava_prefix\tjava_ast\trust"));
        class SpanVerifier {
            void verify(JsonObject ast, String input, int parentStart, int parentEnd) {
                int start = ast.getAsJsonArray("span").get(0).getAsInt();
                int end = ast.getAsJsonArray("span").get(1).getAsInt();
                assertTrue(ast.toString(), parentStart <= start && start <= end && end <= parentEnd);
                var fields = ast.getAsJsonObject("fields");
                if (ast.get("type").getAsString().equals("Number")) {
                    String slice = input.substring(input.offsetByCodePoints(0, start), input.offsetByCodePoints(0, end));
                    assertTrue(ast + " vs " + slice, slice.contains(fields.get("value").getAsString()));
                } else {
                    assertEquals("Power", ast.get("type").getAsString());
                    var left = fields.get("left");
                    if (left.isJsonObject()) verify(left.getAsJsonObject(), input, start, end);
                    assertTrue(ast.toString(), fields.getAsJsonArray("op").size() <= 1);
                    assertEquals(fields.getAsJsonArray("op").size(), fields.getAsJsonArray("right").size());
                    for (var right : fields.getAsJsonArray("right")) verify(right.getAsJsonObject(), input, start, end);
                }
            }
        }
        for (String mode : List.of("text", "mapped", "unicode", "grouped")) {
            String grammarSource = source;
            if (!mode.equals("text")) {
                grammarSource = grammarSource.replace("Atom ::= NUMBER;",
                    "@mapping(Number, params=[value]) Atom ::= "
                    + (mode.equals("unicode") ? "[ '😀' ] " : "") + "(NUMBER) @value;");
            }
            if (mode.equals("grouped")) {
                grammarSource = grammarSource.replace("Expr ::= Atom @left", "Expr ::= Base @left")
                    .replace("  @root", "  Base ::= Atom | '(' Expr ')';\n  @root");
            }
            var grammar = UBNFMapper.parse(grammarSource).grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            String probe = probeTemplate.replace("LEFT_TYPE", mode.equals("text") ? "&str" : "&Ast")
                .replace("LEFT_VALUE", mode.equals("text") ? "number(left)" : "evaluate(left, self)")
                .replace("// NUMBER_SEMANTICS", mode.equals("text") ? ""
                    : "fn eval_number(&mut self, value: &str, _: Span) -> f64 { number(value) }");
            Files.writeString(dir.resolve("main.rs"), probe);
            success(rustCompile(dir, library));
            var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", corpus.asList().stream()
                .map(row -> java.util.HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n");
            success(actual);
            var lines = actual.output().lines().toList();
            assertEquals(corpus.size(), lines.size());
            // Independent values distinguish right nesting (512) from a left fold (64).
            for (int i = 0; i < corpus.size(); i++) {
                var row = corpus.get(i).getAsJsonObject();
                String input = row.get("input").getAsString();
                String context = mode + " " + row;
                boolean accepted = row.has("value") && (!row.has("modes")
                    || row.getAsJsonArray("modes").contains(new JsonPrimitive(mode)));
                var result = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                assertEquals(context, accepted, !result.get("ast").isJsonNull());
                if (accepted) {
                    assertEquals(context, row.get("value").getAsDouble(), result.get("value").getAsDouble(), 0.0);
                    var ast = result.getAsJsonObject("ast");
                    assertEquals(0, ast.getAsJsonArray("span").get(0).getAsInt());
                    assertEquals(input.codePointCount(0, input.length()), ast.getAsJsonArray("span").get(1).getAsInt());
                    new SpanVerifier().verify(ast, input, 0, input.codePointCount(0, input.length()));
                    if (input.equals("2^3^2")) {
                        var second = ast.getAsJsonObject("fields").getAsJsonArray("right").get(0).getAsJsonObject();
                        var third = second.getAsJsonObject("fields").getAsJsonArray("right").get(0).getAsJsonObject();
                        assertEquals(0, third.getAsJsonObject("fields").getAsJsonArray("right").size());
                    }
                }
            }
            try (var loader = compileJava(grammar, List.of())) {
                var mapper = loader.loadClass("org.example.power.PowerMapper");
                var parser = (org.unlaxer.parser.Parser) loader.loadClass("org.example.power.PowerParsers")
                    .getMethod("getRootParser").invoke(null);
                for (int i = 0; i < corpus.size(); i++) {
                    var row = corpus.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = mode + " " + row;
                    var result = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    var prefix = new JsonArray();
                    try (var parseContext = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                        prefix.add(parser.parse(parseContext).isSucceeded());
                        prefix.add(parseContext.getConsumedPosition().value());
                        prefix.add(parseContext.getMatchedPosition().value());
                    }
                    assertEquals(context + " prefix cursors", prefix, result.get("prefix"));
                    boolean accepted = row.has("value") && (!row.has("modes")
                        || row.getAsJsonArray("modes").contains(new JsonPrimitive(mode)));
                    var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(context + " Java acceptance", accepted, diagnostic.isEmpty());
                    JsonElement java = JsonNull.INSTANCE;
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        java = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                        assertEquals(context + " Java/Rust AST and all spans", java, result.get("ast"));
                    }
                    report.add(mode + "\t" + row.get("input") + "\t" + prefix + "\t" + java + "\t" + result);
                }
            }
            Files.writeString(dir.resolve("main.rs"), """
                mod generated;
                struct Stale;
                impl generated::evaluator::Semantics for Stale { type Output = (); }
                fn main() {}
                """);
            var stale = rustCompile(dir, library);
            assertNotEquals(stale.output(), 0, stale.code());
            assertTrue(stale.output(), stale.output().contains("E0046"));
            assertTrue(stale.output(), stale.output().contains("eval_power"));
        }
        Files.write(Path.of("target/rust-right-associative.tsv"), report, StandardCharsets.UTF_8);
    }

    @Test public void sharedLeftAssociativeVariantsPreserveOperatorsEvaluationAndSpans() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/associative");
        String source = Files.readString(fixtures.resolve("Operators.ubnf"));
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of("metadata_mode\tinput_json\tjava_ast\trust"));
        for (boolean reversedMetadata : List.of(false, true)) {
            // Metadata does not turn this layered grammar into a Pratt parser.
            String grammarSource = reversedMetadata ? source.replace("level=10", "level=30") : source;
            var grammar = UBNFMapper.parse(grammarSource).grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            String probe = Files.readString(fixtures.resolve("probe.rs.txt"));
            String metadataAssertions = reversedMetadata
                ? "assert_eq!(generated::parser::OPERATORS[0].rule, \"Term\"); assert_eq!(generated::parser::OPERATORS[1].precedence, 30);"
                : "assert_eq!(generated::parser::OPERATORS[0].rule, \"Expression\"); assert_eq!(generated::parser::OPERATORS[1].precedence, 20);";
            Files.writeString(dir.resolve("main.rs"), probe.replace("fn main() {", "fn main() {\n" + metadataAssertions));
            success(rustCompile(dir, library));
            var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", corpus.asList().stream()
                .map(row -> java.util.HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n");
            success(actual);
            var lines = actual.output().lines().toList();
            assertEquals(corpus.size(), lines.size());
            // Check the Rust oracle before compiling Java: an existing Java bug must not hide it.
            for (int i = 0; i < corpus.size(); i++) {
                var row = corpus.get(i).getAsJsonObject();
                var result = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                boolean accepted = row.has("value");
                assertEquals(row.toString(), accepted, !result.get("ast").isJsonNull());
                if (accepted) {
                    assertEquals(row.toString(), row.get("value").getAsDouble(), result.get("value").getAsDouble(), 0.0);
                    var ast = result.getAsJsonObject("ast");
                    String input = row.get("input").getAsString();
                    assertEquals(0, ast.getAsJsonArray("span").get(0).getAsInt());
                    assertEquals(input.codePointCount(0, input.length()), ast.getAsJsonArray("span").get(1).getAsInt());
                    assertOperatorTree(ast, input, 0, input.codePointCount(0, input.length()));
                    if (input.equals("10-3-2")) {
                        var fields = ast.getAsJsonObject("fields");
                        assertEquals(JsonParser.parseString("[\"-\",\"-\"]"), fields.get("op"));
                        assertEquals(2, fields.getAsJsonArray("right").size());
                    }
                }
            }
            try (var loader = compileJava(grammar, List.of())) {
                var mapper = loader.loadClass("org.example.operators.OperatorsMapper");
                var parser = (org.unlaxer.parser.Parser) loader.loadClass("org.example.operators.OperatorsParsers")
                    .getMethod("getRootParser").invoke(null);
                for (int i = 0; i < corpus.size(); i++) {
                    var row = corpus.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    var result = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    var prefix = new JsonArray();
                    try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                        prefix.add(parser.parse(context).isSucceeded());
                        prefix.add(context.getConsumedPosition().value());
                        prefix.add(context.getMatchedPosition().value());
                    }
                    assertEquals(row + " prefix", prefix, result.get("prefix"));
                    boolean accepted = row.has("value");
                    var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(row + " Java acceptance", accepted, diagnostic.isEmpty());
                    JsonElement java = JsonNull.INSTANCE;
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        java = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                        assertEquals(row + " Java/Rust AST and all spans", java, result.get("ast"));
                    }
                    report.add(reversedMetadata + "\t" + row.get("input") + "\t" + java + "\t" + result);
                }
            }
            Files.writeString(dir.resolve("main.rs"), """
                mod generated;
                struct Stale;
                impl generated::evaluator::Semantics for Stale {
                    type Output = ();
                    fn eval_number(&mut self, _: &str, _: unlaxer_runtime::Span) {}
                }
                fn main() {}
                """);
            var stale = rustCompile(dir, library);
            assertNotEquals(stale.output(), 0, stale.code());
            assertTrue(stale.output(), stale.output().contains("E0046"));
            assertTrue(stale.output(), stale.output().contains("eval_binary"));
        }
        Files.write(Path.of("target/rust-associative.tsv"), report, StandardCharsets.UTF_8);
    }

    private void assertOperatorTree(JsonObject ast, String source, int parentStart, int parentEnd) {
        int start = ast.getAsJsonArray("span").get(0).getAsInt();
        int end = ast.getAsJsonArray("span").get(1).getAsInt();
        assertTrue(ast.toString(), parentStart <= start && start <= end && end <= parentEnd);
        var fields = ast.getAsJsonObject("fields");
        if (ast.get("type").getAsString().equals("Number")) {
            String slice = source.substring(source.offsetByCodePoints(0, start), source.offsetByCodePoints(0, end));
            assertTrue(ast + " vs " + slice, slice.contains(fields.get("value").getAsString()));
        } else {
            assertEquals("Binary", ast.get("type").getAsString());
            assertOperatorTree(fields.getAsJsonObject("left"), source, start, end);
            assertEquals(fields.getAsJsonArray("op").size(), fields.getAsJsonArray("right").size());
            for (var right : fields.getAsJsonArray("right")) assertOperatorTree(right.getAsJsonObject(), source, start, end);
        }
    }

    private void tokenCorpus(String corpusName) throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        var corpus = JsonParser.parseString(Files.readString(repo.resolve("unlaxer-dsl/src/test/resources/" + corpusName + "/corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of("token\tbody\tinput_json\tjava_prefix\tjava_ast\trust"));
        for (var entry : corpus) {
            var fixture = entry.getAsJsonObject();
            String declaration = fixture.get("token").getAsString();
            String whitespace = fixture.has("whitespace") ? "@whitespace: javaStyle" : "";
            var grammar = UBNFMapper.parse("grammar Primitive { @package: org.example.primitive " + whitespace
                + " token T = " + declaration + "\n token END = EOF\n "
                + (fixture.has("extra") ? fixture.get("extra").getAsString() : "")
                + "\n @root @mapping(Value, params=[value]) Root ::= "
                + fixture.get("body").getAsString() + "; }").grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            Files.writeString(dir.resolve("main.rs"), """
                mod generated;
                use std::io::{self, BufRead};
                fn main() {
                    for line in io::stdin().lock().lines() {
                        // Hex framing transports embedded newlines/NUL without changing the input.
                        let encoded = line.unwrap();
                        let bytes = (0..encoded.len()).step_by(2)
                            .map(|i| u8::from_str_radix(&encoded[i..i+2], 16).unwrap()).collect();
                        let input = String::from_utf8(bytes).unwrap();
                        let mut context = unlaxer_runtime::ParseContext::new(&input);
                        let prefix_ok = generated::parser::parse_context(&mut context).is_ok();
                        print!(r#"{{"prefix":[{},{},{}],"ast":"#, prefix_ok, context.position(), context.matched_position());
                        match generated::parser::parse_tree_detailed(&input) {
                            Ok(tree) => {
                                let ast = generated::mapper::map(&tree).unwrap();
                                drop(tree);
                                print!("{}", ast.canonical_json());
                            }
                            Err(_) => print!("null"),
                        }
                        println!("}}");
                    }
                }
                """);
            success(rustCompile(dir, library));
            var cases = fixture.getAsJsonArray("cases");
            var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", cases.asList().stream()
                .map(row -> java.util.HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n");
            success(actual);
            var lines = actual.output().lines().toList();
            assertEquals(declaration, cases.size(), lines.size());
            try (var loader = compileJava(grammar, List.of())) {
                var mapper = loader.loadClass("org.example.primitive.PrimitiveMapper");
                var parser = (org.unlaxer.parser.Parser) loader.loadClass("org.example.primitive.PrimitiveParsers")
                    .getMethod("getRootParser").invoke(null);
                for (int i = 0; i < cases.size(); i++) {
                    var row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = declaration + " / " + fixture.get("body") + " / " + row;
                    var result = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    var rust = result.get("ast");
                    var prefix = new JsonArray();
                    try (var parseContext = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                        boolean prefixOk = parser.parse(parseContext).isSucceeded();
                        prefix.add(prefixOk);
                        prefix.add(parseContext.getConsumedPosition().value());
                        prefix.add(parseContext.getMatchedPosition().value());
                        assertEquals(context + " prefix cursors", prefix, result.get("prefix"));
                        if (row.has("prefix")) assertEquals(context + " prefix oracle", row.get("prefix"), prefix);
                    }
                    boolean accepted = row.has("value");
                    assertEquals(context + " Rust acceptance", accepted, !rust.isJsonNull());
                    var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(context + " Java acceptance", accepted, diagnostic.isEmpty());
                    JsonElement java = JsonNull.INSTANCE;
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        java = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                        var expected = new JsonObject(); expected.addProperty("type", "Value");
                        var span = new JsonArray(); span.add(0); span.add(input.codePointCount(0, input.length()));
                        expected.add("span", span);
                        var fields = new JsonObject(); fields.add("value", row.get("value")); expected.add("fields", fields);
                        assertEquals(context + " Rust oracle", expected, rust);
                        assertEquals(context + " Java oracle", expected, java);
                    }
                    report.add(declaration + "\t" + fixture.get("body") + "\t" + row.get("input") + "\t" + prefix + "\t" + java + "\t" + result);
                }
            }
        }
        Files.write(Path.of("target/rust-" + corpusName + ".tsv"), report, StandardCharsets.UTF_8);
    }

    @Test public void capturePlacementAndTransparentCollectionsAreExecutable() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        record Case(String body, String extra, String input, String field) {}
        String item0 = "{\"type\":\"Item\",\"span\":[0,1],\"fields\":{}}";
        String item1 = "{\"type\":\"Item\",\"span\":[1,2],\"fields\":{}}";
        String itemRule = "@mapping(Item) Item ::= 'x';";
        for (Case example : List.of(
            new Case("[ 'x' @value ]", "", "", "null"),
            new Case("[ 'x' @value ]", "", "x", "\"x\""),
            new Case("('x' @value | 'y')", "", "y", "null"),
            new Case("{ 'x' @value }", "", "xx", "[\"x\",\"x\"]"),
            new Case("'x' @value 'y' @value", "", "xy", "[\"x\",\"y\"]"),
            new Case("{ ('x' @value 'y') }", "", "xyxy", "[\"x\",\"x\"]"),
            new Case("Parts @value", "Parts ::= { Item }; " + itemRule, "xx", "[" + item0 + "," + item1 + "]"),
            new Case("Maybe @value", "Maybe ::= [ Item ]; " + itemRule, "", "null"),
            new Case("Maybe @value", "Maybe ::= [ Item ]; " + itemRule, "x", item0),
            new Case("(Item Item) @value", itemRule, "xx", "[" + item0 + "," + item1 + "]"),
            new Case("('x' 'y') @value", "", "xy", "\"xy\""),
            new Case("'x'{0} @value", "", "", "[]"),
            new Case("'x'{2} @value", "", "xx", "[\"x\",\"x\"]"))) {
            var grammar = UBNFMapper.parse("grammar G { @root @mapping(Case, params=[value]) Root ::= " + example.body() + "; " + example.extra() + " }")
                .grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            Files.writeString(dir.resolve("main.rs"), """
                mod generated;
                use std::io::{self, BufRead};
                fn main() {
                    let input = io::stdin().lock().lines().next().unwrap().unwrap();
                    let tree = generated::parser::parse_tree(&input).unwrap();
                    let ast = generated::mapper::map(&tree).unwrap();
                    drop(tree);
                    println!("{}", ast.canonical_json());
                }
                """);
            success(rustCompile(dir, library));
            var actual = run(List.of(dir.resolve("probe").toString()), example.input() + "\n"); success(actual);
            String expected = "{\"type\":\"Case\",\"span\":[0," + example.input().length() + "],\"fields\":{\"value\":" + example.field() + "}}";
            assertEquals(example.toString(), JsonParser.parseString(expected), JsonParser.parseString(actual.output()));
            if (example.body().equals("[ 'x' @value ]")) {
                Files.writeString(dir.resolve("main.rs"), """
                    mod generated;
                    struct Stale;
                    impl generated::evaluator::Semantics for Stale {
                        type Output = ();
                        fn eval_case(&mut self, _: &str, _: unlaxer_runtime::Span) {}
                    }
                    fn main() {}
                    """);
                var stale = rustCompile(dir, library);
                assertNotEquals(stale.output(), 0, stale.code());
                assertTrue(stale.output(), stale.output().contains("E0053"));
            }
        }
    }

    @Test public void cardinalityPreservesOptionalListsSpansAndEvaluation() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/cardinality");
        String source = Files.readString(fixtures.resolve("Cardinality.ubnf"));
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("mode\tinput_json\tjava\trust"));
        for (String mode : List.of("zero", "plus", "bounded", "unbounded", "separated")) {
            String quantifier = switch (mode) {
                case "zero" -> "{ Item }";
                case "plus" -> "Item+";
                case "bounded" -> "Item{1,2}";
                case "unbounded" -> "Item{1,}";
                default -> "Item % ','";
            };
            var grammar = UBNFMapper.parse(source.replace("{ Item }", quantifier)).grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            Files.writeString(dir.resolve("main.rs"), Files.readString(fixtures.resolve("probe.rs.txt")));
            success(rustCompile(dir, library));
            var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", corpus.asList().stream()
                .map(row -> row.getAsJsonObject().get("input").getAsString()).toList()) + "\n");
            success(actual);
            var lines = actual.output().lines().toList();
            assertEquals(corpus.size(), lines.size());
            try (var loader = compileJava(grammar, List.of())) {
                var mapper = loader.loadClass("org.example.cardinality.CardinalityMapper");
                for (int i = 0; i < corpus.size(); i++) {
                    var row = corpus.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = mode + " " + input;
                    var rust = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    boolean accepted = row.getAsJsonArray("modes").asList().contains(new JsonPrimitive(mode));
                    assertEquals(context, accepted, rust.get("ok").getAsBoolean());
                    var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(context + " Java", accepted, diagnostic.isEmpty());
                    JsonElement java = JsonNull.INSTANCE;
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        java = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                        assertEquals(context + " Rust oracle", cardinalityAst(row.getAsJsonObject("expected"), input), rust.get("ast"));
                        assertEquals(context + " Java oracle", cardinalityAst(row.getAsJsonObject("expected"), input), java);
                        assertEquals(context + " Java/Rust AST and spans", java, rust.get("ast"));
                        assertEquals(context, row.get("value").getAsDouble(), rust.get("value").getAsDouble(), 0.0);
                    }
                    report.add(mode + "\t" + row.get("input") + "\t" + java + "\t" + rust);
                }
            }
        }
        Files.write(Path.of("target/rust-cardinality.tsv"), report, StandardCharsets.UTF_8);
    }

    private JsonObject cardinalityAst(JsonObject expected, String input) {
        var fields = new JsonObject();
        fields.add("head", cardinalityItem(expected.get("head")));
        var items = new JsonArray();
        for (var item : expected.getAsJsonArray("items")) items.add(cardinalityItem(item));
        fields.add("values", items);
        fields.add("tail", expected.get("tail"));
        var flags = new JsonArray();
        expected.get("flags").getAsString().chars().forEach(c -> flags.add(String.valueOf((char) c)));
        fields.add("flags", flags);
        var ast = new JsonObject();
        ast.addProperty("type", "Container");
        var span = new JsonArray(); span.add(0); span.add(input.codePointCount(0, input.length()));
        ast.add("span", span); ast.add("fields", fields);
        return ast;
    }

    private JsonElement cardinalityItem(JsonElement description) {
        if (description.isJsonNull()) return description;
        var item = description.getAsJsonArray();
        var ast = new JsonObject(); ast.addProperty("type", "Item");
        var span = new JsonArray(); span.add(item.get(1)); span.add(item.get(2)); ast.add("span", span);
        var fields = new JsonObject(); fields.add("value", item.get(0)); ast.add("fields", fields);
        return ast;
    }

    @Test public void diagnosticEdgeCasesIncludeAnExplicitBackendDifference() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        var grammar = UBNFMapper.parse(fixture("3/Evolution.ubnf")).grammars().get(0);
        Path dir = temporary.newFolder().toPath();
        Path generated = Files.createDirectory(dir.resolve("generated"));
        for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
        Files.writeString(dir.resolve("main.rs"), """
            mod generated;
            use std::io::{self, BufRead};
            fn main() {
                for line in io::stdin().lock().lines() {
                    let error = generated::parser::parse_tree_detailed(&line.unwrap()).unwrap_err();
                    println!("{}", error.canonical_json());
                }
            }
            """);
        success(rustCompile(dir, library));
        var corpus = JsonParser.parseString(fixture("diagnostics.json")).getAsJsonArray();
        var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", corpus.asList().stream()
            .map(row -> row.getAsJsonObject().get("input").getAsString()).toList()) + "\n");
        success(actual);
        var lines = actual.output().lines().toList();
        assertEquals(corpus.size(), lines.size());
        var report = new ArrayList<>(List.of("input_json\tjava_diagnostic\trust_diagnostic"));
        try (var loader = compileJava(grammar, 3)) {
            for (int i = 0; i < corpus.size(); i++) {
                var row = corpus.get(i).getAsJsonObject();
                String input = row.get("input").getAsString();
                var java = javaDiagnostic(loader, input);
                var rust = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                report.add(row.get("input") + "\t" + java + "\t" + rust);
                assertEquals(input, row.get("kind"), java.get("kind"));
                assertEquals(input, row.get("kind"), rust.get("kind"));
                assertEquals(input + " Java", row.get("javaOffset"), java.get("offset"));
                assertEquals(input + " Rust", row.get("rustOffset"), rust.get("offset"));
                assertFalse(input, java.getAsJsonArray("expected").isEmpty());
                assertFalse(input, rust.getAsJsonArray("expected").isEmpty());
                if (row.get("kind").getAsString().equals("trailing_input")) {
                    assertEquals(input, java.get("expected"), rust.get("expected"));
                }
            }
        }
        Files.write(Path.of("target/rust-diagnostics-edge.tsv"), report, StandardCharsets.UTF_8);
    }

    @Test public void sameGrammarPreservesAstSpansResultsAndEvolutionObligations() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        var corpus = JsonParser.parseString(fixture("conformance.json")).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("stage\tcases\taccepted\tevaluated\tstale_dispatch\tmissing_semantics"));
        List<String> diagnosticReport = new ArrayList<>(List.of("stage\tinput_json\tjava_diagnostic\trust_diagnostic"));
        String previousDispatch = null;
        for (int stage = 0; stage < 4; stage++) {
            var grammar = UBNFMapper.parse(fixture(stage + "/Evolution.ubnf")).grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            String dispatch = Files.readString(generated.resolve("evaluator.rs"));
            if (stage >= 2) {
                Files.writeString(generated.resolve("evaluator.rs"), previousDispatch);
                Files.writeString(dir.resolve("main.rs"), "mod generated; fn main() {}\n");
                var stale = rustCompile(dir, library);
                assertNotEquals(stale.output(), 0, stale.code());
                assertTrue(stale.output(), stale.output().contains("E0004"));
                Files.writeString(generated.resolve("evaluator.rs"), dispatch);
                Files.writeString(dir.resolve("semantics.rs"), semantics(stage - 1));
                Files.writeString(dir.resolve("main.rs"), "mod generated; mod semantics; fn main() {}\n");
                var missing = rustCompile(dir, library);
                assertNotEquals(missing.output(), 0, missing.code());
                assertTrue(missing.output(), missing.output().contains("E0046"));
                assertTrue(missing.output(), missing.output().contains(stage == 2 ? "eval_negation" : "eval_conditional"));
            }
            Files.writeString(dir.resolve("main.rs"), Files.readString(repo.resolve("rust/examples/evolution/src/main.rs"))
                .replace("use unlaxer_evolution_example::{generated, semantics::Calculator};",
                    "mod generated; mod semantics; use semantics::Calculator;"));
            if (stage == 1) {
                Files.writeString(dir.resolve("semantics.rs"), semantics(0));
                success(rustCompile(dir, library));
                var negative = run(List.of(dir.resolve("probe").toString()), "2*3\n");
                success(negative);
                assertTrue(JsonParser.parseString(negative.output()).getAsJsonObject().get("evaluationError").getAsBoolean());
            }
            Files.writeString(dir.resolve("semantics.rs"), semantics(stage));
            success(rustCompile(dir, library));
            String input = String.join("\n", corpus.asList().stream().map(JsonElement::getAsString).toList()) + "\n";
            var actual = run(List.of(dir.resolve("probe").toString()), input);
            success(actual);
            var lines = actual.output().lines().toList();
            assertEquals(corpus.size(), lines.size());
            int accepted = 0, evaluated = 0;
            try (var loader = compileJava(grammar, stage)) {
                for (int i = 0; i < corpus.size(); i++) {
                    String source = corpus.get(i).getAsString();
                    var expected = javaResult(loader, source);
                    var rust = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    String context = "stage=" + stage + " input=" + source;
                    assertEquals(context, expected.get("ok"), rust.get("ok"));
                    if (expected.get("ok").getAsBoolean()) {
                        accepted++;
                        assertEquals(context, expected.get("ast"), rust.get("ast"));
                        assertEquals(context, expected.get("evaluationError"), rust.get("evaluationError"));
                        if (expected.has("value")) {
                            evaluated++;
                            assertEquals(context, expected.get("value").getAsDouble(), rust.get("value").getAsDouble(), 1e-12);
                        }
                    } else {
                        int offset = rust.get("offset").getAsInt();
                        assertTrue(context, offset >= 0 && offset <= source.codePointCount(0, source.length()));
                        assertFalse(context, rust.getAsJsonArray("expected").isEmpty());
                        var javaDiagnostic = expected.getAsJsonObject("diagnostic");
                        var rustDiagnostic = rust.getAsJsonObject("diagnostic");
                        diagnosticReport.add(stage + "\t" + corpus.get(i) + "\t" + javaDiagnostic + "\t" + rustDiagnostic);
                        assertEquals(context, javaDiagnostic.get("kind"), rustDiagnostic.get("kind"));
                        assertEquals(context, javaDiagnostic.get("offset"), rustDiagnostic.get("offset"));
                        if (javaDiagnostic.get("kind").getAsString().equals("trailing_input")) {
                            assertEquals(context, javaDiagnostic.get("expected"), rustDiagnostic.get("expected"));
                        }
                    }
                }
            }
            report.add(stage + "\t" + corpus.size() + "\t" + accepted + "\t" + evaluated + "\t"
                + (stage >= 2 ? "E0004\tE0046" : "not-applicable\tnot-applicable"));
            previousDispatch = dispatch;
        }
        Files.write(Path.of("target/rust-conformance.tsv"), report, StandardCharsets.UTF_8);
        Files.write(Path.of("target/rust-diagnostics.tsv"), diagnosticReport, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
        // Committed standalone example must exactly match the generator, without requiring Java to run.
        var finalGrammar = UBNFMapper.parse(fixture("3/Evolution.ubnf")).grammars().get(0);
        for (var file : new RustBackend().generate(finalGrammar)) assertEquals(file.relativePath(), file.content(),
            Files.readString(repo.resolve("rust/examples/evolution/src/generated").resolve(file.relativePath())));
    }

    private String fixture(String path) throws Exception {
        try (var input = getClass().getResourceAsStream("/evolution/" + path)) {
            assertNotNull(path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private URLClassLoader compileJava(org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar, int stage) throws Exception {
        return compileJava(grammar, List.of(new GeneratedSource("org.example.evolution", "Calculator", fixture(stage + "/Calculator.java.txt"))));
    }

    private URLClassLoader compileJava(org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar, List<GeneratedSource> extra) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator())) {
            sources.add(generator.generate(grammar));
        }
        sources.addAll(extra);
        var units = sources.stream().map(s -> new SimpleJavaFileObject(
            URI.create("string:///" + s.packageName().replace('.', '/') + "/" + s.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return s.source(); }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonObject javaResult(ClassLoader loader, String source) throws Exception {
        var result = new JsonObject();
        Object mapped;
        try { mapped = loader.loadClass(PACKAGE + "EvolutionMapper").getMethod("parseWithSourceMap", String.class).invoke(null, source); }
        catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
            result.addProperty("ok", false);
            result.add("diagnostic", javaDiagnostic(loader, source));
            return result;
        }
        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
        assertTrue(((Optional<?>) loader.loadClass(PACKAGE + "EvolutionMapper")
            .getMethod("diagnose", String.class).invoke(null, source)).isEmpty());
        result.addProperty("ok", true);
        result.add("ast", canonical(ast, mapped));
        Object calculator = loader.loadClass(PACKAGE + "Calculator").getConstructor().newInstance();
        try {
            double value = (Double) calculator.getClass().getMethod("eval", loader.loadClass(PACKAGE + "EvolutionAST")).invoke(calculator, ast);
            if (Double.isFinite(value)) result.addProperty("value", value);
            else result.addProperty("evaluationError", true);
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
            result.addProperty("evaluationError", true);
        }
        return result;
    }

    private JsonObject javaDiagnostic(ClassLoader loader, String source) throws Exception {
        Object diagnostic = ((Optional<?>) loader.loadClass(PACKAGE + "EvolutionMapper")
            .getMethod("diagnose", String.class).invoke(null, source)).orElseThrow();
        var json = new JsonObject();
        for (var component : diagnostic.getClass().getRecordComponents()) {
            json.add(component.getName(), new Gson().toJsonTree(component.getAccessor().invoke(diagnostic)));
        }
        return json;
    }

    private JsonObject canonical(Object ast, Object mapped) throws Exception {
        var result = new JsonObject();
        result.addProperty("type", ast.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast)).orElseThrow();
        var position = new JsonArray(); position.add(span[0]); position.add(span[1]); result.add("span", position);
        var fields = new JsonObject();
        for (var component : ast.getClass().getRecordComponents()) {
            Object value = component.getAccessor().invoke(ast);
            fields.add(component.getName(), canonicalValue(value, mapped));
        }
        result.add("fields", fields);
        return result;
    }

    private JsonElement canonicalValue(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonicalValue(optional.orElse(null), mapped);
        if (value instanceof List<?> list) {
            JsonArray result = new JsonArray();
            for (Object item : list) result.add(canonicalValue(item, mapped));
            return result;
        }
        return canonical(value, mapped);
    }

    private record ProcessResult(int code, String output) {}
    private ProcessResult run(List<String> command, String input) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(command.toString(), process.waitFor(45, TimeUnit.SECONDS));
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private ProcessResult rustCompile(Path directory, Path library) throws Exception {
        return run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
            directory.resolve("main.rs").toString(), "-o", directory.resolve("probe").toString()), "");
    }
    private void success(ProcessResult result) { assertEquals(result.output(), 0, result.code()); }

    private String semantics(int stage) {
        return """
            use crate::generated::{ast::Ast, evaluator::{evaluate, Semantics}};
            use unlaxer_runtime::Span;
            pub struct Calculator;
            impl Semantics for Calculator {
                type Output = Result<f64, String>;
                fn eval_number(&mut self, value: &str, _: Span) -> Self::Output {
                    value.parse::<f64>().map_err(|error| error.to_string())
                }
                fn eval_binary(&mut self, left: &Ast, op: &str, right: &Ast, _: Span) -> Self::Output {
                    let left = evaluate(left, self)?; let right = evaluate(right, self)?;
                    match op { "+" => Ok(left + right),
            """ + (stage >= 1 ? "\"*\" => Ok(left * right),\n" : "") + "_ => Err(op.to_owned()) } }\n"
            + (stage >= 2 ? """
                fn eval_negation(&mut self, value: &Ast, _: Span) -> Self::Output { Ok(-evaluate(value, self)?) }
                """ : "")
            + (stage >= 3 ? """
                fn eval_conditional(&mut self, condition: &Ast, then_expr: &Ast, else_expr: &Ast, _: Span) -> Self::Output {
                    if evaluate(condition, self)? != 0.0 { evaluate(then_expr, self) } else { evaluate(else_expr, self) }
                }
                """ : "") + "}\n";
    }
}
