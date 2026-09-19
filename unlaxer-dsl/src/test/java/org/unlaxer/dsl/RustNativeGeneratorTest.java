package org.unlaxer.dsl;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Java is an oracle only: every generation below uses the JVM-free native executable. */
public class RustNativeGeneratorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void nativeFrontendLoweringAndEmissionMatchTheSupportedCorpus() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        assertEquals(0, command(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path binary = repo.resolve("rust/target/debug/unlaxer");
        List<String> grammars = new ArrayList<>();
        for (String corpus : List.of("primitives", "lexical")) {
            for (var row : JsonParser.parseString(Files.readString(Path.of("src/test/resources/" + corpus + "/corpus.json"))).getAsJsonArray()) {
                var fixture = row.getAsJsonObject();
                grammars.add("grammar Primitive { @package: org.example.primitive "
                    + (fixture.has("whitespace") ? "@whitespace: javaStyle" : "")
                    + " token T = " + fixture.get("token").getAsString() + "\n token END = EOF\n "
                    + (fixture.has("extra") ? fixture.get("extra").getAsString() : "")
                    + "\n @root @mapping(Value,params=[value]) Root ::= " + fixture.get("body").getAsString() + "; }");
            }
        }
        String cardinality = Files.readString(Path.of("src/test/resources/cardinality/Cardinality.ubnf"));
        for (String quantifier : List.of("{ Item }", "Item+", "Item{1,2}", "Item{1,}", "Item % ','")) {
            grammars.add(cardinality.replace("{ Item }", quantifier));
        }
        for (int stage = 0; stage < 4; stage++) grammars.add(Files.readString(Path.of("src/test/resources/evolution/" + stage + "/Evolution.ubnf")));
        grammars.add(Files.readString(Path.of("src/test/resources/associative/Operators.ubnf")));
        for (String atom : List.of("Atom ::= NUMBER;", "@mapping(Number,params=[value]) Atom ::= (NUMBER) @value;")) {
            grammars.add("grammar Power { @whitespace: javaStyle token NUMBER = org.unlaxer.parser.elementary.NumberParser\n"
                + "@root @rightAssoc @precedence(level=10) @mapping(Power,params=[left,op,right]) Expr ::= Atom @left { '^' @op Expr @right }; " + atom + " }");
        }
        String power = Files.readString(Path.of("src/test/resources/right-associative/Power.ubnf"));
        grammars.add(power.replace("Atom ::= NUMBER;", "@mapping(Number,params=[value]) Atom ::= ['😀'] (NUMBER) @value;"));
        grammars.add(power.replace("Atom ::= NUMBER;", "@mapping(Number,params=[value]) Atom ::= (NUMBER) @value;")
            .replace("Expr ::= Atom @left", "Expr ::= Base @left").replace("  @root", "  Base ::= Atom | '(' Expr ')';\n  @root"));
        for (String ruleName : List.of("_Root", "self")) {
            grammars.add("grammar G { @whitespace: none @root @mapping(Item,params=[value]) " + ruleName + " ::= 'x' @value; }");
        }
        var report = new ArrayList<>(List.of("grammar_index\tstatus\tfiles_identical"));
        int index = 0;
        for (String source : grammars) {
            Path grammar = temporary.newFile().toPath();
            Files.writeString(grammar, source);
            Path output = temporary.getRoot().toPath().resolve("generated-" + index);
            var generated = command(List.of(binary.toString(), "generate", "--grammar", grammar.toString(), "--output", output.toString()), true);
            assertEquals(source + "\n" + generated.output(), 0, generated.code());
            var expected = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
            assertEquals(5, expected.size());
            for (var file : expected) assertEquals(source + " / " + file.relativePath(), file.content(), Files.readString(output.resolve(file.relativePath())));
            var checked = command(List.of(binary.toString(), "generate", "--grammar", grammar.toString(), "--output", output.toString(), "--check"), true);
            assertEquals(checked.output(), 0, checked.code());
            report.add(index++ + "\taccepted\t5");
        }
        Files.write(Path.of("target/rust-native-generator.tsv"), report, StandardCharsets.UTF_8);
    }

    private record Result(int code, String output) {}
    private Result command(List<String> args, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        var process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("process timed out: " + args); }
        return new Result(process.exitValue(), Files.readString(log));
    }
}
