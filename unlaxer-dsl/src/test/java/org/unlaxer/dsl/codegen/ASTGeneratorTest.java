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
    // @enum — Java enum generation (3.0.0)
    // =========================================================================

    // Grammar that uses @enum: the enum rules are siblings of @mapping rules so the generator
    // enters the sealed-interface path and emits the enum classes.
    private static final String ENUM_GRAMMAR =
        "grammar EnumDemo {\n" +
        "  @package: org.example.enumdemo\n" +
        "\n" +
        "  @enum\n" +
        "  RecoveryMode ::= 'sync' | 'auto' | 'skip' ;\n" +
        "\n" +
        "  @enum\n" +
        "  LogLevel ::= 'debug' | 'info' | 'warn' | 'error' ;\n" +
        "\n" +
        "  @root\n" +
        "  @mapping(Config, params=[name])\n" +
        "  Config ::= 'cfg' RecoveryMode @name ;\n" +
        "}";

    @Test
    public void testEnumRuleGeneratesEnumClass() {
        GrammarDecl grammar = parseGrammar(ENUM_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should generate enum RecoveryMode", source.contains("public enum RecoveryMode {"));
    }

    @Test
    public void testEnumConstantsAreUpperCase() {
        GrammarDecl grammar = parseGrammar(ENUM_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should contain SYNC constant", source.contains("SYNC"));
        assertTrue("should contain AUTO constant", source.contains("AUTO"));
        assertTrue("should contain SKIP constant", source.contains("SKIP"));
    }

    @Test
    public void testEnumHasFromTextFactory() {
        GrammarDecl grammar = parseGrammar(ENUM_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should have fromText factory method",
            source.contains("public static RecoveryMode fromText(String text)"));
    }

    @Test
    public void testFromTextSwitchCoversAllLiterals() {
        GrammarDecl grammar = parseGrammar(ENUM_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("fromText should handle 'sync'", source.contains("case \"sync\" -> SYNC;"));
        assertTrue("fromText should handle 'auto'", source.contains("case \"auto\" -> AUTO;"));
        assertTrue("fromText should handle 'skip'", source.contains("case \"skip\" -> SKIP;"));
    }

    @Test
    public void testFromTextHasDefaultThrow() {
        GrammarDecl grammar = parseGrammar(ENUM_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("fromText should throw on unknown",
            source.contains("throw new IllegalArgumentException(\"Unknown RecoveryMode: \" + text)"));
    }

    @Test
    public void testMultipleEnumRulesAllGenerated() {
        GrammarDecl grammar = parseGrammar(ENUM_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should generate RecoveryMode enum", source.contains("public enum RecoveryMode {"));
        assertTrue("should generate LogLevel enum", source.contains("public enum LogLevel {"));
    }

    @Test
    public void testEnumConstantNameCamelCase() {
        // camelCase literals → CAMEL_CASE constant names; kebab-case → KEBAB_CASE.
        // The grammar needs at least one @mapping rule so the generator enters the sealed-interface
        // path and emits enum classes.
        String grammar =
            "grammar CamelEnum {\n" +
            "  @package: org.example\n" +
            "  @enum\n" +
            "  SomeEnum ::= 'camelCase' | 'kebab-case' ;\n" +
            "  @root\n" +
            "  @mapping(Root, params=[value])\n" +
            "  Root ::= 'root' SomeEnum @value ;\n" +
            "}";
        GrammarDecl g = parseGrammar(grammar);
        String source = new ASTGenerator().generate(g).source();
        assertTrue("camelCase → CAMEL_CASE", source.contains("CAMEL_CASE"));
        assertTrue("kebab-case → KEBAB_CASE", source.contains("KEBAB_CASE"));
    }

    // =========================================================================
    // @commonField — sealed interface method promotion (3.0.0)
    // =========================================================================

    // Grammar for @commonField: the annotation goes on the mid-sealed (parent) rule that
    // aggregates alternatives.  Alternatives must use flat (non-dotted) @mapping names so
    // the mid-sealed path is taken.  The implementation at emitMidSealed() collects
    // @commonField from rules whose @mapping className equals the midSealedName ("Node").
    private static final String COMMON_FIELD_GRAMMAR =
        "grammar Expr {\n" +
        "  @package: org.example.expr\n" +
        "  @whitespace: javaStyle\n" +
        "\n" +
        "  token IDENT = org.unlaxer.parser.clang.IdentifierParser\n" +
        "\n" +
        "  @root\n" +
        "  @mapping(Node)\n" +
        "  @commonField(left)\n" +
        "  Node ::= AddNode | SubNode ;\n" +
        "\n" +
        "  @mapping(AddNode, params=[left, right])\n" +
        "  AddNode ::= IDENT @left '+' IDENT @right ;\n" +
        "\n" +
        "  @mapping(SubNode, params=[left, right])\n" +
        "  SubNode ::= IDENT @left '-' IDENT @right ;\n" +
        "}";

    @Test
    public void testCommonFieldAppearsAsAbstractMethodInSealedInterface() {
        GrammarDecl grammar = parseGrammar(COMMON_FIELD_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("sealed interface Node should have left() abstract method",
            source.contains("left();"));
    }

    @Test
    public void testCommonFieldSealedInterfaceIsGenerated() {
        GrammarDecl grammar = parseGrammar(COMMON_FIELD_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should generate sealed interface Node with permits",
            source.contains("sealed interface Node extends ExprAST permits"));
    }

    @Test
    public void testCommonFieldPermittedRecordsAreGenerated() {
        GrammarDecl grammar = parseGrammar(COMMON_FIELD_GRAMMAR);
        ASTGenerator gen = new ASTGenerator();
        String source = gen.generate(grammar).source();
        assertTrue("should have AddNode record", source.contains("record AddNode("));
        assertTrue("should have SubNode record", source.contains("record SubNode("));
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
