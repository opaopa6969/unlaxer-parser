package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Independent normalized Rust fixtures compared with the real Java UBNF frontend. */
public class RustNativeEmitterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeEmitterMatchesJavaByteForByte() throws Exception {
        compare("evolution", Files.readString(Path.of("src/test/resources/evolution/3/Evolution.ubnf")), false);
        compare("evolution", Files.readString(Path.of("src/test/resources/evolution/3/Evolution.ubnf")), true);
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
            """, false);
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
            """, true);
        compare("shared", """
            grammar Shared {
              @whitespace: javaStyle
              @root Root ::= Left | Right;
              @mapping(Value, params=[value]) @precedence(level=20) Left ::= 'a' @value;
              @mapping(Value, params=[value]) @precedence(level=10) Right ::= 'b' @value;
            }
            """, false);
        compare("shared", """
            grammar Shared {
              @whitespace: javaStyle
              @root Root ::= Left | Right;
              @mapping(Value, params=[value]) @precedence(level=20) Left ::= 'a' @value;
              @mapping(Value, params=[value]) @precedence(level=10) Right ::= 'b' @value;
            }
            """, true);
    }

    private void compare(String fixture, String source, boolean direct) throws Exception {
        Path output = temporary.newFolder(fixture + (direct ? "-direct" : "-combinator")).toPath();
        Path log = output.resolve("cargo.log");
        var builder = new ProcessBuilder("cargo", "run", "--offline", "--quiet", "-p", "unlaxer-codegen",
            "--example", "parity_fixture", "--", fixture, output.toString(), direct ? "direct" : "combinator")
            .directory(Path.of("../rust").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var process = builder.start();
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue("Rust native fixture timed out", finished);
        assertEquals(Files.readString(log), 0, process.exitValue());
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        for (var file : new RustBackend().generate(grammar, direct ? RustBackend.ExecutionTier.DIRECT : RustBackend.ExecutionTier.COMBINATOR)) {
            assertEquals(fixture + "/" + file.relativePath(), file.content(),
                Files.readString(output.resolve(file.relativePath()), StandardCharsets.UTF_8));
        }
    }
}
