package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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

/** #160: compile and execute semantic collections hidden inside an unmapped capture. */
public class SemanticCardinalityRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String expression, String helpers) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Semantic {
              @package: org.example.semantic
              @root @mapping(Box, params=[values]) Document ::= %s @values;
              %s
              Factor ::= 'a' | '😀' | Leaf;
              @mapping(Leaf, params=[text]) Leaf ::= ('x' | 'y') @text;
            }
            """.formatted(expression, helpers)).grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/semantic/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(expression + " / " + helpers + "\n" + diagnostics.getDiagnostics(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object values(URLClassLoader loader, String input) throws Exception {
        var mapper = loader.loadClass("org.example.semantic.SemanticMapper");
        Object ast = mapper.getMethod("parse", String.class).invoke(null, input);
        return ast.getClass().getMethod("values").invoke(ast);
    }

    private String text(Object value) throws Exception {
        return value instanceof String string ? string : value.getClass().getMethod("text").invoke(value).toString();
    }

    private void assertTexts(Object result, String... expected) throws Exception {
        assertTrue(result.toString(), result instanceof List<?>);
        List<?> values = (List<?>) result;
        assertEquals(List.of(expected), values.stream().map(value -> {
            try { return text(value); } catch (Exception error) { throw new AssertionError(error); }
        }).toList());
    }

    private void assertSpan(URLClassLoader loader, Object value, int start, int end) throws Exception {
        var mapper = loader.loadClass("org.example.semantic.SemanticMapper");
        var span = (Optional<?>) mapper.getMethod("sourceSpanOf", Object.class).invoke(null, value);
        assertArrayEquals(new int[]{start, end}, (int[]) span.orElseThrow());
    }

    @Test public void parallelNodesAndMixedValuesRetainOrderAndIndependentSpans() throws Exception {
        for (String helper : List.of("Helper ::= Leaf Leaf;", "Helper ::= Factor Factor;", "Helper ::= Alias; Alias ::= (Factor Factor);")) {
            try (var loader = compile("Helper", helper)) {
                List<?> nodes = (List<?>) values(loader, "xy");
                assertTexts(nodes, "x", "y");
                assertSpan(loader, nodes.get(0), 0, 1);
                assertSpan(loader, nodes.get(1), 1, 2);
                if (helper.contains("Factor")) {
                    List<?> mixed = (List<?>) values(loader, "😀x");
                    assertTexts(mixed, "😀", "x");
                    assertSpan(loader, mixed.get(0), 0, 1);
                    assertSpan(loader, mixed.get(1), 1, 2);
                    List<?> duplicate = (List<?>) values(loader, "aa");
                    assertTexts(duplicate, "a", "a");
                    assertNotSame(duplicate.get(0), duplicate.get(1));
                    assertSpan(loader, duplicate.get(0), 0, 1);
                    assertSpan(loader, duplicate.get(1), 1, 2);
                }
            }
        }
    }

    @Test public void helperOptionalIsAbsentRatherThanEmptyTextAndKeepsOuterTextBoundary() throws Exception {
        for (String child : List.of("Leaf", "Factor")) {
            try (var loader = compile("Helper", "Helper ::= '(' [" + child + "] ')';")) {
                assertEquals(Optional.empty(), values(loader, "()"));
                Object node = ((Optional<?>) values(loader, "(x)")).orElseThrow();
                assertEquals("Leaf", node.getClass().getSimpleName());
                assertSpan(loader, node, 1, 2);
                if (child.equals("Factor")) {
                    Object literal = ((Optional<?>) values(loader, "(a)")).orElseThrow();
                    assertEquals("(a)", literal);
                    assertSpan(loader, literal, 0, 3);
                }
            }
        }
    }

    @Test public void hiddenRepeatAndSeparatedFlattenEverySemanticItem() throws Exception {
        for (String child : List.of("Leaf", "Factor")) {
            for (String body : List.of("'[' {" + child + "} ']'", "'[' " + child + " % ',' ']'")) {
                try (var loader = compile("Helper", "Helper ::= " + body + ";")) {
                    if (!body.contains("%")) assertTexts(values(loader, "[]"));
                    assertTexts(values(loader, body.contains("%") ? "[x,y]" : "[xy]"), "x", "y");
                    if (child.equals("Factor")) assertTexts(values(loader, body.contains("%") ? "[a,x]" : "[ax]"), "a", "x");
                }
            }
        }
    }

    @Test public void scalarItemBoundariesSurviveNamedAndInlineContainers() throws Exception {
        for (String body : List.of("{Outer}", "{ '(' Factor ')' }", "Outer % ','", "('(' Factor ')') % ','")) {
            try (var loader = compile("Helper", "Helper ::= " + body + "; Outer ::= '(' Factor ')';")) {
                String input = body.contains("%") ? "(a),(x),(😀)" : "(a)(x)(😀)";
                List<?> result = (List<?>) values(loader, input);
                assertEquals(body, "(a)", text(result.get(0)));
                assertTexts(result, "(a)", "x", "(😀)");
                assertSpan(loader, result.get(0), 0, 3);
            }
        }
    }

    @Test public void outerQuantifiersFlattenInnerCollectionsWithoutNestedLists() throws Exception {
        for (String expression : List.of("[Helper]", "{Helper}", "Helper+", "Helper{1,2}", "Helper % ','")) {
            try (var loader = compile(expression, "Helper ::= '(' Factor Factor ')';")) {
                assertTexts(values(loader, "(ax)"), "a", "x");
                if (expression.startsWith("[") || expression.startsWith("{")) assertTexts(values(loader, ""));
                if (!expression.startsWith("[")) {
                    assertTexts(values(loader, expression.contains("%") ? "(ax),(ya)" : "(ax)(ya)"), "a", "x", "y", "a");
                }
            }
        }
    }

    @Test public void multiTokenTextAlternativeIsOneValueNotThreeTokens() throws Exception {
        try (var loader = compile("Helper", "Helper ::= Pair | '(' 'a' ')'; Pair ::= Leaf Leaf;")) {
            assertTexts(values(loader, "xy"), "x", "y");
            List<?> literal = (List<?>) values(loader, "(a)");
            assertTexts(literal, "(a)");
            assertSpan(loader, literal.get(0), 0, 3);
        }
    }
}
