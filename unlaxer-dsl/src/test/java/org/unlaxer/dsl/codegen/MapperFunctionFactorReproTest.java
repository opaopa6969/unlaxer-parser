package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * Regression for unlaxer-parser #43: a left-assoc BinaryExpr spine whose Factor can be a
 * {@code @mapping}'d function. The generated mapper must dispatch a function-valued factor
 * to its own mapper (toAbsExpr) via the operand helper instead of recursing through the
 * assoc mapper (which descended past the function wrapper and dropped it), and the AST
 * BinaryExpr operand fields must be widened to the base interface to hold the function node.
 */
public class MapperFunctionFactorReproTest {

    static final String G =
        "grammar FnCalc {\n" +
        "  @package: org.example.fncalc\n" +
        "  @whitespace: javaStyle\n" +
        "  token NUMBER = NumberParser\n" +
        "  @root\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Expression ::= Term @left { AddOp @op Term @right } ;\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Term ::= Factor @left { MulOp @op Factor @right } ;\n" +
        "  AddOp ::= '+' | '-' ;\n" +
        "  MulOp ::= '*' | '/' ;\n" +
        "  Factor ::= AbsFunction | NUMBER | '(' Expression ')' ;\n" +
        "  @mapping(AbsExpr, params=[arg])\n" +
        "  AbsFunction ::= 'abs' '(' Expression @arg ')' ;\n" +
        "}";

    // A homogeneous arithmetic grammar (factor is only NUMBER / grouped Expression):
    // the fix must NOT trigger here, so its generated code is unchanged.
    static final String HOMOGENEOUS =
        "grammar Plain {\n" +
        "  @package: org.example.plain\n" +
        "  @whitespace: javaStyle\n" +
        "  token NUMBER = NumberParser\n" +
        "  @root\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Expression ::= Term @left { AddOp @op Term @right } ;\n" +
        "  @mapping(BinaryExpr, params=[left, op, right])\n" +
        "  @leftAssoc\n" +
        "  Term ::= Factor @left { MulOp @op Factor @right } ;\n" +
        "  AddOp ::= '+' | '-' ;\n" +
        "  MulOp ::= '*' | '/' ;\n" +
        "  Factor ::= NUMBER | '(' Expression ')' ;\n" +
        "}";

    @Test
    public void heterogeneousFactorWidensOperandsAndDispatches() {
        GrammarDecl grammar = UBNFMapper.parse(G).grammars().get(0);
        String ast = new ASTGenerator().generate(grammar).source();
        String mapper = new MapperGenerator().generate(grammar).source();

        // AST operand fields widened to the base interface so a factor's AbsExpr fits.
        assertTrue("BinaryExpr operands should widen to base interface",
            ast.contains("FnCalcAST left,") && ast.contains("List<FnCalcAST> right"));
        // Operand helper generated and used for dispatch.
        assertTrue("mapper should generate the operand-dispatch helper",
            mapper.contains("mapAssocOperandToBinaryExpr(Token token)"));
        assertTrue("mapper should dispatch operands through the helper",
            mapper.contains("mapAssocOperandToBinaryExpr(leftToken)"));
    }

    @Test
    public void homogeneousFactorIsUnchanged() {
        GrammarDecl grammar = UBNFMapper.parse(HOMOGENEOUS).grammars().get(0);
        String ast = new ASTGenerator().generate(grammar).source();
        String mapper = new MapperGenerator().generate(grammar).source();
        // No widening, no helper — behaviour identical to before the fix.
        assertFalse("homogeneous operands must not widen",
            ast.contains("PlainAST left,"));
        assertFalse("no operand helper for homogeneous grammars",
            mapper.contains("mapAssocOperandToBinaryExpr"));
    }
}
