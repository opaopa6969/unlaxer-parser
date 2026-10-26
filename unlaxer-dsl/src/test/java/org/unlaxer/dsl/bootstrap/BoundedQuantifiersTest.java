package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.rust.RustBackend;

public class BoundedQuantifiersTest {
    @Test public void asciiBoundsKeepLeadingZeroesAndOverflowHandling() {
        assertEquals(0, BoundedQuantifiers.bound("0000"));
        assertEquals(1234567890, BoundedQuantifiers.bound("01234567890"));
        assertEquals(Integer.MAX_VALUE, BoundedQuantifiers.bound("2147483647"));
        assertEquals(Integer.MIN_VALUE, BoundedQuantifiers.bound("2147483648"));
        assertEquals(1, BoundedQuantifiers.bound("0".repeat(1000) + "1"));
    }

    @Test public void nonAsciiBoundsCannotBeConvertedToNumbers() {
        for (String bound : new String[] {"１", "２", "٣", "1２", "２1", "/", ":"}) {
            assertThrows(bound, NumberFormatException.class, () -> BoundedQuantifiers.bound(bound));
        }
    }

    @Test public void sharedWhitespaceFixtureKeepsBoundsSpansAndJavaHostRustOutput() throws Exception {
        String fixture = Files.readString(Path.of("../rust/unlaxer-ubnf/tests/fixtures/positive/quantifier-whitespace.ubnf"));
        String[] names = {"Exact", "BeforeComma", "BeforeClose", "AfterComma", "AllSpaces", "Open",
            "ExactComments", "RangeComments", "OpenComments", "LineBreaks", "Whitespace", "MultipleDigits"};
        String[] bounds = {"2", "2,3", "2,3", "2,3", "2,3", "2,", "2", "2,3", "2,", "2,3", "2,3", "10,12"};
        StringBuilder compact = new StringBuilder("grammar QuantifierWhitespace { @root @mapping(ExactNode) ");
        for (int i = 0; i < names.length; i++) compact.append(names[i]).append(" ::= 'x'{").append(bounds[i]).append("}; ");
        var expected = UBNFMapper.parse(compact.append("}").toString());
        var expectedRust = new RustBackend().generate(expected.grammars().get(0));
        for (String source : List.of(fixture, fixture.replace("\n", "\r\n"))) {
            var snapshot = UBNFMapper.parseWithSource(source);
            assertEquals(expected, UBNFMapper.parse(source));
            assertEquals(expected, snapshot.ast());
            var grammar = snapshot.ast().grammars().get(0);
            assertEquals(List.of(), GrammarValidator.validate(grammar));
            // Java-host Rust must emit the same five files as the compact grammar.
            assertEquals(expectedRust, new RustBackend().generate(grammar));
            for (var rule : grammar.rules()) {
                var repeat = (BoundedRepeatElement) ((ChoiceBody) rule.body()).alternatives().get(0).elements().get(0).element();
                int start = source.indexOf("'x'", source.indexOf(rule.name() + " ::="));
                int end = source.indexOf(';', start);
                assertEquals(rule.name(), new UBNFSourceSnapshot.Span(source.codePointCount(0, start),
                    source.codePointCount(0, end)), snapshot.spanOf(repeat).orElseThrow());
                assertEquals(rule.name(), source.substring(start, end), snapshot.sourceOf(repeat).orElseThrow());
            }
        }
    }

    @Test public void sharedNegativeBoundsFailBeforeJavaHostRustGeneration() throws Exception {
        for (String row : Files.readAllLines(Path.of("../rust/unlaxer-ubnf/tests/fixtures/negative.tsv"))) {
            String[] parts = row.split("\t", 2);
            if (!parts[0].startsWith("split-") && !parts[0].startsWith("quantifier-")) continue;
            for (boolean withSource : List.of(false, true)) {
                var error = assertThrows(parts[0], IllegalArgumentException.class, () -> {
                    var ast = withSource ? UBNFMapper.parseWithSource(parts[1]).ast() : UBNFMapper.parse(parts[1]);
                    new RustBackend().generateFile(ast, false, false);
                });
                assertTrue(error.getMessage(), error.getMessage().startsWith("UBNF パース失敗"));
            }
        }
    }
}
