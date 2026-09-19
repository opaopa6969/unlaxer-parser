package org.unlaxer.dsl;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.RustGrammarLowering;

public class RustBackendTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SIMPLE = """
        grammar Example {
          @root
          @mapping(Value, params=[value])
          Root ::= 'hello' @value ;
        }
        """;

    @Test public void generationIsDeterministicAndExhaustive() {
        var grammar = UBNFMapper.parse(SIMPLE).grammars().get(0);
        var files = new RustBackend().generate(grammar);
        assertEquals(files, new RustBackend().generate(grammar));
        assertEquals(5, files.size());
        assertTrue(files.get(1).content().contains("r#Value { span: Span, r#value: String }"));
        assertTrue(files.get(4).content().contains("fn eval_value(&mut self, r#value: &str, span: Span) -> Self::Output;"));
        assertFalse(files.get(4).content().contains("_ =>"));
    }

    @Test public void generatedParserSharesOneGrammarWithoutBreakingRulesSnapshotApi() {
        var grammar = UBNFMapper.parse(SIMPLE).grammars().get(0);
        String parser = new RustBackend().generate(grammar).get(2).content();
        assertTrue(parser.contains("static GRAMMAR: OnceLock<SharedGrammar>"));
        assertTrue(parser.contains("pub fn grammar() -> &'static SharedGrammar"));
        assertTrue(parser.contains("pub fn rules() -> Vec<Rule>"));
        assertTrue(parser.contains("context.parse_shared_grammar(grammar()"));
        assertTrue(parser.contains("parse_detailed_shared(grammar()"));
        assertFalse(parser.contains("parse_detailed(&rules()"));
    }

    @Test public void generatedMapperDispatchesToBoundedRuleFunctions() {
        String source = """
            grammar SplitMapper {
              @root @mapping(Root, params=[left, right]) Root ::= Left @left Right @right;
              @mapping(Leaf, params=[value]) Left ::= 'left' @value;
              @mapping(Leaf, params=[value]) Right ::= 'right' @value;
            }
            """;
        String mapper = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0))
            .stream().filter(file -> file.relativePath().endsWith("mapper.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(mapper.contains("0 => map_rule_0(tree, id)"));
        assertTrue(mapper.contains("1 => map_rule_1(tree, id)"));
        assertTrue(mapper.contains("2 => map_rule_2(tree, id)"));
        assertEquals(3, mapper.split("#\\[inline\\(never\\)\\]", -1).length - 1);
        String dispatcher = mapper.substring(
            mapper.indexOf("fn map_node"), mapper.indexOf("#[inline(never)]"));
        assertFalse(dispatcher.contains("Ast::"));
    }

    @Test public void unsupportedFeaturesAreRejectedBeforeEmission() {
        reject(SIMPLE.replace("'hello' @value", "Missing @value"), "unknown reference");
        reject(SIMPLE.replace("'hello' @value", "Root @value"), "left recursion");
        reject(SIMPLE.replace("@root", "@root\n@root"), "multiple @root");
        reject(SIMPLE.replace("value", "span"), "reserved");
        reject(SIMPLE.replace("value", "semantics"), "reserved");
        reject(SIMPLE.replace("'hello' @value", "[ 'x' ] Root @value"), "left recursion");
        reject(SIMPLE.replace("'hello' @value", "{ [ 'hello' ] } 'x' @value"), "nullable unbounded");
        reject(SIMPLE.replace("'hello' @value", "[ [ 'hello' ] ] @value"), "nested container capture");
        reject(SIMPLE.replace("'hello' @value", "{ Maybe } @value").replace("Root ::=", "Maybe ::= [ 'x' ];\nRoot ::="), "nullable unbounded");
        reject(SIMPLE.replace("'hello' @value", "'' @value"), "empty literal");
        reject(SIMPLE.replace("grammar Example {", "grammar Example {\n@whitespace: python"), "whitespace");
        reject(SIMPLE.replace("grammar Example {", "grammar Example {\ntoken TEXT = StringParser"), "external token");
    }

    @Test public void zeroWidthTokensCannotCreateUnboundedLoopsOrLeftRecursion() {
        for (String declaration : new String[]{"EMPTY", "EOF", "LOOKAHEAD('x')", "NEGATIVE_LOOKAHEAD('x')", "UNTIL('#')"}) {
            String source = SIMPLE.replace("grammar Example {", "grammar Example { token T = " + declaration + "\n");
            reject(source.replace("'hello' @value", "{ T } 'hello' @value"), "nullable unbounded");
            reject(source.replace("'hello' @value", "(T | 'x')+ @value"), "nullable unbounded");
            reject(source.replace("'hello' @value", "T @value Root @value"), "left recursion");
        }
        reject(SIMPLE.replace("grammar Example {", "grammar Example { token T = REGEX('x')\n"), "token T");
        reject(SIMPLE.replace("'hello' @value", "Root{0} @value"), "left recursion");
        reject(SIMPLE.replace("'hello' @value", "(Root | 'x'){0} @value"), "left recursion");
    }

    @Test public void invalidUnicodeTextIsRejectedBeforeRustEmission() {
        var grammar = UBNFMapper.parse(SIMPLE).grammars().get(0);
        String invalid = String.valueOf((char) 0xd800);
        for (var token : java.util.List.of(
            new org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Until("T", invalid),
            new org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Negation("T", invalid),
            new org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Lookahead("T", invalid))) {
            var modified = new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl(
                grammar.name(), grammar.settings(), java.util.List.of(token), grammar.rules());
            var error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(modified));
            assertTrue(error.toString(), error.getMessage().contains("unpaired surrogate"));
        }
    }

    @Test public void lexicalBindingsUseAnExplicitAllowlistAndEofStaysNullable() {
        for (String parser : new String[]{"IdentifierParser", "org.unlaxer.parser.clang.IdentifierParser",
                "SingleQuotedParser", "org.unlaxer.parser.elementary.SingleQuotedParser",
                "DoubleQuotedParser", "org.unlaxer.parser.elementary.DoubleQuotedParser",
                "EndOfSourceParser", "org.unlaxer.parser.elementary.EndOfSourceParser"}) {
            String source = SIMPLE.replace("grammar Example {", "grammar Example { token T = " + parser + "\n")
                .replace("'hello' @value", "T @value");
            assertEquals(5, new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0)).size());
            if (parser.endsWith("EndOfSourceParser")) {
                reject(source.replace("T @value", "{ T } 'x' @value"), "nullable unbounded");
                reject(source.replace("T @value", "T Root @value"), "left recursion");
            }
        }
        for (String parser : new String[]{"other.IdentifierParser", "StringLiteralParser",
                "other.StringLiteralParser", "CodeStartParser", "other.CodeStartParser",
                "CodeEndParser", "other.CodeEndParser", "QuotedParser"}) {
            reject(SIMPLE.replace("grammar Example {", "grammar Example { token T = " + parser + "\n"), "external token");
        }
    }

    @Test public void tinyExpressionStringLiteralUsesDoubleThenSingleQuotedChoice() {
        String source = SIMPLE
            .replace("grammar Example {", "grammar Example { token STRING = org.unlaxer.tinyexpression.parser.StringLiteralParser\n")
            .replace("'hello' @value", "STRING @value");
        var body = RustGrammarLowering.lower(UBNFMapper.parse(source).grammars().get(0)).rules().get(0).body();
        assertEquals(
            new GrammarIR.Sequence(List.of(new GrammarIR.Capture("value", new GrammarIR.Choice(List.of(
                new GrammarIR.QuotedToken('"'), new GrammarIR.QuotedToken('\'')))))),
            body);
    }

    @Test public void tinyExpressionCodeFencesUseExactAtomicBindings() {
        String source = SIMPLE
            .replace("grammar Example {", """
                grammar Example {
                token START = org.unlaxer.tinyexpression.parser.javalang.CodeStartParser
                token END = org.unlaxer.tinyexpression.parser.javalang.CodeEndParser
                """)
            .replace("'hello' @value", "(START | END) @value");
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var body = RustGrammarLowering.lower(grammar).rules().get(0).body();
        assertEquals(
            new GrammarIR.Sequence(List.of(new GrammarIR.Capture("value", new GrammarIR.Choice(List.of(
                new GrammarIR.CodeStartToken(), new GrammarIR.CodeEndToken()))))),
            body);
        String parser = new RustBackend().generate(grammar).stream()
            .filter(file -> file.relativePath().equals("parser.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(parser, parser.contains("Expr::Choice(vec![Expr::CodeStart, Expr::CodeEnd])"));
    }

    @Test public void cardinalityReachesAstAndSemantics() {
        for (String expression : new String[]{"[ 'hello' ] @value", "[ 'hello' @value ]", "('hello' @value | 'bye')"}) {
            var files = new RustBackend().generate(UBNFMapper.parse(SIMPLE.replace("'hello' @value", expression)).grammars().get(0));
            assertTrue(expression, files.get(1).content().contains("r#value: Option<String>"));
            assertTrue(expression, files.get(4).content().contains("r#value: Option<&str>"));
        }
        for (String expression : new String[]{"{ 'hello' } @value", "{ 'hello' @value }", "'hello'+ @value",
            "'hello'{1,2} @value", "'hello' % ',' @value", "'hello' @value 'bye' @value"}) {
            var files = new RustBackend().generate(UBNFMapper.parse(SIMPLE.replace("'hello' @value", expression)).grammars().get(0));
            assertTrue(expression, files.get(1).content().contains("r#value: Vec<String>"));
            assertTrue(expression, files.get(4).content().contains("r#value: &[String]"));
        }
    }

    @Test public void sharedMappingsAreDeduplicatedButTheirSchemasMustAgree() {
        String source = """
            grammar Shared {
              @root Root ::= First | Second;
              @mapping(Value, params=[value]) First ::= 'a' @value;
              @mapping(Value, params=[value]) Second ::= 'b' @value;
            }
            """;
        var files = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
        assertEquals(1, files.get(1).content().split("r#Value \\{ span: Span", -1).length - 1);
        assertEquals(1, files.get(4).content().split("fn eval_value", -1).length - 1);
        assertTrue(files.get(3).content().contains("1 => map_rule_1(tree, id)"));
        assertTrue(files.get(3).content().contains("2 => map_rule_2(tree, id)"));
        assertTrue(files.get(3).content().contains("fn map_rule_1(tree: &Tree, id: usize)"));
        assertTrue(files.get(3).content().contains("fn map_rule_2(tree: &Tree, id: usize)"));
        reject(source.replace("'b' @value", "[ 'b' ] @value"), "incompatible shared mapping schema");
        reject(source.replace("Second ::= 'b' @value", "Second ::= 'b' @other")
            .replace("@mapping(Value, params=[value]) Second", "@mapping(Value, params=[other]) Second"), "incompatible shared mapping schema");
        reject(source.replace("@mapping(Value, params=[value]) Second", "@mapping(VALUE, params=[value]) Second"), "mapping method collision");
    }

    @Test public void mixedValuesKeepTextBranchesAndCardinalityInGeneratedApis() {
        String source = """
            grammar Mixed {
              @root @mapping(RootValue, params=[one, maybe, many])
              Root ::= Value @one ':' [Value] @maybe ':' {Value} @many;
              Value ::= 'literal' | Leaf;
              @mapping(Leaf, params=[text]) Leaf ::= 'node' @text;
            }
            """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var ir = RustGrammarLowering.lower(grammar);
        assertEquals(List.of(GrammarIR.Kind.VALUE, GrammarIR.Kind.VALUE, GrammarIR.Kind.VALUE),
            ir.rules().get(0).mapping().fields().stream().map(GrammarIR.Field::kind).toList());
        var files = new RustBackend().generate(grammar);
        assertTrue(files.get(1).content().contains("r#one: AstValue, r#maybe: Option<AstValue>, r#many: Vec<AstValue>"));
        assertTrue(files.get(4).content().contains("r#one: &AstValue, r#maybe: Option<&AstValue>, r#many: &[AstValue]"));
        assertTrue(files.get(4).content().contains("r#maybe.as_ref()"));
        assertTrue(files.get(2).content().contains("Expr::Literal(\"literal\").text_value()"));
        assertTrue(files.get(3).content().contains("unlaxer_runtime::TEXT_VALUE_RULE => found.push(AstValue::Text"));
        assertTrue(files.get(3).content().contains("0 | 2 => found.extend(map_node"));
        assertTrue(files.get(3).content().contains("values.extend(map_values(tree, &capture.nodes)?);"));
    }

    @Test public void sharedMappingsJoinKindsBeforeRewritingAllCaptureSites() {
        String first = "@mapping(Box, params=[value]) First ::= 'a' @value;";
        String second = "@mapping(Box, params=[value]) Second ::= Leaf @value;";
        String leaf = "@mapping(Leaf, params=[text]) Leaf ::= 'b' @text;";
        for (String declarations : List.of(first + second + leaf, second + leaf + first)) {
            var grammar = UBNFMapper.parse("grammar Shared { @root Root ::= First | Second; " + declarations + " }")
                .grammars().get(0);
            var ir = RustGrammarLowering.lower(grammar);
            assertEquals(2, ir.mappings().size());
            ir.rules().stream().filter(rule -> rule.mapping() != null && rule.mapping().name().equals("Box"))
                .forEach(rule -> assertEquals(GrammarIR.Kind.VALUE, rule.mapping().fields().get(0).kind()));
            var files = new RustBackend().generate(grammar);
            assertTrue(files.get(2).content().contains("Expr::Capture(\"value\", Box::new(Expr::Literal(\"a\").text_value()))"));
            assertEquals(1, files.get(1).content().split("r#Box \\{ span: Span", -1).length - 1);
        }
    }

    @Test public void nestedMixedChoicesAndSemanticSequencesAreRetained() {
        String source = """
            grammar Mixed {
              @root @mapping(Box, params=[value]) Root ::= (Value Leaf) @value;
              Value ::= [('a' | Leaf)];
              @mapping(Leaf, params=[text]) Leaf ::= 'b' @text;
            }
            """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var field = RustGrammarLowering.lower(grammar).rules().get(0).mapping().fields().get(0);
        assertEquals(new GrammarIR.Field("value", GrammarIR.Kind.VALUE, GrammarIR.Cardinality.MANY), field);
        assertTrue(new RustBackend().generate(grammar).get(2).content().contains("Expr::Literal(\"a\").text_value()"));
        reject(source.replace("(Value Leaf) @value", "Leaf % Value @value"), "mapped separator");
    }

    @Test public void mixedUncapturedChoicesDoNotChangeExistingArtifacts() {
        String source = """
            grammar Mixed {
              @root @mapping(Box) Root ::= 'a' | Leaf;
              @mapping(Leaf) Leaf ::= 'b';
            }
            """;
        var files = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
        assertFalse(files.get(1).content().contains("AstValue"));
        assertFalse(files.get(2).content().contains("TextValue"));
        assertFalse(files.get(3).content().contains("map_values"));
    }

    @Test public void sharedAssocSchemasValidateLocallyBeforePromotion() {
        String source = """
            grammar Shared {
              @root Root ::= First | Second;
              @leftAssoc @mapping(Binary, params=[left,op,right])
              First ::= 'a' @left {'+' @op 'a' @right};
              @leftAssoc @mapping(Binary, params=[left,op,right])
              Second ::= Leaf @left {'+' @op Leaf @right};
              @mapping(Leaf) Leaf ::= 'b';
            }
            """;
        var ir = RustGrammarLowering.lower(UBNFMapper.parse(source).grammars().get(0));
        assertEquals(ir.rules().get(1).mapping(), ir.rules().get(2).mapping());
        assertEquals(List.of(GrammarIR.Kind.VALUE, GrammarIR.Kind.TEXT, GrammarIR.Kind.VALUE),
            ir.rules().get(1).mapping().fields().stream().map(GrammarIR.Field::kind).toList());
        reject(source.replace("'a' @right", "Leaf @right"), "@leftAssoc requires");
        reject(source.replace("Leaf @left", "[Leaf] @left"), "@leftAssoc requires");
    }

    @Test public void rightAssocSyntheticChoiceDoesNotBecomeATextValueBranch() {
        String source = """
            grammar MixedPower {
              @root @mapping(Box, params=[value]) Root ::= ('z' | Expr) @value;
              @rightAssoc @mapping(Power, params=[left,op,right])
              Expr ::= 'x' @left {'^' @op Expr @right};
            }
            """;
        String parser = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0)).get(2).content();
        assertTrue(parser.contains("Expr::Literal(\"z\").text_value()"));
        String power = parser.lines().filter(line -> line.contains("Rule { name: \"Expr\"")).findFirst().orElseThrow();
        assertTrue(power.contains("Expr::Choice"));
        assertFalse(power.contains("TextValue"));
    }

    @Test public void leftAssocMetadataDoesNotRewriteTheGrammarAndInvalidShapesAreRejected() {
        String source = """
            grammar Operators {
              @root @leftAssoc @precedence(level=10) @mapping(Binary, params=[left, op, right])
              Root ::= 'x' @left { '+' @op 'x' @right };
            }
            """;
        var files = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
        var plain = new RustBackend().generate(UBNFMapper.parse(source.replace("@leftAssoc @precedence(level=10)", "")).grammars().get(0));
        assertEquals(plain.get(1), files.get(1));
        assertEquals(plain.get(3), files.get(3));
        assertEquals(plain.get(4), files.get(4));
        assertTrue(files.get(2).content().contains("precedence: 10, associativity: Associativity::Left"));
        var defaults = new RustBackend().generate(UBNFMapper.parse(source.replace("@precedence(level=10)", "")).grammars().get(0));
        assertTrue(defaults.get(2).content().contains("precedence: -1, associativity: Associativity::Left"));
        var precedenceOnly = new RustBackend().generate(UBNFMapper.parse(source.replace("@leftAssoc", "")).grammars().get(0));
        assertTrue(precedenceOnly.get(2).content().contains("precedence: 10, associativity: Associativity::None"));
        reject(source.replace("@leftAssoc", "@leftAssoc @leftAssoc"), "duplicate/conflicting associativity");
        reject(source.replace("@precedence(level=10)", "@precedence(level=10) @precedence(level=20)"), "duplicate @precedence");
        reject(source.replace("@leftAssoc", "@rightAssoc"), "@rightAssoc requires");
        reject(source.replace("{ '+' @op 'x' @right }", "'+' @op 'x' @right"), "@leftAssoc requires");
        reject(source.replace("'x' @left", "[ 'x' ] @left"), "@leftAssoc requires");
        reject(source.replace("'+' @op 'x' @right", "'x' @right '+' @op"), "@leftAssoc requires");
        reject(source.replace("params=[left, op, right]", "params=[right, op, left]"), "@leftAssoc requires");
        reject(source.replace("'x' @right", "[ 'x' ] @right"), "@leftAssoc requires");
        reject(source.replace("'x' @left", "('x' @op) @left"), "@leftAssoc requires");
        reject(source.replace("'x' @left", "('x' @right) @left"), "@leftAssoc requires");
        reject(source.replace("'+' @op", "('+' @op) @op"), "@leftAssoc requires");
    }

    @Test public void rightAssocRewritesOnlyCanonicalRecursionAndPreservesVectorSchema() {
        String source = """
            grammar Power {
              @root @rightAssoc @precedence(level=10) @mapping(Power,params=[left, op, right])
              Expr ::= 'x' @left { '^' @op Expr @right };
            }
            """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var files = new RustBackend().generate(grammar);
        var plain = new RustBackend().generate(UBNFMapper.parse(source.replace("@rightAssoc @precedence(level=10)", "")).grammars().get(0));
        for (int index : List.of(1, 3, 4)) assertEquals(plain.get(index), files.get(index));
        assertTrue(files.get(2).content().contains("Associativity { Left, Right, None }"));
        assertTrue(files.get(2).content().contains("associativity: Associativity::Right"));
        assertTrue(files.get(2).content().contains("Expr::Choice"));
        assertFalse(files.get(2).content().contains("Expr::Repeat"));
        reject(source.replace("@rightAssoc", "@leftAssoc @rightAssoc"), "duplicate/conflicting associativity");
        reject(source.replace("@rightAssoc", "@rightAssoc @rightAssoc"), "duplicate/conflicting associativity");
        reject(source.replace("Expr @right", "'x' @right"), "@rightAssoc requires");
        reject(source.replace("Expr @right", "(Expr) @right"), "@rightAssoc requires");
        reject(source.replace("{ '^' @op Expr @right }", "('^' @op Expr @right){0,}"), "@rightAssoc requires");
        reject(source.replace("'x' @left", "['x'] @left"), "@rightAssoc requires");
        reject(source.replace("'^' @op", "('^' @op) @op"), "@rightAssoc requires");
        reject(source.replace("params=[left, op, right]", "params=[right, op, left]"), "@rightAssoc requires");
    }

    private void reject(String source, String reason) {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar));
        assertTrue(error.getMessage(), error.getMessage().contains(reason));
    }

    @Test public void cliValidatesOperatorMetadataBeforeCreatingArtifacts() throws Exception {
        String source = Files.readString(Path.of("src/test/resources/associative/Operators.ubnf"));
        for (String invalid : new String[]{source.replace("level=10", "level=30"),
                source.replace("@leftAssoc", ""), source.replace("@precedence(level=10)", ""),
                source.replace("NumericLiteral", "Number")}) {
            Path grammar = temporary.newFile().toPath();
            Files.writeString(grammar, invalid);
            Path output = temporary.getRoot().toPath().resolve(grammar.getFileName() + "-generated");
            assertEquals(3, run("generate", "--target", "rust", "--grammar", grammar.toString(), "--output", output.toString()));
            assertFalse(Files.exists(output));
        }
        Path grammar = temporary.newFile().toPath();
        Files.writeString(grammar, source);
        Path output = temporary.getRoot().toPath().resolve("operators-generated");
        assertEquals(0, run("generate", "--target", "rust", "--grammar", grammar.toString(), "--output", output.toString()));
        assertTrue(Files.isRegularFile(output.resolve("ast.rs")));
    }

    @Test public void cliAcceptsRightAssocAndRejectsConflictingMetadataBeforeOutput() throws Exception {
        String source = Files.readString(Path.of("src/test/resources/right-associative/Power.ubnf"));
        Path grammar = temporary.newFile().toPath();
        Files.writeString(grammar, source);
        Path output = temporary.getRoot().toPath().resolve("power-generated");
        assertEquals(0, run("generate", "--target", "rust", "--grammar", grammar.toString(), "--output", output.toString()));
        assertTrue(Files.readString(output.resolve("parser.rs")).contains("Associativity::Right"));
        for (String invalid : List.of(source.replace("@rightAssoc", "@rightAssoc @leftAssoc"),
                source.replace("@precedence(level=30)", ""), source.replace("level=30", "level=-1"),
                source.replace("Expr @right", "Atom @right"))) {
            Path rejected = temporary.getRoot().toPath().resolve("rejected");
            Files.writeString(grammar, invalid);
            assertEquals(3, run("generate", "--target", "rust", "--grammar", grammar.toString(), "--output", rejected.toString()));
            assertFalse(Files.exists(rejected));
        }
    }

    @Test public void cliChecksDriftAndProtectsHandwrittenFiles() throws Exception {
        Path grammar = temporary.newFile("sample.ubnf").toPath();
        Files.writeString(grammar, SIMPLE);
        Path output = temporary.getRoot().toPath().resolve("generated");
        String[] command = {"generate", "--target", "rust", "--grammar", grammar.toString(), "--output", output.toString()};
        assertEquals(0, run(command));
        String[] check = java.util.Arrays.copyOf(command, command.length + 1);
        check[command.length] = "--check";
        assertEquals(0, run(check));
        Files.writeString(output.resolve("evaluator.rs"), "// handwritten\n");
        assertEquals(4, run(check));
        String ast = Files.readString(output.resolve("ast.rs"));
        assertEquals(4, run(command));
        assertEquals(ast, Files.readString(output.resolve("ast.rs")));
        assertEquals("// handwritten\n", Files.readString(output.resolve("evaluator.rs")));
        Files.writeString(grammar, SIMPLE.replace("'hello' @value", "Missing @value"));
        assertEquals(3, run(command));
        assertEquals(2, run("generate", "--target", "java"));
    }

    @Test public void explicitRustWhitespaceNoneRemainsAcceptedByTheCli() throws Exception {
        Path grammar = temporary.newFile().toPath();
        Files.writeString(grammar, SIMPLE.replace("grammar Example {", "grammar Example { @whitespace: none"));
        Path output = temporary.getRoot().toPath().resolve("none-generated");
        assertEquals(0, run("generate", "--target", "rust", "--grammar", grammar.toString(), "--output", output.toString()));
        assertTrue(Files.readString(output.resolve("parser.rs")).contains("0, false, source"));
    }

    private int run(String... args) {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            return CodegenMain.run(args, stream, stream);
        }
    }
}
