package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** #145: one associative record schema independent of its representative rule. */
public class SharedAssociativeSchemaRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String grammar(String rules) {
        return """
            grammar Shared {
              @package: org.example.shared
              @whitespace: javaStyle
              token NUMBER = org.unlaxer.parser.elementary.NumberParser
            %s
            }
            """.formatted(rules);
    }

    private URLClassLoader compile(String rules) throws Exception {
        var grammar = UBNFMapper.parse(grammar(rules)).grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/shared/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(rules + "\n" + diagnostics.getDiagnostics(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object parse(URLClassLoader loader, String input) throws Exception {
        return loader.loadClass("org.example.shared.SharedMapper").getMethod("parse", String.class).invoke(null, input);
    }

    private Object field(Object node, String name) throws Exception {
        return node.getClass().getMethod(name).invoke(node);
    }

    private List<?> list(Object node, String name) throws Exception {
        return (List<?>) field(node, name);
    }

    @Test public void transparentTypedLeavesAndMixedAssociativityWorkInBothDeclarationOrders() throws Exception {
        String expression = """
            @root @mapping(Binary, params=[left,op,right]) @leftAssoc @precedence(level=10)
            Expression ::= Exponent @left { '*' @op Exponent @right };
            """;
        String exponent = """
            @mapping(Binary, params=[left,op,right]) @rightAssoc @precedence(level=20)
            Exponent ::= Factor @left { '^' @op Exponent @right };
            """;
        String leaf = """
            Factor ::= NumericLiteral | '(' Expression ')';
            @mapping(Number, params=[value]) NumericLiteral ::= (NUMBER) @value;
            """;
        for (String rules : List.of(expression + exponent + leaf, exponent + expression + leaf)) {
            try (var loader = compile(rules)) {
                Object root = parse(loader, "2^3^2*2");
                assertEquals(loader.loadClass("org.example.shared.SharedAST"), root.getClass().getMethod("left").getReturnType());
                assertEquals("java.util.List<org.example.shared.SharedAST>",
                    root.getClass().getMethod("right").getGenericReturnType().getTypeName());
                assertEquals("Number", field(field(root, "left"), "left").getClass().getSimpleName());
                for (var entry : List.of(new Object[]{"2^3^2*2", 1024.0}, new Object[]{"2*3^2", 18.0},
                        new Object[]{"(2^3)^2", 64.0}, new Object[]{"2^(3^2)", 512.0}, new Object[]{"2", 2.0})) {
                    assertEquals((String) entry[0], (Double) entry[1], evaluate(parse(loader, (String) entry[0])), 0.0);
                }
                Object mapped = loader.loadClass("org.example.shared.SharedMapper")
                    .getMethod("parseWithSourceMap", String.class).invoke(null, " 2 ^ 3 ^ 2 ");
                Object outer = field(mapped, "ast");
                Object power = field(outer, "left");
                Object right = list(power, "right").get(0);
                Object number = field(right, "left");
                assertSpan(mapped, outer, 0, 11);
                assertSpan(mapped, right, 5, 11);
                assertSpan(mapped, number, 5, 7);
                parse(loader, "4");
                assertSpan(mapped, number, 5, 7);
            }
        }
    }

    @Test public void homogeneousRecursiveFamiliesKeepTheirNarrowRecordType() throws Exception {
        String expression = """
            @root @mapping(Binary, params=[left,op,right]) @leftAssoc @precedence(level=10)
            Expression ::= Term @left { '-' @op Term @right };
            """;
        String term = """
            @mapping(Binary, params=[left,op,right]) @leftAssoc @precedence(level=20)
            Term ::= Factor @left { '/' @op Factor @right };
            """;
        for (String rules : List.of(expression + term, term + expression)) {
            try (var loader = compile(rules + "Factor ::= NUMBER | '(' Expression ')';")) {
                Object root = parse(loader, "9-3-1");
                assertEquals(root.getClass(), root.getClass().getMethod("left").getReturnType());
                assertEquals(List.of("-", "-"), list(root, "op"));
                assertEquals(5.0, evaluate(root), 0.0);
                assertEquals(1.0, evaluate(parse(loader, "8/4/2")), 0.0);
                assertEquals(5.0, evaluate(parse(loader, "(9-3)-(2-1)")), 0.0);
            }
        }
    }

    @Test public void singleRightAssociativeRulePreservesTypedAndParenthesizedBaseAlternatives() throws Exception {
        try (var loader = compile("""
            @root @mapping(Power,params=[left,op,right]) @rightAssoc @precedence(level=30)
            Expr ::= Base @left { '^' @op Expr @right };
            Base ::= Atom | '(' Expr ')';
            @mapping(Number,params=[value]) Atom ::= (NUMBER) @value;
            """)) {
            for (var entry : List.of(new Object[]{"2^3^2", 512.0}, new Object[]{"(2^3)^2", 64.0},
                    new Object[]{"2^(3^2)", 512.0}, new Object[]{"(2)", 2.0})) {
                assertEquals((String) entry[0], (Double) entry[1], evaluate(parse(loader, (String) entry[0])), 0.0);
            }
            Object root = parse(loader, "2^3");
            assertEquals(loader.loadClass("org.example.shared.SharedAST"), root.getClass().getMethod("left").getReturnType());
            assertEquals("java.util.List<org.example.shared.SharedAST>",
                root.getClass().getMethod("right").getGenericReturnType().getTypeName());
            assertEquals("Number", field(root, "left").getClass().getSimpleName());
        }
    }

    @Test public void singleLeftAssociativeStringFoldKeepsItsSourceTextApi() throws Exception {
        try (var loader = compile("""
            @root @mapping(Concat,params=[left,op,right]) @leftAssoc @precedence(level=10)
            Expr ::= Base @left { '+' @op Base @right };
            Base ::= Function | OtherFunction | Text | Parenthesized;
            Text ::= 'a';
            Parenthesized ::= '(' Expr ')';
            @mapping(Call,params=[value]) Function ::= 'f' ('a') @value;
            @mapping(OtherCall,params=[value]) OtherFunction ::= 'g' ('a') @value;
            """)) {
            Object root = parse(loader, "a+fa");
            assertEquals(Object.class, root.getClass().getMethod("left").getReturnType());
            assertEquals("java.util.List<java.lang.Object>",
                root.getClass().getMethod("right").getGenericReturnType().getTypeName());
            assertEquals("a", field(root, "left"));
            assertEquals("Call", list(root, "right").get(0).getClass().getSimpleName());
            assertEquals("a", field(list(root, "right").get(0), "value"));
        }
    }

    @Test public void sharedScalarAndDirectMappedFamiliesUseEachRulesActualOperandMapper() throws Exception {
        for (String operand : List.of("Atom", "NumericLiteral", "NUMBER")) {
            boolean mapped = operand.equals("NumericLiteral");
            String first = "@mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=10) "
                + "First ::= 'a' " + operand + " @left { '+' @op " + operand + " @right };";
            String second = "@mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=10) "
                + "Second ::= 'b' " + operand + " @left { '-' @op " + operand + " @right };";
            String leaf = mapped ? "@mapping(Number,params=[value]) NumericLiteral ::= NUMBER @value;" : "Atom ::= NUMBER;";
            for (String rules : List.of(first + second, second + first)) {
                try (var loader = compile("@root Root ::= First | Second;" + rules + leaf)) {
                    Object root = parse(loader, "b9-3-1");
                    assertEquals(mapped ? "Number" : operand.equals("NUMBER") ? "int" : "String",
                        root.getClass().getMethod("left").getReturnType().getSimpleName());
                    assertEquals(5.0, evaluate(root), 0.0);
                    assertEquals(7.0, evaluate(parse(loader, "a3+4")), 0.0);
                }
            }
        }
    }

    @Test public void differentDirectMappedLeavesArePreservedWithoutStringFallback() throws Exception {
        String first = "@mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=10) "
            + "First ::= 'a' NumericLiteral @left { '+' @op NumericLiteral @right };";
        String second = "@mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=10) "
            + "Second ::= 'b' Named @left { '+' @op Named @right };";
        String leaf = "@mapping(Number,params=[value]) NumericLiteral ::= NUMBER @value;"
            + "@mapping(Name,params=[value]) Named ::= 'one' @value;";
        for (String rules : List.of(first + second, second + first)) {
            try (var loader = compile("@root Root ::= First | Second;" + rules + leaf)) {
                Object root = parse(loader, "bone+one");
                assertEquals("Name", field(root, "left").getClass().getSimpleName());
                assertEquals("Name", list(root, "right").get(0).getClass().getSimpleName());
                assertEquals(7.0, evaluate(parse(loader, "a3+4")), 0.0);
            }
        }
    }

    @Test public void incompatibleScalarFamiliesFailExplicitlyInBothDeclarationOrders() {
        String first = "@mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=10) "
            + "First ::= NUMBER @left { '+' @op NUMBER @right };";
        String second = "@mapping(Binary,params=[left,op,right]) @leftAssoc @precedence(level=10) "
            + "Second ::= 'x' @left { '+' @op 'x' @right };";
        for (String rules : List.of(first + second, second + first)) {
            var grammar = UBNFMapper.parse(grammar("@root Root ::= First | Second;" + rules)).grammars().get(0);
            for (CodeGenerator generator : List.of(new ASTGenerator(), new MapperGenerator())) {
                var error = assertThrows(IllegalArgumentException.class, () -> generator.generate(grammar));
                assertTrue(error.getMessage(), error.getMessage().contains("Incompatible shared associative mapping Binary"));
                assertTrue(error.getMessage(), error.getMessage().contains("scalar operand types"));
            }
        }
    }

    private void assertSpan(Object mapped, Object node, int start, int end) throws Exception {
        Object span = mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, node);
        assertArrayEquals(new int[]{start, end}, (int[]) ((Optional<?>) span).orElseThrow());
    }

    private double evaluate(Object node) throws Exception {
        if (node instanceof String text) return Double.parseDouble(text);
        if (node instanceof Number number) return number.doubleValue();
        if (node.getClass().getSimpleName().equals("Number")) return evaluate(field(node, "value"));
        Object left = field(node, "left");
        if (left == null) return evaluate(list(node, "op").get(0));
        double result = evaluate(left);
        List<?> ops = list(node, "op");
        List<?> rights = list(node, "right");
        assertEquals(ops.size(), rights.size());
        for (int i = 0; i < ops.size(); i++) {
            double right = evaluate(rights.get(i));
            result = switch ((String) ops.get(i)) {
                case "+" -> result + right;
                case "-" -> result - right;
                case "*" -> result * right;
                case "/" -> result / right;
                case "^" -> Math.pow(result, right);
                default -> throw new AssertionError("Unexpected operator: " + ops.get(i));
            };
        }
        return result;
    }
}
