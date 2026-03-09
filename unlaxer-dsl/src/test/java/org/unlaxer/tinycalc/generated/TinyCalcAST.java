package org.unlaxer.tinycalc.generated;

import java.util.List;
import java.util.Optional;

public sealed interface TinyCalcAST permits
    TinyCalcAST.TinyCalcProgram,
    TinyCalcAST.VarDecl,
    TinyCalcAST.BinaryExpr,
    TinyCalcAST.NumberLiteral,
    TinyCalcAST.VariableRef {

    record TinyCalcProgram(
        List<TinyCalcAST.VarDecl> declarations,
        TinyCalcAST expression
    ) implements TinyCalcAST {}

    record VarDecl(
        String keyword,
        String name,
        Optional<TinyCalcAST> init
    ) implements TinyCalcAST {}

    /**
     * 二項演算ノード。左結合のため left は単一だが op/right は複数あり得る。
     * 例: 1 + 2 + 3 → BinaryExpr(NumberLiteral(1), ["+", "+"], [NumberLiteral(2), NumberLiteral(3)])
     */
    record BinaryExpr(
        TinyCalcAST left,
        List<String> op,
        List<TinyCalcAST> right
    ) implements TinyCalcAST {}

    record NumberLiteral(double value) implements TinyCalcAST {}

    record VariableRef(String name) implements TinyCalcAST {}
}
