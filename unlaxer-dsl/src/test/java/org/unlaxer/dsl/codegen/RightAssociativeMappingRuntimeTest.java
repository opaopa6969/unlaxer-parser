package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
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

/** #139: compile and execute canonical right-associative parser/AST/mapper output. */
public class RightAssociativeMappingRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String rules) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Power {
              @package: org.example.power
              @whitespace: javaStyle
              token NUMBER = org.unlaxer.parser.elementary.NumberParser
            %s
            }
            """.formatted(rules)).grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/power/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
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

    private Class<?> mapper(URLClassLoader loader) throws Exception {
        return loader.loadClass("org.example.power.PowerMapper");
    }

    private Object parse(URLClassLoader loader, String input) throws Exception {
        return mapper(loader).getMethod("parse", String.class).invoke(null, input);
    }

    private Object field(Object node, String name) throws Exception {
        return node.getClass().getMethod(name).invoke(node);
    }

    private List<?> list(Object node, String name) throws Exception {
        return (List<?>) field(node, name);
    }

    private String simple(String base, String suffix) {
        return """
            @root @mapping(Pow, params=[left, op, right]) @rightAssoc @precedence(level=10)
            Expr ::= %s @left { '^' @op Expr @right };
            %s
            """.formatted(base, suffix);
    }

    @Test public void textBaseAndRecursiveRightHaveDifferentTypesWithoutRefolding() throws Exception {
        try (var loader = compile(simple("Atom", "Atom ::= NUMBER;"))) {
            Object root = parse(loader, "2^3^2");
            assertEquals(String.class, root.getClass().getMethod("left").getReturnType());
            assertEquals("2", field(root, "left"));
            assertEquals(List.of("^"), list(root, "op"));
            Object right = list(root, "right").get(0);
            assertEquals("3", field(right, "left"));
            assertEquals("2", field(list(right, "right").get(0), "left"));
            assertEquals(512.0, evaluate(root), 0.0);
            assertEquals(8.0, evaluate(parse(loader, "2^3")), 0.0);
            assertEquals(2.0, evaluate(parse(loader, "2")), 0.0);
            for (String invalid : List.of("", "2^", "^2", "2^^3", "2^3x")) {
                InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> parse(loader, invalid));
                assertTrue(error.getCause() instanceof IllegalArgumentException);
            }
        }
    }

    @Test public void primitiveAndMappedBaseTypesRemainUnchanged() throws Exception {
        try (var loader = compile(simple("NUMBER", ""))) {
            Object root = parse(loader, "2^3^2");
            assertEquals(int.class, root.getClass().getMethod("left").getReturnType());
            assertEquals(512.0, evaluate(root), 0.0);
            InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> parse(loader, "2147483648"));
            assertTrue(error.getCause() instanceof NumberFormatException);
        }
        try (var loader = compile(simple("NumericLiteral", """
            @mapping(Number, params=[value]) NumericLiteral ::= (NUMBER) @value;
            """))) {
            Object root = parse(loader, "2^3^2");
            assertEquals("Number", field(root, "left").getClass().getSimpleName());
            assertEquals(512.0, evaluate(root), 0.0);
        }
    }

    @Test public void sharedClassDispatchHonorsEachRulesAssociativityInEitherDeclarationOrder() throws Exception {
        String multiply = """
            @root @mapping(Binary, params=[left, op, right]) @leftAssoc @precedence(level=10)
            Expression ::= Exponent @left { '*' @op Exponent @right };
            """;
        String exponent = """
            @mapping(Binary, params=[left, op, right]) @rightAssoc @precedence(level=20)
            Exponent ::= Quotient @left { '^' @op Exponent @right };
            """;
        String leaves = """
            @mapping(Binary, params=[left, op, right]) @leftAssoc @precedence(level=30)
            Quotient ::= Factor @left { '/' @op Factor @right };
            Factor ::= NumericLiteral | '(' Expression ')';
            @mapping(Number, params=[value]) NumericLiteral ::= (NUMBER) @value;
            """;
        for (String rules : List.of(multiply + exponent + leaves, exponent + multiply + leaves)) {
            try (var loader = compile(rules)) {
                for (var entry : List.of(new Object[]{"2^3^2*2", 1024.0}, new Object[]{"2*3^2", 18.0},
                        new Object[]{"(2^3)^2", 64.0}, new Object[]{"2^(3^2)", 512.0},
                        new Object[]{"2^3*2", 16.0}, new Object[]{"2", 2.0})) {
                    assertEquals((String) entry[0], (Double) entry[1], evaluate(parse(loader, (String) entry[0])), 0.0);
                }
            }
        }
    }

    @Test public void recursiveNodesRetainTheirOwnSourceSpans() throws Exception {
        try (var loader = compile(simple("Atom", "Atom ::= NUMBER;"))) {
            Object mapped = mapper(loader).getMethod("parseWithSourceMap", String.class).invoke(null, " 2 ^ 3 ^ 2 ");
            Object root = field(mapped, "ast");
            Object right = list(root, "right").get(0);
            Object leaf = list(right, "right").get(0);
            assertSpan(mapped, root, 0, 11);
            assertSpan(mapped, right, 5, 11);
            assertSpan(mapped, leaf, 9, 11);
            parse(loader, "1");
            assertSpan(mapped, right, 5, 11);

        }
    }

    private void assertSpan(Object mapped, Object node, int start, int end) throws Exception {
        Object span = mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, node);
        assertTrue(span instanceof Optional<?>);
        assertArrayEquals(new int[]{start, end}, (int[]) ((Optional<?>) span).orElseThrow());
    }

    private double evaluate(Object node) throws Exception {
        if (node instanceof String text) return Double.parseDouble(text);
        if (node instanceof Number number) return number.doubleValue();
        if (node.getClass().getSimpleName().equals("Number")) return evaluate(field(node, "value"));
        double result = evaluate(field(node, "left"));
        List<?> ops = list(node, "op");
        List<?> rights = list(node, "right");
        assertEquals(ops.size(), rights.size());
        for (int i = 0; i < ops.size(); i++) {
            double right = evaluate(rights.get(i));
            result = switch ((String) ops.get(i)) {
                case "^" -> Math.pow(result, right);
                case "*" -> result * right;
                default -> throw new AssertionError("Unexpected operator: " + ops.get(i));
            };
        }
        return result;
    }
}
