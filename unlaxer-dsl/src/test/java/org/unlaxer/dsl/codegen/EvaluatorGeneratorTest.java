package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class EvaluatorGeneratorTest {

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

    @Test
    public void testGeneratedPackageName() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("org.unlaxer.tinycalc.generated", result.packageName());
    }

    @Test
    public void testGeneratedClassName() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("TinyCalcEvaluator", result.className());
    }

    @Test
    public void testGeneratedSourceContainsPackageDeclaration() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain package declaration",
            source.contains("package org.unlaxer.tinycalc.generated;"));
    }

    @Test
    public void testGeneratedSourceContainsAbstractClass() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should be abstract class",
            source.contains("abstract class TinyCalcEvaluator"));
    }

    @Test
    public void testGeneratedSourceContainsEvalMethod() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain eval method",
            source.contains("public T eval(TinyCalcAST node)"));
    }

    @Test
    public void testGeneratedSourceContainsDebugStrategy() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain DebugStrategy interface",
            source.contains("interface DebugStrategy"));
    }

    @Test
    public void testGeneratedSourceContainsNOOP() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain NOOP constant",
            source.contains("NOOP"));
    }

    @Test
    public void testGeneratedSourceContainsStepCounterStrategy() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain StepCounterStrategy",
            source.contains("StepCounterStrategy"));
    }

    @Test
    public void testGeneratedSourceContainsEvalTinyCalcProgram() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain evalTinyCalcProgram",
            source.contains("evalTinyCalcProgram"));
    }

    @Test
    public void testGeneratedSourceContainsEvalVarDecl() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain evalVarDecl",
            source.contains("evalVarDecl"));
    }

    @Test
    public void testGeneratedSourceContainsEvalBinaryExpr() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain evalBinaryExpr",
            source.contains("evalBinaryExpr"));
    }

    @Test
    public void testBinaryExprMethodIsDeduplicatedInSwitch() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        // BinaryExpr appears twice in grammar but should appear once in switch
        long switchOccurrences = countOccurrences(source, "case TinyCalcAST.BinaryExpr");
        assertEquals("BinaryExpr should appear once in sealed switch", 1, switchOccurrences);
    }

    @Test
    public void testGeneratedSourceContainsOnEnterOnExit() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain onEnter", source.contains("onEnter"));
        assertTrue("should contain onExit", source.contains("onExit"));
    }

    @Test
    public void testGeneratedSourceContainsBiConsumer() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("StepCounterStrategy should use BiConsumer",
            source.contains("BiConsumer"));
    }

    @Test
    public void testAbstractMethodsAreDeclared() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("abstract evalTinyCalcProgram",
            source.contains("protected abstract T evalTinyCalcProgram("));
        assertTrue("abstract evalVarDecl",
            source.contains("protected abstract T evalVarDecl("));
        assertTrue("abstract evalBinaryExpr",
            source.contains("protected abstract T evalBinaryExpr("));
    }

    @Test
    public void testNoMappingGrammarGeneratesFallbackEvalInternal() {
        String noMappingGrammar =
            "grammar Plain {\n" +
            "  @package: org.example.generated\n" +
            "  @root\n" +
            "  Plain ::= 'ok' ;\n" +
            "}";
        GrammarDecl grammar = parseGrammar(noMappingGrammar);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain fallback exception",
            source.contains("No mapping classes for evaluation"));
    }

    // =========================================================================
    // @eval annotation tests
    // =========================================================================

    private static final String EVAL_GRAMMAR =
        "grammar EvalTest {\n" +
        "  @package: org.example.generated\n" +
        "  @whitespace: javaStyle\n" +
        "\n" +
        "  token NUMBER     = NumberParser\n" +
        "  token IDENTIFIER = IdentifierParser\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @eval(kind='binary_arithmetic', strategy='default')\n" +
        "  @leftAssoc\n" +
        "  Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;\n" +
        "\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;\n" +
        "\n" +
        "  @mapping(VariableRefExpr, params=[name])\n" +
        "  @eval(kind='variable_ref', strategy='default', strip_prefix='$')\n" +
        "  VariableRef ::= IDENTIFIER @name ;\n" +
        "\n" +
        "  @mapping(IfExpr, params=[condition, thenExpr, elseExpr])\n" +
        "  @eval(kind='conditional', strategy='default')\n" +
        "  IfExpression ::= 'if' Expression @condition 'then' Expression @thenExpr 'else' Expression @elseExpr ;\n" +
        "\n" +
        "  @mapping(InvocationExpr, params=[name, args])\n" +
        "  @eval(kind='invocation', strategy='manual')\n" +
        "  Invocation ::= IDENTIFIER @name '(' { Expression @args } ')' ;\n" +
        "\n" +
        "  @mapping(LiteralExpr, params=[value])\n" +
        "  NoEvalRule ::= NUMBER @value ;\n" +
        "\n" +
        "  Factor ::=\n" +
        "      '(' Expression ')'\n" +
        "    | NUMBER\n" +
        "    | IDENTIFIER ;\n" +
        "}";

    @Test
    public void testEvalBinaryArithmeticGeneratesConcreteMethod() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        // Should NOT be abstract — should be concrete with body
        assertTrue("should contain concrete evalBinaryExpr",
            source.contains("protected T evalBinaryExpr(EvalTestAST.BinaryExpr node)"));
        assertTrue("should NOT contain abstract evalBinaryExpr",
            !source.contains("protected abstract T evalBinaryExpr("));
    }

    @Test
    public void testEvalBinaryArithmeticContainsApplyBinary() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain applyBinary call",
            source.contains("applyBinary("));
        assertTrue("should declare abstract applyBinary",
            source.contains("protected abstract T applyBinary(String op, T left, T right)"));
    }

    @Test
    public void testEvalBinaryArithmeticContainsEvalLeaf() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain evalLeaf call",
            source.contains("evalLeaf("));
        assertTrue("should declare abstract evalLeaf",
            source.contains("protected abstract T evalLeaf(String literal)"));
    }

    @Test
    public void testEvalVariableRefGeneratesConcreteMethod() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain concrete evalVariableRefExpr",
            source.contains("protected T evalVariableRefExpr(EvalTestAST.VariableRefExpr node)"));
        assertTrue("should NOT contain abstract evalVariableRefExpr",
            !source.contains("protected abstract T evalVariableRefExpr("));
    }

    @Test
    public void testEvalVariableRefContainsStripPrefix() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should strip $ prefix",
            source.contains("startsWith(\"$\")"));
        assertTrue("should declare abstract resolveVariable",
            source.contains("protected abstract T resolveVariable(String name)"));
    }

    @Test
    public void testEvalConditionalGeneratesConcreteMethod() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain concrete evalIfExpr",
            source.contains("protected T evalIfExpr(EvalTestAST.IfExpr node)"));
        assertTrue("should NOT contain abstract evalIfExpr",
            !source.contains("protected abstract T evalIfExpr("));
    }

    @Test
    public void testEvalConditionalContainsConditionLogic() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain condition evaluation",
            source.contains("Boolean.TRUE.equals(cond)"));
        assertTrue("should contain thenExpr/elseExpr dispatch",
            source.contains("node.thenExpr()") && source.contains("node.elseExpr()"));
    }

    @Test
    public void testEvalManualStrategyGeneratesAbstractMethod() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("manual strategy should generate abstract method",
            source.contains("protected abstract T evalInvocationExpr(EvalTestAST.InvocationExpr node)"));
    }

    @Test
    public void testNoEvalAnnotationGeneratesAbstractMethod() {
        GrammarDecl grammar = parseGrammar(EVAL_GRAMMAR);
        EvaluatorGenerator gen = new EvaluatorGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("no @eval should generate abstract method",
            source.contains("protected abstract T evalLiteralExpr(EvalTestAST.LiteralExpr node)"));
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }

    private long countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
