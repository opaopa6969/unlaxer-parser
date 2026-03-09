package org.unlaxer.tinycalc.generated;

public abstract class TinyCalcEvaluator<T> {

    private DebugStrategy debugStrategy = DebugStrategy.NOOP;

    public void setDebugStrategy(DebugStrategy strategy) {
        this.debugStrategy = strategy;
    }

    public T eval(TinyCalcAST node) {
        debugStrategy.onEnter(node);
        T result = evalInternal(node);
        debugStrategy.onExit(node, result);
        return result;
    }

    private T evalInternal(TinyCalcAST node) {
        return switch (node) {
            case TinyCalcAST.TinyCalcProgram n -> evalTinyCalcProgram(n);
            case TinyCalcAST.VarDecl n -> evalVarDecl(n);
            case TinyCalcAST.BinaryExpr n -> evalBinaryExpr(n);
            case TinyCalcAST.NumberLiteral n -> evalNumberLiteral(n);
            case TinyCalcAST.VariableRef n -> evalVariableRef(n);
        };
    }

    protected abstract T evalTinyCalcProgram(TinyCalcAST.TinyCalcProgram node);
    protected abstract T evalVarDecl(TinyCalcAST.VarDecl node);
    protected abstract T evalBinaryExpr(TinyCalcAST.BinaryExpr node);
    protected abstract T evalNumberLiteral(TinyCalcAST.NumberLiteral node);
    protected abstract T evalVariableRef(TinyCalcAST.VariableRef node);

    // =========================================================================
    // DebugStrategy
    // =========================================================================

    public interface DebugStrategy {
        void onEnter(TinyCalcAST node);
        void onExit(TinyCalcAST node, Object result);

        DebugStrategy NOOP = new DebugStrategy() {
            public void onEnter(TinyCalcAST node) {}
            public void onExit(TinyCalcAST node, Object result) {}
        };
    }

    public static class StepCounterStrategy implements DebugStrategy {
        private int step = 0;
        private final java.util.function.BiConsumer<Integer, TinyCalcAST> onStep;

        public StepCounterStrategy(
            java.util.function.BiConsumer<Integer, TinyCalcAST> onStep
        ) {
            this.onStep = onStep;
        }

        @Override
        public void onEnter(TinyCalcAST node) {
            onStep.accept(step++, node);
        }

        @Override
        public void onExit(TinyCalcAST node, Object result) {}
    }
}
