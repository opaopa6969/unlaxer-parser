package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.RegexRetirement;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Independent migration oracle: no regex engine is used to derive lexical expectations. */
public class RegexRetirementConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test
    public void directAstAndEveryJavaGeneratorRejectTheRemovedAdapter() {
        GrammarDecl original =
                UBNFMapper.parse(
                                "grammar Input { @root @mapping(Value,params=[value]) Root ::= 'x'"
                                        + " @value; }")
                        .grammars()
                        .get(0);
        for (TokenDecl removed :
                List.of(
                        new TokenDecl.Regex("T", "a+"),
                        new TokenDecl.Simple("T", "RegexTokenParser"),
                        new TokenDecl.Simple("T", "org.unlaxer.dsl.runtime.RegexTokenParser"))) {
            GrammarDecl grammar =
                    new GrammarDecl(
                            original.name(),
                            original.imports(),
                            original.settings(),
                            List.of(removed),
                            original.rules());
            assertTrue(
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .anyMatch(issue -> issue.code().equals(RegexRetirement.CODE)));
            for (CodeGenerator generator :
                    List.of(
                            new ParserGenerator(),
                            new ASTGenerator(),
                            new MapperGenerator(),
                            new EvaluatorGenerator(),
                            new LSPGenerator(),
                            new DAPGenerator(),
                            new LSPLauncherGenerator(),
                            new DAPLauncherGenerator())) {
                assertTrue(
                        generator.getClass().toString(),
                        assertThrows(
                                        IllegalArgumentException.class,
                                        () -> generator.generate(grammar))
                                .getMessage()
                                .startsWith(RegexRetirement.CODE + ":"));
            }
            assertTrue(
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> new RustBackend().generate(grammar))
                            .getMessage()
                            .startsWith(RegexRetirement.CODE + ":"));
            assertTrue(
                    GrammarValidator.validate(grammar).stream()
                            .anyMatch(issue -> issue.code().equals(RegexRetirement.CODE)));
            assertTrue(
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () ->
                                            org.unlaxer.dsl.tools.bnf.UBNFToBNFConverter.convert(
                                                    new UBNFFile(List.of(grammar)), false))
                            .getMessage()
                            .startsWith(RegexRetirement.CODE + ":"));
        }
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("org.unlaxer.dsl.runtime.RegexTokenParser"));
        // REGEX is still a legal user token name; strings/comments must not trigger the migration
        // check.
        var allowed =
                UBNFMapper.parse(
                                "grammar Input { @ubnf: v2 // REGEX('removed')\n"
                                    + " token REGEX ::= 'REGEX'; @root"
                                    + " @mapping(Value,params=[value]) Root ::= REGEX @value; }")
                        .grammars()
                        .get(0);
        new ParserGenerator().generate(allowed);
        new RustBackend().generate(allowed);
    }

    @Test
    public void bothFrontendsRejectAtTheSameScalarAndBytePositionBeforeGeneration()
            throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] RegexRetirementConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path ubnf = repo.resolve("rust/unlaxer-ubnf");
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_ubnf.rlib");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_ubnf",
                        ubnf.resolve("src/lib.rs").toString(),
                        "-o",
                        library.toString()));
        Path binary = temporary.getRoot().toPath().resolve("inspect");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        ubnf.resolve("examples/inspect.rs").toString(),
                        "--extern",
                        "unlaxer_ubnf=" + library,
                        "-o",
                        binary.toString()));
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator"));
        Path generator = repo.resolve("rust/target/debug/unlaxer");
        var report =
                new ArrayList<>(List.of("case\tcode\tcp_span\tbyte_span\tline\tcolumn\tresult"));
        for (var item :
                JsonParser.parseString(
                                Files.readString(
                                        repo.resolve("spec-corpus/regex-retirement/invalid.json")))
                        .getAsJsonArray()) {
            var row = item.getAsJsonObject();
            String source = row.get("grammar").getAsString();
            var expected = row.getAsJsonArray("span");
            var javaError =
                    assertThrows(
                            RegexRetirement.MigrationException.class,
                            () -> UBNFMapper.parse(source));
            assertEquals(
                    javaError.getMessage(),
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> UBNFMapper.parseWithSource(source))
                            .getMessage());
            assertEquals(expected.get(0).getAsInt(), javaError.start());
            assertEquals(expected.get(1).getAsInt(), javaError.end());
            assertTrue(
                    javaError.getMessage(),
                    javaError
                            .getMessage()
                            .contains("line " + row.get("line") + ", column " + row.get("column")));
            Path input = temporary.newFile().toPath();
            Files.writeString(input, source);
            var nativeResult =
                    JsonParser.parseString(run(List.of(binary.toString(), input.toString())))
                            .getAsJsonObject();
            assertTrue(
                    nativeResult.toString(),
                    nativeResult.get("error").getAsString().startsWith(RegexRetirement.CODE + ":"));
            assertEquals(expected.get(0), nativeResult.getAsJsonArray("span").get(2));
            assertEquals(expected.get(1), nativeResult.getAsJsonArray("span").get(3));
            assertEquals(
                    row.getAsJsonArray("bytes").get(0), nativeResult.getAsJsonArray("span").get(0));
            assertEquals(
                    row.getAsJsonArray("bytes").get(1), nativeResult.getAsJsonArray("span").get(1));
            assertEquals(row.get("line"), nativeResult.get("line"));
            assertEquals(row.get("column"), nativeResult.get("column"));
            var checked = PortabilityCheck.check(source);
            assertEquals(RegexRetirement.CODE, checked.diagnostics().get(0).code());
            assertEquals(javaError.start(), checked.diagnostics().get(0).span().start());
            Path output = temporary.getRoot().toPath().resolve("must-not-exist");
            var process =
                    new ProcessBuilder(
                                    generator.toString(),
                                    "generate",
                                    "--grammar",
                                    input.toString(),
                                    "--output",
                                    output.toString())
                            .redirectErrorStream(true)
                            .start();
            String error =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            assertNotEquals(error, 0, process.exitValue());
            assertTrue(error, error.contains(RegexRetirement.CODE));
            assertFalse(Files.exists(output));
            var nativeCheck =
                    JsonParser.parseString(
                                    runAllowFailure(
                                            List.of(
                                                    generator.toString(),
                                                    "check",
                                                    "--target",
                                                    "rust",
                                                    "--grammar",
                                                    input.toString())))
                            .getAsJsonObject();
            assertEquals(
                    RegexRetirement.CODE,
                    nativeCheck
                            .getAsJsonArray("diagnostics")
                            .get(0)
                            .getAsJsonObject()
                            .get("code")
                            .getAsString());
            report.add(
                    row.get("name")
                            + "\t"
                            + RegexRetirement.CODE
                            + "\t"
                            + expected
                            + "\t"
                            + row.get("bytes")
                            + "\t"
                            + row.get("line")
                            + "\t"
                            + row.get("column")
                            + "\tequal");
        }
        Files.write(Path.of("target/regex-retirement-rejections.tsv"), report);
    }

    @Test
    public void replacementConsumersCompileAndPreserveValuesPositionsAndRollback()
            throws Exception {
        var harness = new DeclarativeTokenConformanceTest();
        harness.temporary.create();
        try {
            harness.verifyRuntimeCorpus(
                    repo.resolve("spec-corpus/regex-retirement"), "regex-retirement-runtime.tsv");
        } finally {
            harness.temporary.delete();
        }
    }

    private String run(List<String> command) throws Exception {
        return command(command, false);
    }

    private String runAllowFailure(List<String> command) throws Exception {
        return command(command, true);
    }

    private String command(List<String> command, boolean failure) throws Exception {
        Path log = temporary.newFile().toPath();
        var process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertTrue(command.toString(), process.waitFor(60, TimeUnit.SECONDS));
            String text = Files.readString(log);
            if (failure) assertNotEquals(text, 0, process.exitValue());
            else assertEquals(text, 0, process.exitValue());
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
