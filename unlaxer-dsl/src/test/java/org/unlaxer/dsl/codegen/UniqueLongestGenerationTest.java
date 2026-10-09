package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

public class UniqueLongestGenerationTest {
    private static final String GRAMMAR = "grammar Unique { @root @mapping(Root) @uniqueLongestChoice Root ::= 'a' | 'ab'; }";
    @Test public void emitsBothRuntimeProfiles() {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        assertTrue(new ParserGenerator().generate(grammar).source().contains("extends LazyUniqueLongestChoice"));
        assertTrue(new RustBackend().generate(grammar).stream().anyMatch(file -> file.content().contains("Expr::UniqueLongestChoice(vec![")));
    }
    @Test public void rejectsMixedDuplicateShapeAndExcessiveCandidates() {
        check(GRAMMAR.replace("@uniqueLongestChoice", "@uniqueLongestChoice @uniqueLongestChoice"), "E-UNIQUE-LONGEST-DUPLICATE");
        for (String annotation : new String[]{"@longestChoice", "@predictiveChoice", "@leftAssoc", "@rightAssoc"})
            check(GRAMMAR.replace("@uniqueLongestChoice", "@uniqueLongestChoice " + annotation), "E-UNIQUE-LONGEST-CONFLICT");
        check(GRAMMAR.replace("'a' | 'ab'", "'a'"), "E-UNIQUE-LONGEST-SHAPE");
        check(GRAMMAR.replace("'a' | 'ab'", String.join(" | ", java.util.Collections.nCopies(65, "'a'"))), "E-UNIQUE-LONGEST-LIMIT");
    }
    private static void check(String source, String code) {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        assertTrue(code, GrammarValidator.validate(grammar).stream().anyMatch(issue -> issue.code().equals(code)));
        try { new RustBackend().generate(grammar); fail("Rust backend must reject " + code); }
        catch (IllegalArgumentException expected) { }
        try { new ParserGenerator().generate(grammar); fail("Java backend must reject " + code); }
        catch (IllegalArgumentException expected) { }
    }
}
