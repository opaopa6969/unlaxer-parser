package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import javax.tools.*;

/** Real generated consumers use the same callback sites, input, values and CP-span oracle. */
public class OrdinaryParserExceptionConformanceTest extends PreferredAstConformanceTest {
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Override
    protected JsonArray corpus() throws Exception {
        return JsonParser.parseString(
                        Files.readString(
                                repo.resolve("spec-corpus/ordinary-exceptions/cases.json")))
                .getAsJsonArray();
    }

    @Override
    @Test
    public void javaSnapshots() throws Exception {
        for (var entry : corpus()) {
            var fixture = entry.getAsJsonObject();
            var grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            var units = new ArrayList<JavaFileObject>();
            for (CodeGenerator generator :
                    List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
                var source = generator.generate(grammar);
                units.add(unit(source.packageName() + "." + source.className(), source.source()));
            }
            units.add(unit("exceptionprobe.JavaProbe", template("JavaProbe.java", fixture, false)));
            Path output = temporary.newFolder().toPath();
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var manager =
                    compiler.getStandardFileManager(
                            diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                boolean compiled =
                        compiler.getTask(
                                        null,
                                        manager,
                                        diagnostics,
                                        List.of(
                                                "--release",
                                                "21",
                                                "-classpath",
                                                System.getProperty("java.class.path"),
                                                "-d",
                                                output.toString()),
                                        null,
                                        units)
                                .call();
                assertTrue(diagnostics.getDiagnostics().toString(), compiled);
            }
            try (var loader =
                    new URLClassLoader(
                            new URL[] {output.toUri().toURL()}, getClass().getClassLoader())) {
                int[] failures =
                        fixture.getAsJsonArray("cases").asList().stream()
                                .mapToInt(v -> v.getAsJsonObject().get("failCP").getAsInt())
                                .toArray();
                assertEquals(
                        failures.length * 24,
                        loader.loadClass("exceptionprobe.JavaProbe")
                                .getMethod("run", String.class, int[].class, int.class)
                                .invoke(
                                        null,
                                        fixture.get("input").getAsString(),
                                        failures,
                                        fixture.get("retryEnd").getAsInt()));
            }
        }
    }

    @Override
    @Test
    public void bothHostsSnapshots() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] OrdinaryParserExceptionConformanceTest requires"
                            + " -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_runtime",
                        repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                        "-o",
                        runtime.toString()),
                "",
                false,
                true);
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator"),
                "",
                false,
                true);
        Path generator = repo.resolve("rust/target/debug/unlaxer");
        for (var entry : corpus()) {
            var fixture = entry.getAsJsonObject();
            String source = fixture.get("grammar").getAsString();
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            var files = new RustBackend().generate(grammar);
            for (String host : List.of("java", "native")) {
                Path dir = temporary.newFolder().toPath(),
                        input = dir.resolve("grammar.ubnf"),
                        output = dir.resolve("generated");
                Files.writeString(input, source);
                if (host.equals("native")) {
                    run(
                            List.of(
                                    generator.toString(),
                                    "generate",
                                    "--grammar",
                                    input.toString(),
                                    "--output",
                                    output.toString()),
                            "",
                            true,
                            true);
                    for (var file : files)
                        assertEquals(
                                file.relativePath(),
                                file.content(),
                                Files.readString(output.resolve(file.relativePath())));
                } else {
                    for (var file : files) {
                        Path path = output.resolve(file.relativePath());
                        Files.createDirectories(path.getParent());
                        Files.writeString(path, file.content());
                    }
                }
                Path probe = dir.resolve("main.rs"), exe = dir.resolve("probe");
                Files.writeString(probe, template("rust-probe.rs", fixture, true));
                run(
                        List.of(
                                "rustc",
                                "--edition=2021",
                                probe.toString(),
                                "--extern",
                                "unlaxer_runtime=" + runtime,
                                "-o",
                                exe.toString()),
                        "",
                        false,
                        true);
                var command =
                        new ArrayList<>(
                                List.of(
                                        exe.toString(),
                                        fixture.get("input").getAsString(),
                                        fixture.get("retryEnd").getAsString()));
                for (var row : fixture.getAsJsonArray("cases"))
                    command.add(row.getAsJsonObject().get("failCP").getAsString());
                assertEquals(
                        host + ":" + fixture.get("name"),
                        Integer.toString(fixture.getAsJsonArray("cases").size() * 12),
                        run(command, "", true, true).trim());
            }
        }
    }

    private String template(String name, JsonObject fixture, boolean rust) throws Exception {
        JsonElement items = fixture.get("items");
        String expected;
        if (items.isJsonArray()) {
            var values = new ArrayList<String>();
            for (var value : items.getAsJsonArray())
                values.add(value.toString() + (rust ? ".to_owned()" : ""));
            expected =
                    (rust ? "vec![" : "List.of(") + String.join(",", values) + (rust ? "]" : ")");
        } else expected = items.toString();
        return Files.readString(repo.resolve("spec-corpus/ordinary-exceptions/" + name))
                .replace("__EXPECTED_ITEMS__", expected)
                .replace("__EXPECTED_BOUNDARY__", fixture.has("boundary")
                        ? (rust ? "Some(" + fixture.get("boundary") + ")" : fixture.get("boundary").toString())
                        : (rust ? "None" : "null"))
                .replace("__ROOT_START__", fixture.getAsJsonArray("rootSpan").get(0).toString())
                .replace("__ROOT_END__", fixture.getAsJsonArray("rootSpan").get(1).toString());
    }

    private static JavaFileObject unit(String name, String source) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return source;
            }
        };
    }
}
