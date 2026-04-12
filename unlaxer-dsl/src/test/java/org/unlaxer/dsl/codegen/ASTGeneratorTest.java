package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class ASTGeneratorTest {

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
        ASTGenerator gen = new ASTGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("org.unlaxer.tinycalc.generated", result.packageName());
    }

    @Test
    public void testGeneratedClassName() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        CodeGenerator.GeneratedSource result = gen.generate(grammar);
        assertEquals("TinyCalcAST", result.className());
    }

    @Test
    public void testGeneratedSourceContainsPackageDeclaration() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain package declaration",
            source.contains("package org.unlaxer.tinycalc.generated;"));
    }

    @Test
    public void testGeneratedSourceContainsSealedInterface() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should be sealed interface",
            source.contains("sealed interface TinyCalcAST"));
    }

    @Test
    public void testGeneratedSourceContainsTinyCalcProgramRecord() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain TinyCalcProgram record",
            source.contains("record TinyCalcProgram("));
    }

    @Test
    public void testGeneratedSourceContainsVarDeclRecord() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain VarDecl record",
            source.contains("record VarDecl("));
    }

    @Test
    public void testGeneratedSourceContainsBinaryExprRecord() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain BinaryExpr record",
            source.contains("record BinaryExpr("));
    }

    @Test
    public void testBinaryExprIsDeduplicatedInPermits() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        // BinaryExpr appears twice in grammar (Expression and Term) but once in permits
        long occurrences = countOccurrences(source, "record BinaryExpr(");
        assertEquals("BinaryExpr record should appear exactly once", 1, occurrences);
    }

    @Test
    public void testGeneratedSourceContainsImports() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should import List", source.contains("import java.util.List;"));
        assertTrue("should import Optional", source.contains("import java.util.Optional;"));
    }

    @Test
    public void testTinyCalcProgramHasDeclarationsField() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("TinyCalcProgram should have declarations field",
            source.contains("declarations"));
    }

    @Test
    public void testTinyCalcProgramHasExpressionField() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("TinyCalcProgram should have expression field",
            source.contains("expression"));
    }

    @Test
    public void testDeclarationsFieldIsListType() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        // { VariableDeclaration } @declarations → VarDecl has @mapping → List<TinyCalcAST.VarDecl>
        assertTrue("declarations should be a List type",
            source.contains("List<") && source.contains("declarations"));
    }

    @Test
    public void testImplementsSealedInterface() {
        GrammarDecl grammar = parseGrammar(TINYCALC_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("records should implement TinyCalcAST",
            source.contains("implements TinyCalcAST"));
    }

    @Test
    public void testNoMappingGrammarGeneratesPlainInterface() {
        String noMappingGrammar =
            "grammar Plain {\n" +
            "  @package: org.example.generated\n" +
            "  @root\n" +
            "  Plain ::= 'ok' ;\n" +
            "}";
        GrammarDecl grammar = parseGrammar(noMappingGrammar);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should generate plain interface", source.contains("public interface PlainAST {}"));
        assertTrue("should not generate malformed permits", !source.contains("permits {"));
    }

    // =========================================================================
    // ドット記法 + 中間 sealed interface (Issue #8)
    // =========================================================================

    private static final String DOTTED_GRAMMAR =
        "grammar Dotted {\n" +
        "  @package: x.y\n" +
        "  @whitespace: javaStyle\n" +
        "  token IDENT = org.unlaxer.parser.clang.IdentifierParser\n" +
        "  token NUM   = org.unlaxer.parser.elementary.NumberParser\n" +
        "\n" +
        "  @root\n" +
        "  @mapping(Program, params=[items])\n" +
        "  Program ::= { Item } @items ;\n" +
        "\n" +
        // 中間 sealed: Item = ItemA | ItemB → flat record の sum
        "  @mapping(Item)\n" +
        "  Item ::= ItemA | ItemB ;\n" +
        "  @mapping(ItemA, params=[name])\n" +
        "  ItemA ::= 'a' IDENT @name ;\n" +
        "  @mapping(ItemB, params=[value])\n" +
        "  ItemB ::= 'b' NUM @value ;\n" +
        "\n" +
        // sealed inner record スタイル: TokenLike = SimpleT | UntilT (どちらも TokenLike.X)
        "  @mapping(TokenLike)\n" +
        "  TokenLike ::= SimpleT | UntilT ;\n" +
        "  @mapping(TokenLike.Simple, params=[name])\n" +
        "  SimpleT ::= 'simple' IDENT @name ;\n" +
        "  @mapping(TokenLike.Until, params=[name])\n" +
        "  UntilT ::= 'until' IDENT @name ;\n" +
        "}";

    @Test
    public void testMidSealedInterfaceForFlatAlternatives() {
        GrammarDecl grammar = parseGrammar(DOTTED_GRAMMAR);
        String source = new ASTGenerator().generate(grammar).source();
        // Item は中間 sealed として宣言される
        assertTrue("Item must be a sealed interface", source.contains("sealed interface Item extends DottedAST permits"));
        assertTrue("Item permits ItemA", source.contains("ItemA"));
        assertTrue("Item permits ItemB", source.contains("ItemB"));
        // 各 alternative record は Item を implements する
        assertTrue("ItemA implements Item",
            source.matches("(?s).*record\\s+ItemA\\s*\\([^)]*\\)\\s*implements\\s+Item\\s*\\{\\}.*"));
        assertTrue("ItemB implements Item",
            source.matches("(?s).*record\\s+ItemB\\s*\\([^)]*\\)\\s*implements\\s+Item\\s*\\{\\}.*"));
        // ルート AST の permits は Item を含み、ItemA/ItemB は含まない (中間 sealed 配下なので)
        assertTrue("AST permits Item", source.contains("DottedAST.Item"));
    }

    @Test
    public void testNestedInnerRecordViaDotted() {
        GrammarDecl grammar = parseGrammar(DOTTED_GRAMMAR);
        String source = new ASTGenerator().generate(grammar).source();
        // TokenLike は sealed inner record を持つ
        assertTrue("TokenLike sealed wrapper", source.contains("sealed interface TokenLike extends DottedAST permits"));
        assertTrue("TokenLike.Simple inner", source.contains("TokenLike.Simple"));
        assertTrue("TokenLike.Until inner",  source.contains("TokenLike.Until"));
        // Inner record の implements は親 (TokenLike) を指す
        assertTrue("inner Simple implements TokenLike",
            source.matches("(?s).*record\\s+Simple\\s*\\([^)]*\\)\\s*implements\\s+TokenLike\\s*\\{\\}.*"));
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
