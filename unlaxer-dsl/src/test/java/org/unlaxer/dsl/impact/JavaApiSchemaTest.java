package org.unlaxer.dsl.impact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.ASTGenerator;
import org.unlaxer.dsl.codegen.EvaluatorGenerator;

public class JavaApiSchemaTest {
    @Test
    public void generatedTreeDefinesNestedSumEnumAndFieldSchema() {
        String source = """
            grammar Schema {
              @package: example.schema
              token NUMBER = org.unlaxer.parser.elementary.NumberParser
              @root @mapping(Program, params=[items, maybe, count])
              Start ::= { Item @items } [ Item @maybe ] NUMBER @count;
              @mapping(Item)
              Item ::= Text | Numeric;
              @mapping(Item.Text, params=[value])
              Text ::= 'x' @value;
              @mapping(Item.Numeric, params=[value])
              Numeric ::= NUMBER @value;
              @enum
              Mode ::= 'on' | 'off';
            }
            """;
        var sourceSnapshot = UBNFMapper.parseWithSource(source);
        var result = JavaApiSchema.build(sourceSnapshot);
        assertEquals("example.schema.SchemaAST", result.astType());
        assertEquals("example.schema.SchemaEvaluator", result.evaluatorType());
        assertEquals(List.of("Item", "Item.Numeric", "Item.Text", "Mode", "Program"),
            result.nodes().stream().map(ApiImpact.Node::name).toList());

        var sum = node(result, "Item");
        assertEquals("interface", sum.kind());
        assertEquals(List.of("Item.Numeric", "Item.Text"), sum.variants());
        assertEquals(List.of("Item", "Numeric", "Text"),
            sum.origins().stream().map(ApiImpact.Origin::rule).toList());
        assertEquals(List.of("Item"), node(result, "Item.Numeric").parents());
        var mode = node(result, "Mode");
        assertEquals("enum", mode.kind());
        assertEquals(List.of("OFF", "ON"), mode.variants());
        assertEquals(List.of("Mode"), mode.origins().stream().map(ApiImpact.Origin::rule).toList());

        var program = node(result, "Program");
        assertEquals("record", program.kind());
        assertEquals(List.of("items", "maybe", "count"),
            program.fields().stream().map(ApiImpact.Field::name).toList());
        assertEquals(List.of("many", "optional", "one"),
            program.fields().stream().map(ApiImpact.Field::cardinality).toList());
        assertEquals("int", program.fields().get(2).type());
        assertTrue(program.fields().get(0).type().startsWith("List<"));
        assertTrue(program.fields().get(1).type().startsWith("Optional<"));
        assertEquals("example/schema/SchemaAST.java", program.generated().path());
        assertEquals("example/schema/SchemaAST.java", program.fields().get(0).generated().path());
        assertTrue(program.generated().line() > 1);
        assertTrue(program.fields().get(0).generated().column() > 1);

        String generated = new ASTGenerator().generate(sourceSnapshot.ast().grammars().get(0)).source();
        var field = program.fields().get(2);
        assertEquals("int count", slice(generated, field.generated().span()));
        assertEquals("Start", program.origins().get(0).rule());
        var startRule = sourceSnapshot.ast().grammars().get(0).rules().get(0);
        assertEquals(sourceSnapshot.spanOf(startRule).orElseThrow().start(),
            program.origins().get(0).span().start());
    }

    @Test
    public void sharedMappingUsesEveryContributingRule() {
        String source = """
            grammar Shared {
              token NUMBER = org.unlaxer.parser.elementary.NumberParser
              @root @mapping(Binary, params=[left,op,right]) @leftAssoc @precedence(level=10)
              Expression ::= Atom @left { '+' @op Atom @right };
              @mapping(Binary, params=[left,op,right]) @rightAssoc @precedence(level=20)
              Power ::= Atom @left { '^' @op Atom @right };
              Atom ::= NUMBER;
            }
            """;
        var result = JavaApiSchema.build(UBNFMapper.parseWithSource(source));
        assertEquals(List.of("Expression", "Power"),
            node(result, "Binary").origins().stream().map(ApiImpact.Origin::rule).toList());
        assertEquals(List.of("Expression", "Power"),
            method(result, "evalBinary").origins().stream().map(ApiImpact.Origin::rule).toList());
    }

