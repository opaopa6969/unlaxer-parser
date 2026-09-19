package org.unlaxer.dsl;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

public class RustScopeEffectsLoweringTest {
    private static final String GRAMMAR = """
        grammar G {
          token ID = IdentifierParser
          @root @mapping(Root) @scopeTree(mode=dynamic)
          Root ::= Decl '|' Ref ;
          @declares(symbol=name, description=documentation)
          Decl ::= ID @noise ':' ID @name ;
          @backref(name=name)
          Ref ::= ID @name ;
        }
        """;

    @Test public void metadataAndUnmappedCapturesSurviveLowering() {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        var ir = RustGrammarLowering.lower(grammar);
        var scope = (GrammarIR.RuleEffects) ir.rules().get(0).body();
        assertEquals(GrammarIR.ScopeMode.DYNAMIC, scope.effects().scopeMode());
        var declaration = ((GrammarIR.RuleEffects) ir.rules().get(1).body()).effects().declares();
        assertEquals("name", declaration.symbolCapture());
        assertEquals("documentation", declaration.description());
        String parser = new RustBackend().generate(grammar).stream()
            .filter(file -> file.relativePath().equals("parser.rs")).findFirst().orElseThrow().content();
        assertTrue(parser.contains("Some(unlaxer_runtime::ScopeMode::Dynamic)"));
        assertTrue(parser.contains("description: Some(\"documentation\")"));
        assertTrue(parser.contains("backref: Some(\"name\")"));
        for (String name : new String[]{"_name", "self", "span", "semantics"}) {
            String source = GRAMMAR.replace("symbol=name", "symbol=" + name)
                .replace("name=name", "name=" + name).replace("@name", "@" + name);
            new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
        }
    }

    @Test public void malformedEffectsAndUnsafeBodiesAreRejected() {
        for (String source : new String[] {
            GRAMMAR.replace("mode=dynamic", "mode=unknown"),
            GRAMMAR.replace("@scopeTree(mode=dynamic)", "@scopeTree(mode=dynamic) @scopeTree(mode=lexical)"),
            GRAMMAR.replace("@declares(symbol=name, description=documentation)", "@declares(symbol=name) @declares(symbol=name)"),
            GRAMMAR.replace("@backref(name=name)", "@backref(name=name) @backref(name=name)"),
            GRAMMAR.replace("symbol=name", "symbol=missing"),
            GRAMMAR.replace("@backref(name=name)", "@backref(name=missing)"),
            GRAMMAR.replace("@scopeTree(mode=dynamic)", ""),
            GRAMMAR.replace("Decl ::= ID @noise ':' ID @name", "Decl ::= { [ ID ] } @name"),
            GRAMMAR.replace("Decl ::= ID @noise ':' ID @name", "Decl ::= Decl @name")
        }) {
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            assertThrows(source, IllegalArgumentException.class, () -> RustGrammarLowering.lower(grammar));
        }
    }
}
