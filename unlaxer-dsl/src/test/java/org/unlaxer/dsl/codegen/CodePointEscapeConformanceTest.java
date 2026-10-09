package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;

public class CodePointEscapeConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void legacyEscapesAndUnrelatedConstructorsKeepTheirValues() {
        for (String version : List.of("", "@ubnf: v1", "@ubnf: v2")) {
            String prefix = "grammar G { " + version;
            if (!version.equals("@ubnf: v2")) {
                TokenDecl.Negation token = (TokenDecl.Negation) UBNFMapper.parse(prefix
                    + " token T = NEGATION('\\u0041') @root Root ::= T; }").grammars().get(0).tokens().get(0);
                assertEquals("\\u0041", token.excludedChars());
                assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parse(prefix
                    + " token T = CHAR_RANGE('\\u0041','z') @root Root ::= T; }"));
            }
            TokenDecl.Until until = (TokenDecl.Until) UBNFMapper.parse(prefix
                + " token T = UNTIL('\\u0041') @root Root ::= T; }").grammars().get(0).tokens().get(0);
            assertEquals("\\u0041", until.terminator());
            TokenDecl.Regex regex = (TokenDecl.Regex) UBNFMapper.parse(prefix
                + " token T = REGEX('\\u0041') @root Root ::= T; }").grammars().get(0).tokens().get(0);
            assertEquals("\\u0041", regex.pattern());
        }
    }

    @Test public void unknownSupplementaryEscapePreservesItsRawValue() {
        TokenDecl.Negation token = (TokenDecl.Negation) UBNFMapper.parse(
            "grammar G { @ubnf: v2 token T = NEGATION('\\😀') @root Root ::= T; }")
            .grammars().get(0).tokens().get(0);
        assertEquals("\\😀", token.excludedChars());
    }

    @Test public void programmaticFormatTwoRangeRejectsTheSurrogateInterval() {
        var legacy = UBNFMapper.parse("grammar G { token T = CHAR_RANGE('a','') @root @mapping(Value,params=[text]) Root ::= T @text; }")
            .grammars().get(0);
        assertFalse(GrammarValidator.validateWithoutClassLoading(legacy).stream()
            .anyMatch(issue -> issue.code().equals("E-TOKEN-RANGE-SURROGATE")));
        var formatTwo = new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl(legacy.name(), legacy.imports(),
            List.of(new org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting("ubnf",
                new org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue("v2"))), legacy.tokens(), legacy.rules());
        assertTrue(GrammarValidator.validateWithoutClassLoading(formatTwo).stream()
            .anyMatch(issue -> issue.code().equals("E-TOKEN-RANGE-SURROGATE")));
    }

    @Test public void malformedEscapesAgreeOnCodesAndUnicodeSourcePositions() throws Exception {
        if (false == Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] CodePointEscapeConformanceTest requires -DrustConformance=true and rustc");
        }
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path crate = Path.of("../rust/unlaxer-ubnf").toAbsolutePath().normalize();
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_ubnf.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_ubnf",
            crate.resolve("src/lib.rs").toString(), "-o", library.toString()));
        Path binary = temporary.getRoot().toPath().resolve("inspect");
        run(List.of("rustc", "--edition=2021", crate.resolve("examples/inspect.rs").toString(),
            "--extern", "unlaxer_ubnf=" + library, "-o", binary.toString()));
        for (var item : JsonParser.parseString(Files.readString(Path.of(
                "../spec-corpus/codepoint-escapes/invalid.json"))).getAsJsonArray()) {
            var fixture = item.getAsJsonObject();
            String source = fixture.get("grammar").getAsString();
            Path input = temporary.newFile().toPath();
            Files.writeString(input, source);
            var nativeResult = JsonParser.parseString(run(List.of(binary.toString(), input.toString()))).getAsJsonObject();
            IllegalArgumentException java = assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parse(source));
            String code = fixture.get("code").getAsString();
            assertTrue(java.getMessage(), java.getMessage().startsWith(code + ":"));
            assertTrue(nativeResult.toString(), nativeResult.get("error").getAsString().startsWith(code + ":"));
            var expectedSpan = fixture.getAsJsonArray("span");
            assertEquals(expectedSpan.get(0), nativeResult.getAsJsonArray("span").get(2));
            assertEquals(expectedSpan.get(1), nativeResult.getAsJsonArray("span").get(3));
            assertTrue(java.getMessage(), java.getMessage().contains("code points ["
                + expectedSpan.get(0) + "," + expectedSpan.get(1) + ")"));
            assertEquals(fixture.get("line"), nativeResult.get("line"));
            assertEquals(fixture.get("column"), nativeResult.get("column"));
            assertTrue(java.getMessage(), java.getMessage().contains("line " + fixture.get("line")
                + ", column " + fixture.get("column")));
        }
    }

    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(command.toString(), process.waitFor(60, TimeUnit.SECONDS));
        String output = Files.readString(log);
        assertEquals(output, 0, process.exitValue());
        return output;
    }
}