    @Test
    public void evalAnnotationChangesRequirementAndAddsHelpersWithoutExecutingProvider() {
        String before = """
            grammar Eval {
              @root @mapping(Binary, params=[left,op,right])
              Root ::= '1' @left '+' @op '2' @right;
            }
            """;
        String after = before.replace("@mapping(Binary, params=[left,op,right])",
            "@mapping(Binary, params=[left,op,right]) @eval(kind='binary_arithmetic', strategy='default')");
        var oldSchema = JavaApiSchema.build(UBNFMapper.parseWithSource(before));
        var newSchema = JavaApiSchema.build(UBNFMapper.parseWithSource(after));
        assertTrue(method(oldSchema, "evalBinary").required());
        assertFalse(method(newSchema, "evalBinary").required());
        assertTrue(method(newSchema, "evalLeaf").required());
        assertTrue(method(newSchema, "applyBinary").required());
        assertEquals(List.of("Root"), method(newSchema, "evalLeaf").origins().stream()
            .map(ApiImpact.Origin::rule).toList());
        assertTrue(ApiImpact.changes(oldSchema, newSchema).stream().anyMatch(change ->
            change.kind().equals("METHOD_REQUIRED_CHANGED") && change.subject().equals("evalBinary")));
        assertTrue(ApiImpact.changes(oldSchema, newSchema).stream().anyMatch(change ->
            change.kind().equals("METHOD_ADDED") && change.subject().equals("applyBinary")));
    }

    @Test
    public void generatedLocationsCountUnicodeCodePointsNotUtf16Units() {
        String source = """
            grammar Unicode {
              @root @mapping(Value, params=[name])
              @eval(kind='variable_ref', strategy='default', strip_prefix='😀')
              Root ::= '😀' @name;
            }
            """;
        var snapshot = UBNFMapper.parseWithSource(source);
        var result = JavaApiSchema.build(snapshot);
        var helper = method(result, "resolveVariable");
        String generated = new EvaluatorGenerator(17).generate(snapshot.ast().grammars().get(0)).source();
        int utf16Start = generated.indexOf("protected abstract T resolveVariable");
        assertTrue(utf16Start > generated.indexOf("😀"));
        assertEquals(generated.codePointCount(0, utf16Start), helper.generated().span().start());
        assertEquals("UnicodeEvaluator.java", helper.generated().path().substring(
            helper.generated().path().lastIndexOf('/') + 1));
        assertEquals(List.of("Root"), helper.origins().stream().map(ApiImpact.Origin::rule).toList());
    }

    @Test
    public void ambiguousGeneratedNamesFailBeforeTheDiffPhase() {
        for (String[] caseAndCode : List.of(
            new String[]{"""
                @root @mapping(A.B, params=[value]) Root ::= 'x' @value;
                @mapping(AB, params=[value]) Other ::= 'y' @value;
                """, "duplicate generated method: evalAB"},
            new String[]{"""
                @root @mapping(Value, params=[value,value]) Root ::= 'x' @value;
                """, "duplicate generated field: Value.value"},
            new String[]{"""
                @root @mapping(Foo) Root ::= 'x';
                @enum Foo ::= 'on' | 'off';
                """, "duplicate generated node: Foo"},
            new String[]{"""
                @root @mapping(Value) Root ::= 'x';
                @enum Mode ::= 'on' | 'ON';
                """, "duplicate generated variant: Mode.ON"}
        )) {
            String source = "grammar Ambiguous {\n" + caseAndCode[0] + "\n}";
            try {
                JavaApiSchema.build(UBNFMapper.parseWithSource(source));
                fail("expected schema failure: " + caseAndCode[1]);
            } catch (IllegalStateException error) {
                assertTrue(error.getMessage(), error.getMessage().contains(caseAndCode[1]));
            }
        }
    }

    private static ApiImpact.Node node(ApiImpact.Snapshot snapshot, String name) {
        return snapshot.nodes().stream().filter(node -> node.name().equals(name)).findFirst().orElseThrow();
    }

    private static ApiImpact.Method method(ApiImpact.Snapshot snapshot, String name) {
        return snapshot.methods().stream().filter(method -> method.name().equals(name)).findFirst().orElseThrow();
    }

    private static String slice(String source, ApiImpact.Span span) {
        int start = source.offsetByCodePoints(0, span.start());
        int end = source.offsetByCodePoints(start, span.end() - span.start());
        return source.substring(start, end);
    }
}
