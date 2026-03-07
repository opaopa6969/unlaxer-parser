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
    // T2-1: NEGATION  /  T2-2: LOOKAHEAD  /  T2-3: NEGATIVE_LOOKAHEAD
    // =========================================================================

    @Test
    public void testTokenDecl_negation() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token NOT_QUOTE = NEGATION('\"')\n"
            + "  @root\n"
            + "  Rule ::= NOT_QUOTE ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("NEGATION token should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_lookahead() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token COLON_AHEAD = LOOKAHEAD(':')\n"
            + "  @root\n"
            + "  Rule ::= COLON_AHEAD ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("LOOKAHEAD token should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_negativeLookahead() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token NOT_SLASH = NEGATIVE_LOOKAHEAD('//')\n"
            + "  @root\n"
            + "  Rule ::= NOT_SLASH ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("NEGATIVE_LOOKAHEAD token should parse successfully", parsed.isSucceeded());
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

    // =========================================================================
    // ERROR element
    // =========================================================================

    @Test
    public void testErrorElement() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID | ERROR('expected identifier') ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ERROR('msg') should parse successfully", parsed.isSucceeded());
    }

    // =========================================================================
    // Token keywords: ANY, EOF, EMPTY, CHAR_RANGE, CI
    // =========================================================================

    @Test
    public void testTokenDecl_any() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token ANY_CHAR = ANY");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_eof() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token FILE_END = EOF");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_empty() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token EMPTY_MATCH = EMPTY");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_charRange() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token LOWER = CHAR_RANGE('a','z')");
        assertTrue(parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_caseInsensitive() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token KW_IF = CI('if')");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // @doc annotation
    // =========================================================================

    @Test
    public void testAnnotation_doc() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class),
            "@doc('this rule parses an expression')");
        assertTrue(parsed.isSucceeded());
    }

    // =========================================================================
    // Bounded quantifiers: {n}  {n,m}  {n,}
    // =========================================================================

    @Test
    public void testAnnotatedElement_exactQuantifier() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID{3} ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ID{3} should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testAnnotatedElement_rangeQuantifier() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID{1,3} ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ID{1,3} should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testAnnotatedElement_openEndedQuantifier() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID{2,} ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ID{2,} should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testAnnotatedElement_asteriskQuantifier() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID* ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("ID* should parse successfully", parsed.isSucceeded());
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

    // =========================================================================
    // T4-5: % separator
    // =========================================================================

    @Test
    public void testAnnotatedElement_separatedByLiteral() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= ID % ',' ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("elem % sep should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testAnnotatedElement_separatedByRuleRef() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token ID = IdentifierParser\n"
            + "  @root\n"
            + "  Rule ::= Expr % Sep ;\n"
            + "  Expr ::= ID ;\n"
            + "  Sep ::= ',' ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("elem % RuleRef should parse successfully", parsed.isSucceeded());
    }

    // =========================================================================
    // T4-9: @skip annotation
    // =========================================================================

    @Test
    public void testAnnotation_skip() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class), "@skip");
        assertTrue("@skip annotation should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testRuleDecl_withSkip() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token COMMA = IdentifierParser\n"
            + "  @skip\n"
            + "  @root\n"
            + "  Comma ::= ',' ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("@skip rule should parse successfully", parsed.isSucceeded());
    }

    // =========================================================================
    // T4-3: REGEX token
    // =========================================================================

    @Test
    public void testTokenDecl_regex() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token ID = REGEX('[a-zA-Z_][a-zA-Z0-9_]*')");
        assertTrue("REGEX token should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testTokenDecl_regexDigits() {
        Parsed parsed = parse(Parser.get(UBNFParsers.TokenDeclParser.class),
            "token DIGITS = REGEX('[0-9]+')");
        assertTrue("REGEX digits token should parse successfully", parsed.isSucceeded());
    }

    // =========================================================================
    // @declares annotation
    // =========================================================================

    @Test
    public void testAnnotation_declares() {
        Parsed parsed = parse(Parser.get(UBNFParsers.AnnotationParser.class),
            "@declares(symbol=varName)");
        assertTrue("@declares annotation should parse successfully", parsed.isSucceeded());
    }

    @Test
    public void testRuleDecl_withDeclares() {
        String input = "grammar G {\n"
            + "  @whitespace: javaStyle\n"
            + "  token VARNAME = IdentifierParser\n"
            + "  @declares(symbol=varName)\n"
            + "  @root\n"
            + "  VarDecl ::= 'let' VARNAME @varName ';' ;\n"
            + "}";
        Parsed parsed = parse(UBNFParsers.getRootParser(), input);
        assertTrue("@declares rule should parse successfully", parsed.isSucceeded());
    }
}
