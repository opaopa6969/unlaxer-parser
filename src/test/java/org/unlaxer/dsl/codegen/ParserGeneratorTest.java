package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class ParserGeneratorTest {

    private static final String TINYCALC_GRAMMAR =
        "grammar TinyCalc {\n" +
        "  @package: org.unlaxer.tinycalc.generated\n" +
        "  @whitespace: javaStyle\n" +
        "\n" +
        "  token NUMBER     = NumberParser\n" +
        "  token IDENTIFIER = IdentifierParser\n" +
        "\n" +
        "  @root\n" +
        "  @mapping(TinyCalcProgram, params=[declarations, expression])\n" +
        "  TinyCalc ::=\n" +
        "    { VariableDeclaration } @declarations\n" +
        "    Expression @expression ;\n" +
        "\n" +
        "  @mapping(VarDecl, params=[keyword, name, init])\n" +
        "  VariableDeclaration ::=\n" +
        "    ( 'var' | 'variable' ) @keyword\n" +
        "    IDENTIFIER @name\n" +
        "    [ 'set' Expression @init ]\n" +
        "    ';' ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;\n" +
        "\n" +
        "  Factor ::=\n" +
        "      '(' Expression ')'\n" +
        "    | NUMBER\n" +
        "    | IDENTIFIER ;\n" +
        "}";

    private static final String RIGHT_ASSOC_GRAMMAR =
        "grammar Pow {\n" +
        "  @package: org.example.pow\n" +
        "  @whitespace: javaStyle\n" +
        "  token NUMBER = NumberParser\n" +
        "  @root\n" +
        "  @mapping(PowNode, params=[left, op, right])\n" +
        "  @rightAssoc\n" +
        "  @precedence(level=30)\n" +
        "  Expr ::= Atom @left { '^' @op Expr @right } ;\n" +
        "  Atom ::= NUMBER ;\n" +
        "}";

    private static final String RIGHT_ASSOC_NON_CANONICAL_GRAMMAR =
        "grammar PowAlt {\n" +
        "  @package: org.example.pow\n" +
        "  @whitespace: javaStyle\n" +
        "  token NUMBER = NumberParser\n" +
        "  @root\n" +
        "  @mapping(PowNode, params=[left, op, right])\n" +
        "  @rightAssoc\n" +
        "  @precedence(level=30)\n" +
        "  Expr ::= Atom @left { '^' @op Atom @right } ;\n" +
        "  Atom ::= NUMBER ;\n" +
        "}";

    private static final String OPERATOR_TABLE_GRAMMAR =
        "grammar Ops {\n" +
        "  @package: org.example.ops\n" +
        "  @whitespace: javaStyle\n" +
        "  token NUMBER = NumberParser\n" +
        "  @root\n" +
        "  @mapping(ExprNode, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  @precedence(level=10)\n" +
        "  Expr ::= Term @left { '+' @op Term @right } ;\n" +
        "  @mapping(TermNode, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  @precedence(level=20)\n" +
        "  Term ::= Factor @left { '*' @op Factor @right } ;\n" +
        "  Factor ::= NUMBER ;\n" +
        "}";

    private static final String ADVANCED_ANNOTATION_GRAMMAR =
        "grammar Advanced {\n" +
        "  @package: org.example.advanced\n" +
        "  @whitespace: javaStyle\n" +
        "  @root\n" +
        "  @mapping(StartNode, params=[v])\n" +
        "  @interleave(profile=commentsAndSpaces)\n" +
        "  @scopeTree(mode=lexical)\n" +
        "  Start ::= 'ok' @v ;\n" +
        "  @backref(name=ident)\n" +
        "  Ref ::= Start ;\n" +
        "}";

    private static final String INTERLEAVE_COMMENTS_GRAMMAR =
        "grammar InterleaveOnly {\n" +
        "  @package: org.example.interleave\n" +
        "  @whitespace: javaStyle\n" +
        "  @root\n" +
        "  @interleave(profile=commentsAndSpaces)\n" +
        "  Start ::= 'ok' ;\n" +
        "}";

    // =========================================================================
    // パッケージ名・クラス名
    // =========================================================================

    @Test
    public void testPackageName() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ParserGenerator gen = new ParserGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("org.unlaxer.tinycalc.generated", result.packageName());
    }

    @Test
    public void testClassName() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ParserGenerator gen = new ParserGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("TinyCalcParsers", result.className());
    }

    // =========================================================================
    // 基本構造
    // =========================================================================

    @Test
    public void testContainsOuterClass() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain outer class declaration",
            source.contains("class TinyCalcParsers"));
    }

    @Test
    public void testContainsPackageDeclaration() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain package declaration",
            source.contains("package org.unlaxer.tinycalc.generated;"));
    }

    @Test
    public void testContainsGetRootParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain getRootParser method",
            source.contains("getRootParser()"));
    }

    @Test
    public void testGetRootParserReturnsTinyCalcParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("getRootParser should return TinyCalcParser",
            source.contains("Parser.get(TinyCalcParser.class)"));
    }

    // =========================================================================
    // ルールクラス
    // =========================================================================

    @Test
    public void testContainsTinyCalcParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain TinyCalcParser class",
            source.contains("class TinyCalcParser"));
    }

    @Test
    public void testContainsVariableDeclarationParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain VariableDeclarationParser class",
            source.contains("class VariableDeclarationParser"));
    }

    @Test
    public void testContainsExpressionParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain ExpressionParser class",
            source.contains("class ExpressionParser"));
    }

    @Test
    public void testContainsTermParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain TermParser class",
            source.contains("class TermParser"));
    }

    @Test
    public void testContainsFactorParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain FactorParser class",
            source.contains("class FactorParser"));
    }

    // =========================================================================
    // 要素変換
    // =========================================================================

    @Test
    public void testContainsWordParserForVar() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain WordParser for 'var'",
            source.contains("WordParser(\"var\")"));
    }

    @Test
    public void testContainsWordParserForVariable() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain WordParser for 'variable'",
            source.contains("WordParser(\"variable\")"));
    }

    @Test
    public void testContainsParserGetExpressionParser() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain Parser.get(ExpressionParser.class)",
            source.contains("Parser.get(ExpressionParser.class)"));
    }

    @Test
    public void testContainsZeroOrMore() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain ZeroOrMore for repeat elements",
            source.contains("ZeroOrMore"));
    }

    @Test
    public void testRightAssocRuleUsesRecursiveChoice() {
        String source = generate(RIGHT_ASSOC_GRAMMAR);
        assertTrue("right-assoc rule should be generated as choice",
            source.contains("class ExprParser extends LazyChoice"));
        assertTrue("right-assoc recursive branch should reference itself",
            source.contains("Parser.get(ExprParser.class)"));
    }

    @Test
    public void testRightAssocNonCanonicalRuleFallsBackToGrammarDriven() {
        String source = generate(RIGHT_ASSOC_NON_CANONICAL_GRAMMAR);
        assertFalse("non-canonical right-assoc should not force recursive choice rewrite",
            source.contains("class ExprParser extends LazyChoice"));
        assertTrue("non-canonical right-assoc should keep repeat parser generation",
            source.contains("new ZeroOrMore("));
    }

    @Test
    public void testGeneratesPrecedenceConstants() {
        String source = generate(RIGHT_ASSOC_GRAMMAR);
        assertTrue("should expose precedence metadata as constant",
            source.contains("public static final int PRECEDENCE_EXPR = 30;"));
    }

    @Test
    public void testGeneratesOperatorMetadataApi() {
        String source = generate(RIGHT_ASSOC_GRAMMAR);
        assertTrue("should generate associativity enum",
            source.contains("enum Assoc { LEFT, RIGHT, NONE }"));
        assertTrue("should generate precedence lookup",
            source.contains("getPrecedence(String ruleName)"));
        assertTrue("should map Expr precedence",
            source.contains("new OperatorSpec(\"Expr\", 30, Assoc.RIGHT)"));
        assertTrue("should generate operator spec lookup API",
            source.contains("getOperatorSpec(String ruleName)"));
        assertTrue("should generate operator rule predicate",
            source.contains("isOperatorRule(String ruleName)"));
        assertTrue("should generate next-higher-precedence helper",
            source.contains("getNextHigherPrecedence(String ruleName)"));
        assertTrue("should generate lowest-precedence operator helper",
            source.contains("getLowestPrecedenceOperator()"));
        assertTrue("should generate precedence-level listing helper",
            source.contains("getPrecedenceLevels()"));
        assertTrue("should generate operator-at-precedence helper",
            source.contains("getOperatorsAtPrecedence(int precedence)"));
        assertTrue("should generate operator parser resolver",
            source.contains("getOperatorParser(String ruleName)"));
        assertTrue("should generate parser-at-precedence helper",
            source.contains("getOperatorParsersAtPrecedence(int precedence)"));
        assertTrue("should generate lowest-precedence parser helper",
            source.contains("getLowestPrecedenceParser()"));
        assertTrue("should generate operator spec table",
            source.contains("getOperatorSpecs()"));
    }

    @Test
    public void testOperatorSpecsAreSortedByPrecedence() {
        String source = generate(OPERATOR_TABLE_GRAMMAR);
        int exprIdx = source.indexOf("new OperatorSpec(\"Expr\", 10, Assoc.LEFT)");
        int termIdx = source.indexOf("new OperatorSpec(\"Term\", 20, Assoc.LEFT)");
        assertTrue("Expr spec should exist", exprIdx >= 0);
        assertTrue("Term spec should exist", termIdx >= 0);
        assertTrue("lower precedence spec should appear first", exprIdx < termIdx);
    }

    @Test
    public void testGeneratesAdvancedAnnotationMetadataApi() {
        String source = generate(ADVANCED_ANNOTATION_GRAMMAR);
        assertTrue("should generate interleave profile lookup",
            source.contains("getInterleaveProfile(String ruleName)"));
        assertTrue("should generate backref name lookup",
            source.contains("getBackrefName(String ruleName)"));
        assertTrue("should generate scope tree mode lookup",
            source.contains("getScopeTreeMode(String ruleName)"));
        assertTrue("should generate scope mode enum",
            source.contains("enum ScopeMode { LEXICAL, DYNAMIC }"));
        assertTrue("should generate scope tree spec record",
            source.contains("record ScopeTreeSpec(String ruleName, String scopeId, ScopeMode mode)"));
        assertTrue("should generate scope mode enum lookup",
            source.contains("getScopeTreeModeEnum(String ruleName)"));
        assertTrue("should map lexical scope mode into enum",
            source.contains("case \"lexical\" -> java.util.Optional.of(ScopeMode.LEXICAL)"));
        assertTrue("should map dynamic scope mode into enum",
            source.contains("case \"dynamic\" -> java.util.Optional.of(ScopeMode.DYNAMIC)"));
        assertTrue("should generate lexical scope helper",
            source.contains("isLexicalScopeTreeRule(String ruleName)"));
        assertTrue("should generate dynamic scope helper",
            source.contains("isDynamicScopeTreeRule(String ruleName)"));
        assertTrue("should generate all scope-tree rules helper",
            source.contains("getScopeTreeRules()"));
        assertTrue("should generate scope-tree rules by mode helper",
            source.contains("getScopeTreeRules(ScopeMode mode)"));
        assertTrue("should generate scope mode map helper",
            source.contains("getScopeTreeModeByRule()"));
        assertTrue("should generate string scope mode map helper by rule",
            source.contains("getScopeTreeModeNameByRule()"));
        assertTrue("should generate scope id helper",
            source.contains("getScopeIdForRule(String ruleName)"));
        assertTrue("should generate scope tree spec lookup",
            source.contains("getScopeTreeSpec(String ruleName)"));
        assertTrue("should generate scope tree spec list",
            source.contains("getScopeTreeSpecs()"));
        assertTrue("should generate scope tree spec map by rule",
            source.contains("getScopeTreeSpecByRule()"));
        assertTrue("should generate scope tree spec map by scope id",
            source.contains("getScopeTreeSpecByScopeId()"));
        assertTrue("should generate scope mode map by scope id",
            source.contains("getScopeTreeModeByScopeId()"));
        assertTrue("should generate string scope mode map helper by scope id",
            source.contains("getScopeTreeModeNameByScopeId()"));
        assertTrue("should generate synthetic scope event builder by rule metadata",
            source.contains("buildSyntheticScopeEventsForNodes(java.util.List<Object> nodes)"));
        assertTrue("should generate synthetic scope event builder with overrides",
            source.contains("buildSyntheticScopeEventsForNodes(\n        java.util.List<Object> nodes,\n        java.util.Map<String, ?> modeOverridesByRule"));
        assertTrue("should generate synthetic scope event builder by scope-id metadata",
            source.contains("buildSyntheticScopeEventsForNodesByScopeId(java.util.List<Object> nodes)"));
        assertTrue("should generate scope-tree existence helper",
            source.contains("hasScopeTree(String ruleName)"));
        assertTrue("should generate scope-tree mode default helper",
            source.contains("getScopeTreeModeOrDefault(String ruleName, ScopeMode fallback)"));
        assertTrue("should emit lexical scope-tree rule list",
            source.contains("case LEXICAL -> java.util.List.of(\"Start\")"));
        assertTrue("should emit dynamic scope-tree empty list",
            source.contains("case DYNAMIC -> java.util.List.of()"));
        assertTrue("should map lexical rules in scope mode map helper",
            source.contains("map.put(rule, ScopeMode.LEXICAL);"));
        assertTrue("should emit stable scope id format",
            source.contains("return \"scope:Advanced::\" + ruleName;"));
        assertTrue("should build scope tree spec from mode lookup",
            source.contains("new ScopeTreeSpec(ruleName, getScopeIdForRule(ruleName), mode)"));
        assertTrue("should index scope tree specs by rule",
            source.contains("map.put(spec.ruleName(), spec);"));
        assertTrue("should index scope tree specs by scope id",
            source.contains("map.put(spec.scopeId(), spec);"));
        assertTrue("should index scope modes by scope id",
            source.contains("map.put(spec.scopeId(), spec.mode());"));
        assertTrue("should normalize scope mode enum to lower-case names",
            source.contains("e.getValue().name().toLowerCase(java.util.Locale.ROOT)"));
        assertTrue("should bridge generated metadata to parser ir scope event helper",
            source.contains("ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRulesAnyMode("));
        assertTrue("should merge generated scope modes with overrides",
            source.contains("merged.putAll(getScopeTreeModeByRule());"));
        assertTrue("should merge override scope modes when provided",
            source.contains("if (modeOverridesByRule != null) {"));
        assertTrue("should bridge scope-id metadata to parser ir scope event helper",
            source.contains("ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForScopeIdsAnyMode("));
        assertTrue("should return immutable scope mode map",
            source.contains("return java.util.Map.copyOf(map);"));
        assertTrue("should check scope-tree existence via map",
            source.contains("getScopeTreeModeByRule().containsKey(ruleName)"));
        assertTrue("should fallback scope mode via map",
            source.contains("getScopeTreeModeByRule().getOrDefault(ruleName, fallback)"));
        assertTrue("should include Start interleave profile",
            source.contains("case \"Start\" -> java.util.Optional.of(\"commentsAndSpaces\")"));
        assertTrue("should include Ref backref name",
            source.contains("case \"Ref\" -> java.util.Optional.of(\"ident\")"));
    }

    @Test
    public void testInterleaveCommentsProfileEnablesCppCommentDelimiter() {
        String source = generate(INTERLEAVE_COMMENTS_GRAMMAR);
        assertTrue("should import CPPComment when commentsAndSpaces interleave is used",
            source.contains("import org.unlaxer.parser.clang.CPPComment;"));
        assertTrue("should include CPPComment in generated delimitor",
            source.contains("CPPComment.class"));
    }

    @Test
    public void testContainsOptional() {
        String source = generate(TINYCALC_GRAMMAR);
        // VariableDeclaration has [ 'set' Expression @init ] -> Optional
        assertTrue("should contain Optional for optional elements",
            source.contains("Optional"));
    }

    // =========================================================================
    // トークン参照
    // =========================================================================

    @Test
    public void testTokenReferenceUsesParserClass() {
        String source = generate(TINYCALC_GRAMMAR);
        // NUMBER token -> NumberParser.class
        assertTrue("NUMBER token should reference NumberParser.class",
            source.contains("NumberParser.class"));
    }

    @Test
    public void testIdentifierTokenReferenceUsesParserClass() {
        String source = generate(TINYCALC_GRAMMAR);
        // IDENTIFIER token -> IdentifierParser.class
        assertTrue("IDENTIFIER token should reference IdentifierParser.class",
            source.contains("IdentifierParser.class"));
    }

    // =========================================================================
    // デリミタ・チェーン
    // =========================================================================

    @Test
    public void testContainsSpaceDelimitor() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain space delimitor class",
            source.contains("TinyCalcSpaceDelimitor"));
    }

    @Test
    public void testContainsLazyChain() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should contain base chain class",
            source.contains("TinyCalcLazyChain"));
    }

    @Test
    public void testContainsSpaceParserInDelimitor() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("delimitor should reference SpaceParser",
            source.contains("SpaceParser"));
    }

    // =========================================================================
    // インポート
    // =========================================================================

    @Test
    public void testContainsCombinatorImport() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should import combinator package",
            source.contains("import org.unlaxer.parser.combinator.*"));
    }

    @Test
    public void testContainsWordParserImport() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should import WordParser",
            source.contains("import org.unlaxer.parser.elementary.WordParser"));
    }

    @Test
    public void testContainsParsersImport() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("should import Parsers",
            source.contains("import org.unlaxer.parser.Parsers"));
    }

    // =========================================================================
    // serialVersionUID
    // =========================================================================

    @Test
    public void testContainsSerialVersionUID() {
        String source = generate(TINYCALC_GRAMMAR);
        assertTrue("parser classes should have serialVersionUID",
            source.contains("serialVersionUID = 1L"));
    }

    // =========================================================================
    // 複合繰り返し体ヘルパー（バグ修正検証）
    // =========================================================================

    /** ネストしたグループを持つ最小文法 */
    private static final String NESTED_GROUP_GRAMMAR =
        "grammar TestNG {\n" +
        "  @package: test.generated\n" +
        "  @whitespace: javaStyle\n" +
        "  @root\n" +
        "  Rule ::= ( ( 'a' | 'b' ) 'c' ) ;\n" +
        "}";

    @Test
    public void testExpressionRepeat0ParserExists() {
        // ネストしたグループで inner helper (Group1Parser) が生成されることを確認
        String source = generate(NESTED_GROUP_GRAMMAR);
        assertTrue("should generate RuleGroup1Parser for inner group",
            source.contains("class RuleGroup1Parser"));
    }

    @Test
    public void testExpressionParserUsesZeroOrMoreRepeat() {
        // outer Group0Parser が inner Group1Parser を正しく参照することを確認
        String source = generate(NESTED_GROUP_GRAMMAR);
        assertTrue("RuleGroup0Parser should reference RuleGroup1Parser (counter bug fix)",
            source.contains("Parser.get(RuleGroup1Parser.class)"));
    }

    @Test
    public void testTermRepeat0ParserExists() {
        // outer helper (Group0Parser) も生成されることを確認
        String source = generate(NESTED_GROUP_GRAMMAR);
        assertTrue("should generate RuleGroup0Parser for outer group",
            source.contains("class RuleGroup0Parser"));
    }

    @Test
    public void testTermParserUsesZeroOrMoreRepeat() {
        // RuleParser が Group0Parser を正しく使用することを確認
        String source = generate(NESTED_GROUP_GRAMMAR);
        assertTrue("RuleParser should use Parser.get(RuleGroup0Parser.class)",
            source.contains("Parser.get(RuleGroup0Parser.class)"));
    }

    @Test
    public void testRepeat0ParserReferencesCorrectGroupParser() {
        // カウンターバグがない場合: Group2Parser は存在しない
        String source = generate(NESTED_GROUP_GRAMMAR);
        assertTrue("RuleGroup0Parser should exist",
            source.contains("class RuleGroup0Parser"));
        assertFalse("RuleGroup2Parser must not appear (would indicate counter bug)",
            source.contains("RuleGroup2Parser"));
    }

    // =========================================================================
    // UNTIL トークン
    // =========================================================================

    private static final String UNTIL_GRAMMAR =
        "grammar CodeBlock {\n" +
        "  @package: org.example.codeblock\n" +
        "  @whitespace: javaStyle\n" +
        "  token CODE_BODY = UNTIL('```')\n" +
        "  @root\n" +
        "  CodeBlock ::= CODE_BODY ;\n" +
        "}";

    @Test
    public void testUntilTokenGeneratesWildCardStringTerninatorParser() {
        String source = generate(UNTIL_GRAMMAR);
        assertTrue("UNTIL token should generate WildCardStringTerninatorParser",
            source.contains("WildCardStringTerninatorParser"));
    }

    @Test
    public void testUntilTokenIncludesTerminatorString() {
        String source = generate(UNTIL_GRAMMAR);
        assertTrue("UNTIL token should embed the terminator string",
            source.contains("WildCardStringTerninatorParser(\"```\")"));
    }

    @Test
    public void testUntilTokenDoesNotGenerateParserGet() {
        String source = generate(UNTIL_GRAMMAR);
        // CODE_BODY should not produce Parser.get(CODE_BODYParser.class)
        assertFalse("UNTIL token must not produce Parser.get(CODE_BODYParser.class)",
            source.contains("CODE_BODYParser.class"));
    }

    // =========================================================================
    // ヘルパーメソッド
    // =========================================================================

    private GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }

    private String generate(String grammarSource) {
        GrammarDecl grammar = parseGrammar(grammarSource);
        ParserGenerator gen = new ParserGenerator();
        return gen.generate(grammar).source();
    }
}
