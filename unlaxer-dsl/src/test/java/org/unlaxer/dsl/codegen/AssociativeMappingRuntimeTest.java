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
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

/** #138: keep associative repeat sites without reducing away the source-preserving CST. */
public class AssociativeMappingRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final String SHARED = """
        @root @mapping(Binary, params=[left, op, right]) @leftAssoc @precedence(level=10)
        Expression ::= Term @left { AddOp @op Term @right };
        @mapping(Binary, params=[left, op, right]) @leftAssoc @precedence(level=20)
        Term ::= Factor @left { MulOp @op Factor @right };
        AddOp ::= '+' | '-';
        MulOp ::= '*' | '/';
        Factor ::= NumericLiteral | '(' Expression ')';
        @mapping(Number, params=[value]) NumericLiteral ::= (NUMBER) @value;
        """;

    private URLClassLoader compile(String rules) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Assoc {
              @package: org.example.assoc
              @whitespace: javaStyle
              token NUMBER = org.unlaxer.parser.elementary.NumberParser
            %s
            }
            """.formatted(rules)).grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/assoc/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(rules + "\n" + diagnostics.getDiagnostics(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Class<?> mapper(URLClassLoader loader) throws Exception {
        return loader.loadClass("org.example.assoc.AssocMapper");
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

    @Test public void scalarOperandsRemainOrderedFlatLists() throws Exception {
        try (var loader = compile("""
            @root @mapping(Binary, params=[left, op, right]) @leftAssoc @precedence(level=10)
            Expression ::= Atom @left { '-' @op Atom @right };
            Atom ::= NUMBER;
            """)) {
            Object node = parse(loader, "9-3-1");
            assertEquals("9", field(node, "left"));
            assertEquals(List.of("-", "-"), list(node, "op"));
            assertEquals(List.of("3", "1"), list(node, "right"));
            Object base = parse(loader, "9");
            assertEquals("9", field(base, "left"));
            assertEquals(List.of(), list(base, "op"));
            assertEquals(List.of(), list(base, "right"));
        }
    }

    @Test public void sharedMappingKeepsTypedLeavesPrecedenceAndNestedRuleBoundaries() throws Exception {
        try (var loader = compile(SHARED)) {
            for (var entry : List.of(new Object[]{"9-3-1", 5.0}, new Object[]{"8/4/2", 1.0},
                    new Object[]{"1+2*3", 7.0}, new Object[]{"(1+2)*3", 9.0},
                    new Object[]{"(9-3)-(2-1)", 5.0}, new Object[]{"1*(2+3)*4", 20.0})) {
                assertEquals((String) entry[0], (Double) entry[1], evaluate(parse(loader, (String) entry[0])), 0.0);
            }
            Object node = parse(loader, "(9-3)-(2-1)");
            assertEquals(List.of("-"), list(node, "op"));
            assertEquals(1, list(node, "right").size());
            Object product = list(parse(loader, "1+2*3"), "right").get(0);
            assertEquals("Number", field(product, "left").getClass().getSimpleName());
            assertEquals(List.of("*"), list(product, "op"));
            assertEquals("3", field(list(product, "right").get(0), "value"));
        }
    }

    @Test public void triviaAndParenthesesPreserveNodeSourceSpans() throws Exception {
        try (var loader = compile(SHARED)) {
            String input = " 1 + 2*3 ";
            Object mapped = mapper(loader).getMethod("parseWithSourceMap", String.class).invoke(null, input);
            Object root = field(mapped, "ast");
            assertEquals(7.0, evaluate(root), 0.0);
            assertSpan(mapped, root, 0, input.length());
            Object term = field(root, "left");
            assertSpan(mapped, term, 1, 3);
            // A mapped rule includes its delimiter trivia; the NUMBER token itself does not.
            assertSpan(mapped, field(term, "left"), 1, 3);
            Object product = list(root, "right").get(0);
            assertSpan(mapped, product, 5, 9);
            assertSpan(mapped, field(product, "left"), 5, 6);
            assertSpan(mapped, list(product, "right").get(0), 7, 9);
            Object commented = parse(loader, "1 /*inner*/ +2*3");
            assertEquals(List.of("+"), list(commented, "op"));
            // The grouped NUMBER capture deliberately retains internal delimiter comments.
            assertEquals("1 /*inner*/", field(field(field(commented, "left"), "left"), "value"));
            assertEquals(6.0, evaluate(list(commented, "right").get(0)), 0.0);
            // Later parses must not invalidate the previous source map.
            assertSpan(mapped, product, 5, 9);
        }
    }

    @Test public void legacyReducedTokensStillMapWithoutCollectingInnerRepeats() throws Exception {
        try (var loader = compile(SHARED)) {
            Parser parser = (Parser) loader.loadClass("org.example.assoc.AssocParsers")
                .getMethod("getRootParser").invoke(null);
            try (var context = new ParseContext(StringSource.createRootSource("(9-3)-(2-1)"))) {
                var parsed = parser.parse(context);
                Object mapped = mapper(loader).getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class)
                    .invoke(null, parsed.getRootToken(true));
                Object root = field(mapped, "ast");
                assertEquals(List.of("-"), list(root, "op"));
                assertEquals(5.0, evaluate(root), 0.0);
            }
        }
    }

    private void assertSpan(Object mapped, Object node, int start, int end) throws Exception {
        Object span = mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, node);
        assertTrue(span instanceof Optional<?>);
        assertArrayEquals(new int[]{start, end}, (int[]) ((Optional<?>) span).orElseThrow());
    }

    /** Independent left fold: no generated evaluator or parser metadata determines the expected values. */
    private double evaluate(Object node) throws Exception {
        if (node.getClass().getSimpleName().equals("Number")) {
            return Double.parseDouble((String) field(node, "value"));
        }
        double result = evaluate(field(node, "left"));
        List<?> ops = list(node, "op");
        List<?> rights = list(node, "right");
        assertEquals("One right operand per operator", ops.size(), rights.size());
        for (int i = 0; i < ops.size(); i++) {
            double right = evaluate(rights.get(i));
            result = switch ((String) ops.get(i)) {
                case "+" -> result + right;
                case "-" -> result - right;
                case "*" -> result * right;
                case "/" -> result / right;
                default -> throw new AssertionError("Unexpected operator: " + ops.get(i));
            };
        }
        return result;
    }
}
