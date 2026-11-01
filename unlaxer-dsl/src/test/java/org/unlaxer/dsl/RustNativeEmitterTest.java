package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.unlaxer.dsl.codegen.rust.GrammarIR.*;
import java.util.List;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

/** Compares independent normalized IR emission and complete source-aware generation separately. */
public class RustNativeEmitterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeEmitterMatchesJavaByteForByte() throws Exception {
        compare("evolution", Files.readString(Path.of("src/test/resources/evolution/3/Evolution.ubnf")));
        compare("fields", """
            grammar Fields {
              @whitespace: javaStyle
              token ID = IdentifierParser
              token SQ = SingleQuotedParser
              token DQ = DoubleQuotedParser
              token NUMBER = NumberParser
              @root @mapping(Value, params=[type,text,texts,child,maybe,children])
              Root ::= ID @type [ SQ @text ] { DQ @texts } Leaf @child [ Leaf @maybe ] { Leaf @children };
              @mapping(Leaf, params=[value]) Leaf ::= NUMBER @value;
            }
            """);
        compare("shared", """
            grammar Shared {
              @whitespace: javaStyle
              @root Root ::= Left | Right;
              @mapping(Value, params=[value]) @precedence(level=20) Left ::= 'a' @value;
              @mapping(Value, params=[value]) @precedence(level=10) Right ::= 'b' @value;
            }
            """);
    }

    private static GrammarIR sharedIr() {
        var mapping = new Mapping("Value", List.of(new Field("value", Kind.TEXT, Cardinality.ONE)));
        return new GrammarIR(List.of(
            new GrammarIR.Rule("Root", new Choice(List.of(new Reference(1), new Reference(2))), null, null, null),
            new GrammarIR.Rule("Left", new Sequence(List.of(new Capture("value", new Literal("a")))), mapping,
                new Operator(Associativity.NONE, 20), null),
            new GrammarIR.Rule("Right", new Sequence(List.of(new Capture("value", new Literal("b")))), mapping,
                new Operator(Associativity.NONE, 10), null)), 0, true);
    }

    private void compare(String fixture, String source) throws Exception {
        Path output = temporary.newFolder(fixture).toPath();
        Path log = output.resolve("cargo.log");
        var builder = org.unlaxer.dsl.TestProcesses.builder("cargo", "run", "--offline", "--quiet", "-p", "unlaxer-codegen",
            "--example", "parity_fixture", "--", fixture, output.toString())
            .directory(Path.of("../rust").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var process = builder.start();
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue("Rust native fixture timed out", finished);
        assertEquals(Files.readString(log), 0, process.exitValue());
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var backend = new RustBackend();
        // Raw IR metadata need not be legal source annotations. Keep the original
        // invalid UBNF below and assert rejection by both full lowering paths.
        var ir = fixture.equals("shared") ? sharedIr() : RustGrammarLowering.lower(grammar);
        // The handwritten Rust fixture contains normalized IR, without source annotations.
        // Compare the same layer; preferred mapping and other source-derived APIs are
        // checked byte-for-byte below against the native UBNF frontend as well.
        for (String module : java.util.List.of("ast", "parser", "mapper", "evaluator")) {
            var emitter = RustBackend.class.getDeclaredMethod(module, GrammarIR.class);
            emitter.setAccessible(true);
            assertEquals(fixture + "/IR/" + module + ".rs", emitter.invoke(backend, ir),
                Files.readString(output.resolve(module + ".rs"), StandardCharsets.UTF_8));
        }
        if (!fixture.equals("shared"))
            assertEquals(backend.generate(grammar).get(0).content(), Files.readString(output.resolve("mod.rs")));

        Path input = temporary.newFile(fixture + ".ubnf").toPath();
        Files.writeString(input, source);
        Path fullOutput = temporary.newFolder(fixture + "-source").toPath();
        Path fullLog = temporary.newFile(fixture + "-source.log").toPath();
        var nativeProcess = org.unlaxer.dsl.TestProcesses.builder("cargo", "run", "--offline", "--quiet", "-p", "unlaxer-generator",
            "--", "generate", "--grammar", input.toString(), "--output", fullOutput.toString())
            .directory(Path.of("../rust").toFile()).redirectErrorStream(true).redirectOutput(fullLog.toFile()).start();
        boolean generated = nativeProcess.waitFor(90, TimeUnit.SECONDS);
        if (!generated) nativeProcess.destroyForcibly();
        assertTrue("Rust source generation timed out", generated);
        if (fixture.equals("shared")) {
            // This deliberately handwritten IR fixture supplies precedence without
            // associativity. It is valid for the low-level emitter, but both full
            // frontends must reject it instead of treating it as valid UBNF.
            var issues = org.unlaxer.dsl.codegen.GrammarValidator.validateWithoutClassLoading(grammar);
            assertTrue(issues.toString(), issues.stream().anyMatch(issue ->
                issue.code().equals("E-PRECEDENCE-NO-ASSOC")));
            for (String message : List.of(
                    assertThrows(IllegalArgumentException.class, () -> RustGrammarLowering.lower(grammar)).getMessage(),
                    assertThrows(IllegalArgumentException.class, () -> backend.generate(grammar)).getMessage()))
                assertTrue(message, message.startsWith("E-PRECEDENCE-NO-ASSOC:"));
            assertEquals(Files.readString(fullLog), 3, nativeProcess.exitValue());
            assertTrue(Files.readString(fullLog).contains("associativity and @precedence must occur together"));
            try (var files = Files.list(fullOutput)) { assertEquals(0, files.count()); }
            return;
        }
        var validation = org.unlaxer.dsl.codegen.GrammarValidator.validateWithoutClassLoading(grammar);
        assertTrue(validation.toString(), validation.isEmpty());
        assertEquals(Files.readString(fullLog), 0, nativeProcess.exitValue());
        for (var file : backend.generate(grammar)) {
            assertEquals(fixture + "/source/" + file.relativePath(), file.content(),
                Files.readString(fullOutput.resolve(file.relativePath()), StandardCharsets.UTF_8));
        }
    }
}
