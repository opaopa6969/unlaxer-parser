package org.unlaxer.tinycalc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.unlaxer.tinycalc.generated.TinyCalcAST;
import org.unlaxer.tinycalc.generated.TinyCalcEvaluator;
import org.unlaxer.tinycalc.generated.TinyCalcMapper;

public class TinyCalcIntegrationTest {

    private TinyCalcCalculator calc;

    @Before
    public void setUp() {
        calc = new TinyCalcCalculator();
    }

    // =========================================================================
    // 基本演算
    // =========================================================================

    @Test
    public void testNumberLiteral() {
        assertEquals(42.0, eval("42"), 0.001);
    }

    @Test
    public void testAddition() {
        assertEquals(3.0, eval("1 + 2"), 0.001);
    }

    @Test
    public void testSubtraction() {
        assertEquals(1.0, eval("3 - 2"), 0.001);
    }

    @Test
    public void testMultiplication() {
        assertEquals(6.0, eval("2 * 3"), 0.001);
    }

    @Test
    public void testDivision() {
        assertEquals(2.0, eval("6 / 3"), 0.001);
    }

    // =========================================================================
    // 演算子優先順位
    // =========================================================================

    @Test
    public void testOperatorPrecedence() {
        // 1 + 2 * 3 = 1 + (2 * 3) = 7
        assertEquals(7.0, eval("1 + 2 * 3"), 0.001);
    }

    @Test
    public void testOperatorPrecedenceSubtract() {
        // 10 - 2 * 3 = 10 - (2 * 3) = 4
        assertEquals(4.0, eval("10 - 2 * 3"), 0.001);
    }

    // =========================================================================
    // 括弧
    // =========================================================================

    @Test
    public void testParentheses() {
        // (1 + 2) * 3 = 9
        assertEquals(9.0, eval("(1 + 2) * 3"), 0.001);
    }

    @Test
    public void testNestedParentheses() {
        // (2 + (3 * 4)) = 14
        assertEquals(14.0, eval("(2 + (3 * 4))"), 0.001);
    }

    // =========================================================================
    // 左結合
    // =========================================================================

    @Test
    public void testLeftAssocSubtraction() {
        // 10 - 3 - 2 = (10 - 3) - 2 = 5
        assertEquals(5.0, eval("10 - 3 - 2"), 0.001);
    }

    @Test
    public void testLeftAssocDivision() {
        // 12 / 4 / 3 = (12 / 4) / 3 = 1
        assertEquals(1.0, eval("12 / 4 / 3"), 0.001);
    }

    // =========================================================================
    // 変数宣言
    // =========================================================================

    @Test
    public void testVarDeclaration() {
        assertEquals(8.0, eval("var x set 5 ; x + 3"), 0.001);
    }

    @Test
    public void testVarDeclarationWithVariable() {
        assertEquals(6.0, eval("var a set 2 ; var b set 3 ; a * b"), 0.001);
    }

    @Test
    public void testVarKeywordAlternative() {
        assertEquals(10.0, eval("variable count set 10 ; count"), 0.001);
    }

    @Test
    public void testVarWithoutInit() {
        // 初期値なし → 0.0
        assertEquals(3.0, eval("var x ; x + 3"), 0.001);
    }

    @Test
    public void testMultipleVarDecls() {
        assertEquals(9.0, eval("var a set 4 ; var b set 5 ; a + b"), 0.001);
    }

    // =========================================================================
    // 複合テスト
    // =========================================================================

    @Test
    public void testComplexExpression() {
        // var x set 2 ; (x + 3) * (x - 1) = 5 * 1 = 5
        assertEquals(5.0, eval("var x set 2 ; (x + 3) * (x - 1)"), 0.001);
    }

    // =========================================================================
    // DAP 準備: StepCounterStrategy
    // =========================================================================

    @Test
    public void testStepCounterStrategy() {
        List<String> steps = new ArrayList<>();
        calc.setDebugStrategy(new TinyCalcEvaluator.StepCounterStrategy(
            (step, node) -> steps.add(step + ": " + node.getClass().getSimpleName())
        ));

        TinyCalcAST.TinyCalcProgram program = TinyCalcMapper.parse("1 + 2");
        calc.eval(program);

        // TinyCalcProgram → BinaryExpr → NumberLiteral(1) → NumberLiteral(2)
        assertTrue("should have steps", steps.size() >= 3);
        assertTrue("first step should be TinyCalcProgram",
            steps.get(0).contains("TinyCalcProgram"));
    }

    @Test
    public void testStepCounterWithVariables() {
        List<String> steps = new ArrayList<>();
        calc.setDebugStrategy(new TinyCalcEvaluator.StepCounterStrategy(
            (step, node) -> steps.add(step + ": " + node.getClass().getSimpleName())
        ));

        TinyCalcAST.TinyCalcProgram program = TinyCalcMapper.parse("var x set 5 ; x + 3");
        calc.eval(program);

        // TinyCalcProgram, VarDecl, NumberLiteral(5), BinaryExpr, VariableRef(x), NumberLiteral(3)
        assertTrue("should have multiple steps", steps.size() >= 4);
        boolean hasVarDecl = steps.stream().anyMatch(s -> s.contains("VarDecl"));
        assertTrue("should have VarDecl step", hasVarDecl);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private double eval(String source) {
        TinyCalcAST.TinyCalcProgram program = TinyCalcMapper.parse(source);
        return calc.eval(program);
    }
}
