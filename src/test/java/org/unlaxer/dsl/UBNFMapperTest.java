package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.InterleaveAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.LeftAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.PrecedenceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RightAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ScopeTreeAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class UBNFMapperTest {

    // =========================================================================
    // GrammarDecl — 名前・設定・トークン
    // =========================================================================

    @Test
    public void testGrammarDecl_name() {
        UBNFFile file = UBNFMapper.parse("grammar MyGrammar {\n  Rule ::= 'x' ;\n}");
        assertEquals(1, file.grammars().size());
        assertEquals("MyGrammar", file.grammars().get(0).name());
    }

    @Test
    public void testGrammarDecl_globalSetting_string() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @package: org.example.gen\n"
            + "  Rule ::= 'x' ;\n"
            + "}");
        GrammarDecl grammar = file.grammars().get(0);
        assertEquals(1, grammar.settings().size());
        assertEquals("package", grammar.settings().get(0).key());
        assertTrue(grammar.settings().get(0).value() instanceof StringSettingValue);
        assertEquals("org.example.gen", ((StringSettingValue) grammar.settings().get(0).value()).value());
    }

    @Test
    public void testGrammarDecl_tokenDecl() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  token NUMBER = NumberParser\n"
            + "  token ID     = IdentifierParser\n"
            + "  Rule ::= NUMBER ;\n"
            + "}");
        GrammarDecl grammar = file.grammars().get(0);
        List<TokenDecl> tokens = grammar.tokens();
        assertEquals(2, tokens.size());
        assertEquals("NUMBER", tokens.get(0).name());
        assertEquals("NumberParser", tokens.get(0).parserClass());
        assertEquals("ID", tokens.get(1).name());
    }

    @Test
    public void testGrammarDecl_tokenDecl_until() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  token CODE_BODY = UNTIL('```')\n"
            + "  Rule ::= CODE_BODY ;\n"
            + "}");
        GrammarDecl grammar = file.grammars().get(0);
        List<TokenDecl> tokens = grammar.tokens();
        assertEquals(1, tokens.size());
        TokenDecl token = tokens.get(0);
        assertEquals("CODE_BODY", token.name());
        assertNull(token.parserClass()); // Until tokens return null for parserClass()
        assertTrue(token instanceof TokenDecl.Until);
        assertEquals("```", ((TokenDecl.Until) token).terminator());
    }

    // =========================================================================
    // RuleDecl — アノテーション
    // =========================================================================

    @Test
    public void testRuleDecl_annotations_root() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @root\n"
            + "  MyRule ::= 'hello' ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        assertEquals("MyRule", rule.name());
        assertEquals(1, rule.annotations().size());
        assertTrue(rule.annotations().get(0) instanceof RootAnnotation);
    }

    @Test
    public void testRuleDecl_annotations_leftAssoc() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @leftAssoc\n"
            + "  Expr ::= Term ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        assertEquals(1, rule.annotations().size());
        assertTrue(rule.annotations().get(0) instanceof LeftAssocAnnotation);
    }

    @Test
    public void testRuleDecl_annotations_precedence() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @precedence(level=42)\n"
            + "  Expr ::= Term ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        assertEquals(1, rule.annotations().size());
        assertTrue(rule.annotations().get(0) instanceof PrecedenceAnnotation);
        assertEquals(42, ((PrecedenceAnnotation) rule.annotations().get(0)).level());
    }

    @Test
    public void testRuleDecl_annotations_rightAssoc() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @rightAssoc\n"
            + "  Expr ::= Term ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        assertEquals(1, rule.annotations().size());
        assertTrue(rule.annotations().get(0) instanceof RightAssocAnnotation);
    }

    @Test
    public void testRuleDecl_annotations_interleave() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @interleave(profile=javaStyle)\n"
            + "  Expr ::= Term ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        assertEquals(1, rule.annotations().size());
        assertTrue(rule.annotations().get(0) instanceof InterleaveAnnotation);
        assertEquals("javaStyle", ((InterleaveAnnotation) rule.annotations().get(0)).profile());
    }

    @Test
    public void testRuleDecl_annotations_backref() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @backref(name=ident)\n"
            + "  Expr ::= Term ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        assertEquals(1, rule.annotations().size());
        assertTrue(rule.annotations().get(0) instanceof BackrefAnnotation);
        assertEquals("ident", ((BackrefAnnotation) rule.annotations().get(0)).name());
    }

    @Test
    public void testRuleDecl_annotations_scopeTree() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @scopeTree(mode=lexical)\n"
            + "  Expr ::= Term ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        assertEquals(1, rule.annotations().size());
        assertTrue(rule.annotations().get(0) instanceof ScopeTreeAnnotation);
        assertEquals("lexical", ((ScopeTreeAnnotation) rule.annotations().get(0)).mode());
    }

    @Test
    public void testRuleDecl_annotations_mapping_noParams() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @mapping(ExprNode)\n"
            + "  Expr ::= Term ;\n"
            + "}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        MappingAnnotation mapping = (MappingAnnotation) rule.annotations().get(0);
        assertEquals("ExprNode", mapping.className());
        assertTrue(mapping.paramNames().isEmpty());
    }

    @Test
    public void testRuleDecl_annotations_mapping_withParams() {
        UBNFFile file = UBNFMapper.parse(
            "grammar G {\n"
            + "  @mapping(BinaryExpr, params=[left, op, right])\n"
            + "  Expr ::= Term ;\n"
            + "}");
        MappingAnnotation mapping = (MappingAnnotation) file.grammars().get(0).rules().get(0).annotations().get(0);
        assertEquals("BinaryExpr", mapping.className());
        assertEquals(List.of("left", "op", "right"), mapping.paramNames());
    }

    // =========================================================================
    // RuleBody — SequenceBody / ChoiceBody
    // =========================================================================

    @Test
    public void testRuleBody_simpleRuleRef() {
        UBNFFile file = UBNFMapper.parse("grammar G {\n  Rule ::= Expression ;\n}");
        RuleDecl rule = file.grammars().get(0).rules().get(0);
        ChoiceBody choiceBody = (ChoiceBody) rule.body();
        assertEquals(1, choiceBody.alternatives().size());
        SequenceBody seq = choiceBody.alternatives().get(0);
        assertEquals(1, seq.elements().size());
        AtomicElement element = seq.elements().get(0).element();
        assertTrue(element instanceof RuleRefElement);
        assertEquals("Expression", ((RuleRefElement) element).name());
    }

    @Test
    public void testRuleBody_terminalElement() {
        UBNFFile file = UBNFMapper.parse("grammar G {\n  Rule ::= 'grammar' ;\n}");
        ChoiceBody body = (ChoiceBody) file.grammars().get(0).rules().get(0).body();
        AtomicElement element = body.alternatives().get(0).elements().get(0).element();
        assertTrue(element instanceof TerminalElement);
        assertEquals("grammar", ((TerminalElement) element).value());
    }

    @Test
    public void testRuleBody_choice() {
        UBNFFile file = UBNFMapper.parse("grammar G {\n  Rule ::= A | B | C ;\n}");
        ChoiceBody body = (ChoiceBody) file.grammars().get(0).rules().get(0).body();
        assertEquals(3, body.alternatives().size());
    }

    @Test
    public void testRuleBody_repeatElement() {
        UBNFFile file = UBNFMapper.parse("grammar G {\n  Rule ::= { Item } ;\n}");
        ChoiceBody body = (ChoiceBody) file.grammars().get(0).rules().get(0).body();
        AtomicElement element = body.alternatives().get(0).elements().get(0).element();
        assertTrue(element instanceof RepeatElement);
    }

    @Test
    public void testRuleBody_groupElement() {
        UBNFFile file = UBNFMapper.parse("grammar G {\n  Rule ::= ( A | B ) ;\n}");
        ChoiceBody body = (ChoiceBody) file.grammars().get(0).rules().get(0).body();
        AtomicElement element = body.alternatives().get(0).elements().get(0).element();
        assertTrue(element instanceof GroupElement);
        ChoiceBody inner = (ChoiceBody) ((GroupElement) element).body();
        assertEquals(2, inner.alternatives().size());
    }

    // =========================================================================
    // フル tinycalc UBNF
    // =========================================================================

    @Test
    public void testFullTinycalc() {
        String input = "// TinyCalc\n"
            + "grammar TinyCalc {\n"
            + "  @package: org.example\n"
            + "  @whitespace: javaStyle\n"
            + "  token NUMBER = NumberParser\n"
            + "  token IDENTIFIER = IdentifierParser\n"
            + "  @root\n"
            + "  @mapping(TinyCalcProgram, params=[declarations, expression])\n"
            + "  TinyCalc ::= { VariableDeclaration } @declarations Expression @expression ;\n"
            + "  @mapping(BinaryExpr, params=[left, op, right])\n"
            + "  @leftAssoc\n"
            + "  Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;\n"
            + "  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;\n"
            + "  Factor ::= '(' Expression ')' | NUMBER | IDENTIFIER ;\n"
            + "}";

        UBNFFile file = UBNFMapper.parse(input);

        GrammarDecl grammar = file.grammars().get(0);
        assertEquals("TinyCalc", grammar.name());
        assertEquals(2, grammar.settings().size());
        assertEquals(2, grammar.tokens().size());
        assertEquals(4, grammar.rules().size());

        RuleDecl tinyCalcRule = grammar.rules().get(0);
        assertEquals("TinyCalc", tinyCalcRule.name());
        assertEquals(2, tinyCalcRule.annotations().size());
        assertTrue(tinyCalcRule.annotations().get(0) instanceof RootAnnotation);
        assertTrue(tinyCalcRule.annotations().get(1) instanceof MappingAnnotation);

        RuleDecl exprRule = grammar.rules().get(1);
        assertEquals("Expression", exprRule.name());
        assertEquals(2, exprRule.annotations().size());
        assertTrue(exprRule.annotations().get(0) instanceof MappingAnnotation);
        assertTrue(exprRule.annotations().get(1) instanceof LeftAssocAnnotation);

        RuleDecl factorRule = grammar.rules().get(3);
        assertEquals("Factor", factorRule.name());
        ChoiceBody factorBody = (ChoiceBody) factorRule.body();
        assertEquals(3, factorBody.alternatives().size());
    }

    // =========================================================================
    // Postfix quantifier AST: + and ?
    // =========================================================================

    // =========================================================================
    // エスケープシーケンス
    // =========================================================================

    @Test
    public void testProcessEscapes_newline() {
        assertEquals("\n", UBNFMapper.processEscapes("\\n"));
    }

    @Test
    public void testProcessEscapes_tab() {
        assertEquals("\t", UBNFMapper.processEscapes("\\t"));
    }

    @Test
    public void testProcessEscapes_backslash() {
        assertEquals("\\", UBNFMapper.processEscapes("\\\\"));
    }

    @Test
    public void testProcessEscapes_singleQuote() {
        assertEquals("'", UBNFMapper.processEscapes("\\'"));
    }

    @Test
    public void testProcessEscapes_noEscape_fastPath() {
        assertEquals("hello", UBNFMapper.processEscapes("hello"));
    }

    @Test
    public void testProcessEscapes_mixed() {
        assertEquals("a\tb\nc", UBNFMapper.processEscapes("a\\tb\\nc"));
    }

    // =========================================================================
    // Postfix quantifier AST: + and ?
    // =========================================================================

    @Test
    public void testPostfixPlus_producesOneOrMoreElement() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID+ ;\n"
            + "}";
        GrammarDecl grammar = UBNFMapper.parse(input).grammars().get(0);
        RuleDecl rule = grammar.rules().get(0);
        ChoiceBody choice = (ChoiceBody) rule.body();
        SequenceBody seq = choice.alternatives().get(0);
        AtomicElement element = seq.elements().get(0).element();
        assertTrue("ID+ should produce OneOrMoreElement", element instanceof OneOrMoreElement);
    }

    @Test
    public void testPostfixPlus_innerIsRuleRef() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID+ ;\n"
            + "}";
        GrammarDecl grammar = UBNFMapper.parse(input).grammars().get(0);
        RuleDecl rule = grammar.rules().get(0);
        ChoiceBody choice = (ChoiceBody) rule.body();
        SequenceBody seq = choice.alternatives().get(0);
        OneOrMoreElement one = (OneOrMoreElement) seq.elements().get(0).element();
        SequenceBody inner = (SequenceBody) one.body();
        assertTrue("inner element should be RuleRefElement",
            inner.elements().get(0).element() instanceof RuleRefElement);
    }

    @Test
    public void testPostfixQuestion_producesOptionalElement() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID? ;\n"
            + "}";
        GrammarDecl grammar = UBNFMapper.parse(input).grammars().get(0);
        RuleDecl rule = grammar.rules().get(0);
        ChoiceBody choice = (ChoiceBody) rule.body();
        SequenceBody seq = choice.alternatives().get(0);
        AtomicElement element = seq.elements().get(0).element();
        assertTrue("ID? should produce OptionalElement", element instanceof OptionalElement);
    }

    @Test
    public void testSeparatedElement_producesCorrectAST() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID % ',' ;\n"
            + "}";
        GrammarDecl grammar = UBNFMapper.parse(input).grammars().get(0);
        RuleDecl rule = grammar.rules().get(0);
        ChoiceBody choice = (ChoiceBody) rule.body();
        SequenceBody seq = choice.alternatives().get(0);
        AtomicElement element = seq.elements().get(0).element();
        assertTrue("ID % ',' should produce SeparatedElement", element instanceof SeparatedElement);
        SeparatedElement sep = (SeparatedElement) element;
        assertTrue("element should be RuleRefElement", sep.element() instanceof RuleRefElement);
        assertTrue("separator should be TerminalElement", sep.separator() instanceof TerminalElement);
    }

    @Test
    public void testSkipAnnotation_parsedCorrectly() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  @skip\n"
            + "  @root\n"
            + "  Comma ::= ',' ;\n"
            + "}";
        GrammarDecl grammar = UBNFMapper.parse(input).grammars().get(0);
        RuleDecl rule = grammar.rules().get(0);
        boolean hasSkip = rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation);
        assertTrue("@skip should produce SkipAnnotation", hasSkip);
    }

    @Test
    public void testTokenDecl_regex() {
        String input = "grammar G {\n"
            + "  token ID = REGEX('[a-z]+')\n"
            + "  @root\n"
            + "  Rule ::= ID ;\n"
            + "}";
        GrammarDecl grammar = UBNFMapper.parse(input).grammars().get(0);
        TokenDecl token = grammar.tokens().get(0);
        assertTrue("REGEX token should produce TokenDecl.Regex", token instanceof TokenDecl.Regex);
        assertEquals("[a-z]+", ((TokenDecl.Regex) token).pattern());
    }
}
