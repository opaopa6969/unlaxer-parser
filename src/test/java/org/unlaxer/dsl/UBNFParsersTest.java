package org.unlaxer.dsl;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFParsers;
import org.unlaxer.parser.Parser;

public class UBNFParsersTest {

    private Parsed parse(Parser parser, String input) {
        StringSource source = StringSource.createRootSource(input);
        try (ParseContext context = new ParseContext(source)) {
            return parser.parse(context);
        }
    }

    // =========================================================================
    // IDENTIFIER
    // =========================================================================

    @Test
    public void testIdentifier_simple() {
        Parsed parsed = parse(Parser.get(UBNFParsers.IdentifierParser.class), "FooBar");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testIdentifier_withUnderscore() {
        Parsed parsed = parse(Parser.get(UBNFParsers.IdentifierParser.class), "foo_bar_123");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // DottedIdentifier
    // =========================================================================

    @Test
    public void testDottedIdentifier_simple() {
        Parsed parsed = parse(Parser.get(UBNFParsers.DottedIdentifierParser.class), "javaStyle");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testDottedIdentifier_dotted() {
        Parsed parsed = parse(Parser.get(UBNFParsers.DottedIdentifierParser.class),
            "org.unlaxer.dsl.bootstrap");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // TerminalElement（シングルクォート文字列）
    // =========================================================================

    @Test
    public void testTerminalElement() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TerminalElementParser.class), "'grammar'");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // GlobalSetting
    // =========================================================================

    @Test
    public void testGlobalSetting_stringValue() {
        Parsed parsed = parse(Parser.get(UBNFParsers.GlobalSettingParser.class),
            "@whitespace: javaStyle");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testGlobalSetting_packageValue() {
        Parsed parsed = parse(Parser.get(UBNFParsers.GlobalSettingParser.class),
            "@package: org.unlaxer.dsl.bootstrap");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testGlobalSetting_blockValue() {
        Parsed parsed = parse(Parser.get(UBNFParsers.GlobalSettingParser.class),
            "@comment: { line: '//' }");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // TokenDecl
    // =========================================================================

    @Test
    public void testTokenDecl() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token NUMBER = NumberParser");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_until_simple() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token CODE_BODY = UNTIL('```')");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_until_fqn_class() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token CODE_BODY = org.example.SomeParser");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // Annotation
    // =========================================================================

    @Test
    public void testAnnotation_root() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@root");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_leftAssoc() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@leftAssoc");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_rightAssoc() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@rightAssoc");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_precedence() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@precedence(level=10)");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_interleave() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@interleave(profile=javaStyle)");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_backref() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@backref(name=ident)");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_scopeTree() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@scopeTree(mode=lexical)");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_mapping_noParams() {
        Parsed parsed = parse(Parser.get(UBNFParsers.MappingAnnotationParser.class),
            "@mapping(TinyCalcProgram)");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testAnnotation_mapping_withParams() {
        Parsed parsed = parse(Parser.get(UBNFParsers.MappingAnnotationParser.class),
            "@mapping(TinyCalcProgram, params=[declarations, expression])");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // RuleBody
    // =========================================================================

    @Test
    public void testRuleBody_simpleRef() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleBodyParser.class), "Expression");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testRuleBody_choice() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleBodyParser.class),
            "NumberParser | IdentifierParser");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testRuleBody_sequence() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleBodyParser.class),
            "'grammar' IDENTIFIER '{'");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testRuleBody_optional() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleBodyParser.class),
            "['set' Expression]");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testRuleBody_repeat() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleBodyParser.class),
            "{ '+' Term }");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testRuleBody_withCaptureName() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleBodyParser.class),
            "IDENTIFIER @name");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // RuleDecl（';' ターミネータ必須）
    // =========================================================================

    @Test
    public void testRuleDecl_simple() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleDeclParser.class),
            "Expression ::= Term ;");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testRuleDecl_withAnnotations() {
        Parsed parsed = parse(Parser.get(UBNFParsers.RuleDeclParser.class),
            "@root\n@mapping(TinyCalcProgram, params=[decls, expr])\nTinyCalc ::= Declarations Expression ;");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // GrammarDecl（フル grammar ブロック）
    // =========================================================================

    @Test
    public void testGrammarDecl_minimal() {
        String input = "grammar Mini {\n"
            + "  SimpleRule ::= 'hello' ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getGrammarDeclParser(), input);
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testGrammarDecl_withSettings() {
        String input = "grammar Mini {\n"
            + "  @package: org.example\n"
            + "  @whitespace: javaStyle\n"
            + "  token NUMBER = NumberParser\n"
            + "  @root\n"
            + "  Expr ::= NUMBER ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getGrammarDeclParser(), input);
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // Postfix quantifiers: + and ?
    // =========================================================================

    @Test
    public void testAnnotatedElement_plusQuantifier() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID+ ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ID+ should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testAnnotatedElement_questionMarkQuantifier() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID? ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ID? should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testAnnotatedElement_plusOnTerminal() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= 'x'+ ID ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("'x'+ should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testAnnotatedElement_plusWithCapture() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  @mapping(FooExpr, params=[items])\n"
            + "  Rule ::= ID+ @items ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ID+ @capture should parse successfully", parsed.isSucceeded());
    }

    // =========================================================================
    // UBNFFile（tinycalc.ubnf の内容でフル検証）
    // =========================================================================

    @Test
    public void testUBNFFile_tinycalc() {
        String input = "// TinyCalc sample\n"
            + "grammar TinyCalc {\n"
            + "  @package: org.unlaxer.tinycalc.generated\n"
            + "  @whitespace: javaStyle\n"
            + "\n"
            + "  token NUMBER     = NumberParser\n"
            + "  token IDENTIFIER = IdentifierParser\n"
            + "\n"
            + "  @root\n"
            + "  @mapping(TinyCalcProgram, params=[declarations, expression])\n"
            + "  TinyCalc ::=\n"
            + "    { VariableDeclaration } @declarations\n"
            + "    Expression @expression ;\n"
            + "\n"
            + "  @mapping(BinaryExpr, params=[left, op, right])\n"
            + "  @leftAssoc\n"
            + "  Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;\n"
            + "\n"
            + "  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;\n"
            + "\n"
            + "  Factor ::=\n"
            + "      '(' Expression ')'\n"
            + "    | NUMBER\n"
            + "    | IDENTIFIER ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue(parsed.isSucceeded());
    }
}
