package org.unlaxer.dsl.init;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class TmLanguageEmitterTest {

    private static final String SAMPLE = """
        grammar Sample {
          @package: org.example.sample
          @whitespace: javaStyle

          token NUMBER     = NumberParser
          token IDENTIFIER = IdentifierParser

          @root
          Program ::= { Stmt } ;

          Stmt ::=
              'var' IDENTIFIER '=' Expression ';'
            | 'if' '(' Expression ')' Stmt
            | Expression ';' ;

          Expression ::= Term { ( '+' | '-' ) Term } ;
          Term       ::= Factor { ( '*' | '/' ) Factor } ;
          Factor     ::= NUMBER | IDENTIFIER | '(' Expression ')' ;
        }
        """;

    @Test
    public void testEmitContainsKeywordsAndOperators() {
        UBNFFile ubnf = UBNFMapper.parse(SAMPLE);
        var grammar = ubnf.grammars().get(0);
        String json = TmLanguageEmitter.emit(grammar, "sample");

        // contains grammar metadata
        assertTrue(json.contains("\"name\": \"Sample\""));
        assertTrue(json.contains("\"scopeName\": \"source.sample\""));

        // word keywords should be in the keywords block
        assertTrue("expected 'var' as keyword", json.contains("var"));
        assertTrue("expected 'if' as keyword", json.contains("if"));
        assertTrue("expected keyword.control scope", json.contains("keyword.control.sample"));

        // operators and punctuation
        assertTrue("expected operators block", json.contains("keyword.operator.sample"));

        // common scopes
        assertTrue(json.contains("comment.line.double-slash.sample"));
        assertTrue(json.contains("comment.block.sample"));
        assertTrue(json.contains("constant.numeric.sample"));
        assertTrue(json.contains("variable.other.sample"));
    }

    @Test
    public void testEmitNoWordKeywordsStillProducesValidShell() {
        // grammar with only operators (no word terminals) — should not emit keywords block
        String src = """
            grammar OpsOnly {
              @package: org.example.x
              @whitespace: javaStyle
              token NUMBER = NumberParser
              @root
              Expr ::= NUMBER { '+' NUMBER } ;
            }
            """;
        UBNFFile ubnf = UBNFMapper.parse(src);
        var grammar = ubnf.grammars().get(0);
        String json = TmLanguageEmitter.emit(grammar, "ops");

        assertFalse("should not emit keyword.control when no word keywords exist",
            json.contains("keyword.control.ops"));
        assertTrue(json.contains("keyword.operator.ops"));
    }
}
