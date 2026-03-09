package org.unlaxer.tinycalc;

import java.util.HashMap;
import java.util.Map;

import org.unlaxer.tinycalc.generated.TinyCalcAST;
import org.unlaxer.tinycalc.generated.TinyCalcEvaluator;

/**
 * TinyCalc 計算機。変数環境を持ち、四則演算を評価する。
 */
public class TinyCalcCalculator extends TinyCalcEvaluator<Double> {

    private final Map<String, Double> env = new HashMap<>();

    /**
     * 変数環境をリセットする。
     */
    public void resetEnv() {
        env.clear();
    }

    @Override
    protected Double evalTinyCalcProgram(TinyCalcAST.TinyCalcProgram node) {
        // 宣言を順番に処理してから最後の式を評価
        for (TinyCalcAST.VarDecl decl : node.declarations()) {
            eval(decl);
        }
        return eval(node.expression());
    }

    @Override
    protected Double evalVarDecl(TinyCalcAST.VarDecl node) {
        double value = node.init()
            .map(this::eval)
            .orElse(0.0);
        env.put(node.name(), value);
        return value;
    }

    @Override
    protected Double evalBinaryExpr(TinyCalcAST.BinaryExpr node) {
        double result = eval(node.left());
        for (int i = 0; i < node.op().size(); i++) {
            double right = eval(node.right().get(i));
            result = applyOp(node.op().get(i), result, right);
        }
        return result;
    }

    @Override
    protected Double evalNumberLiteral(TinyCalcAST.NumberLiteral node) {
        return node.value();
    }

    @Override
    protected Double evalVariableRef(TinyCalcAST.VariableRef node) {
        return env.getOrDefault(node.name(), 0.0);
    }

    private double applyOp(String op, double left, double right) {
        return switch (op) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default -> throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }
}
