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

/** #177: capture occurrences, not parser classes or distinct tokens, determine fields. */
public class NestedCaptureRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String body) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Nested {
              @package: org.example.nested
              token N = NumberParser
            """ + body + "\n}").grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/nested/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(body + "\n" + diagnostics.getDiagnostics(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object parse(URLClassLoader loader, String input) throws Exception {
        return loader.loadClass("org.example.nested.NestedMapper").getMethod("parse", String.class).invoke(null, input);
    }

    private Object field(Object ast, String name) throws Exception {
        return ast.getClass().getMethod(name).invoke(ast);
    }

    private void span(URLClassLoader loader, Object value, int start, int end) throws Exception {
        var result = (Optional<?>) loader.loadClass("org.example.nested.NestedMapper")
            .getMethod("sourceSpanOf", Object.class).invoke(null, value);
        assertArrayEquals(new int[]{start, end}, (int[]) result.orElseThrow());
    }

    @Test public void innerCompletesBeforeOuterAndEveryTextRetainsItsSpan() throws Exception {
        try (var loader = compile("@root @mapping(Box, params=[name]) Root ::= ('😀' @name 'b') @name;")) {
            Object ast = parse(loader, "😀b");
            assertEquals("java.util.List<java.lang.String>", ast.getClass().getMethod("name").getGenericReturnType().getTypeName());
            List<?> names = (List<?>) field(ast, "name");
            assertEquals(List.of("😀", "😀b"), names);
            span(loader, names.get(0), 0, 1);
            span(loader, names.get(1), 0, 2);
            span(loader, ast, 0, 2);
        }
    }

    @Test public void coalescedBindingsAreDistinctOccurrencesEvenForEmptyEqualText() throws Exception {
        for (String expression : List.of("('a' @name) @name", "[ 'a' @name ] @name")) {
            try (var loader = compile("@root @mapping(Box, params=[name]) Root ::= " + expression + ";")) {
                List<?> values = (List<?>) field(parse(loader, "a"), "name");
                assertEquals(expression, List.of("a", "a"), values);
                assertNotSame(values.get(0), values.get(1));
                span(loader, values.get(0), 0, 1);
                span(loader, values.get(1), 0, 1);
                if (expression.startsWith("[")) assertEquals(List.of(), field(parse(loader, ""), "name"));
            }
        }
        try (var loader = compile("token E = EMPTY\n @root @mapping(Box, params=[name]) Root ::= (E @name) @name;")) {
            List<?> values = (List<?>) field(parse(loader, ""), "name");
            assertEquals(List.of("", ""), values);
            assertNotSame(values.get(0), values.get(1));
            span(loader, values.get(0), 0, 0);
            span(loader, values.get(1), 0, 0);
        }
    }

    @Test public void nestedQuantifiersKeepEachInnerOuterPairAndExcludeSeparators() throws Exception {
        for (String expression : List.of("{ 'a' @name 'b' } @name", "('a' @name 'b')+ @name",
                "('a' @name 'b'){1,2} @name", "('a' @name 'b') % ',' @name")) {
            try (var loader = compile("@root @mapping(Box, params=[name]) Root ::= " + expression + ";")) {
                String input = expression.contains("%") ? "ab,ab" : "abab";
                List<?> values = (List<?>) field(parse(loader, input), "name");
                assertEquals(expression, List.of("a", "ab", "a", "ab"), values);
                int second = expression.contains("%") ? 3 : 2;
                span(loader, values.get(0), 0, 1);
                span(loader, values.get(1), 0, 2);
                span(loader, values.get(2), second, second + 1);
                span(loader, values.get(3), second, second + 2);
                if (expression.startsWith("{")) assertEquals(List.of(), field(parse(loader, ""), "name"));
            }
        }
    }

    @Test public void alternativeSitesAreScalarMissingBranchIsOptionalAndSequentialSitesAreMany() throws Exception {
        try (var loader = compile("@root @mapping(Box, params=[name]) Root ::= 'a' @name | 'b' @name;")) {
            assertEquals("a", field(parse(loader, "a"), "name"));
            assertEquals("b", field(parse(loader, "b"), "name"));
        }
        try (var loader = compile("@root @mapping(Box, params=[name]) Root ::= 'a' @name | 'b';")) {
            assertEquals(Optional.of("a"), field(parse(loader, "a"), "name"));
            assertEquals(Optional.empty(), field(parse(loader, "b"), "name"));
        }
        try (var loader = compile("@root @mapping(Box, params=[name]) Root ::= 'a' @name 'b' @name;")) {
            assertEquals(List.of("a", "b"), field(parse(loader, "ab"), "name"));
        }
    }

    @Test public void numericElementTypesArePreservedAndRecursiveCapturesStayInTheirOwnRule() throws Exception {
        try (var loader = compile("@root @mapping(Box, params=[numbers]) Root ::= N @numbers ',' N @numbers;")) {
            Object ast = parse(loader, "1,2");
            assertEquals(List.of(1, 2), field(ast, "numbers"));
            assertEquals("java.util.List<java.lang.Integer>", ast.getClass().getMethod("numbers").getGenericReturnType().getTypeName());
        }
        try (var loader = compile("""
            @root @mapping(Box, params=[name, child])
            Root ::= ('a' @name 'b') @name [ '(' Root ')' ] @child;
            """)) {
            Object ast = parse(loader, "ab(ab)");
            assertEquals(List.of("a", "ab"), field(ast, "name"));
            Object child = ((Optional<?>) field(ast, "child")).orElseThrow();
            assertEquals(List.of("a", "ab"), field(child, "name"));
        }
    }

    @Test public void mappedNodesAndSemanticCollectionsPreserveDuplicateOccurrences() throws Exception {
        for (String body : List.of("(Leaf @values) @values", "(Pair @values) @values")) {
            try (var loader = compile("""
                @root @mapping(Box, params=[values]) Root ::= %s;
                Pair ::= Leaf Leaf;
                @mapping(Leaf, params=[text]) Leaf ::= 'a' @text;
                """.formatted(body))) {
                List<?> values = (List<?>) field(parse(loader, body.contains("Pair") ? "aa" : "a"), "values");
                assertEquals(body.contains("Pair") ? 4 : 2, values.size());
                for (Object value : values) assertEquals("a", field(value, "text"));
            }
        }
    }

    @Test public void sharedRecordRetainsItsUnifiedElementTypeAndRejectsDifferentCardinality() throws Exception {
        try (var loader = compile("""
            @root Root ::= Text | Nodes;
            @mapping(Box, params=[values]) Text ::= ('a' @values 'b') @values;
            @mapping(Box, params=[values]) Nodes ::= Leaf @values ',' Leaf @values;
            @mapping(Leaf, params=[text]) Leaf ::= 'x' @text;
            """)) {
            Object text = parse(loader, "ab");
            assertEquals("java.util.List<java.lang.Object>", text.getClass().getMethod("values").getGenericReturnType().getTypeName());
            assertEquals(List.of("a", "ab"), field(text, "values"));
            List<?> nodes = (List<?>) field(parse(loader, "x,x"), "values");
            assertEquals(2, nodes.size());
            assertEquals("x", field(nodes.get(0), "text"));
            assertEquals("x", field(nodes.get(1), "text"));
        }
        var grammar = UBNFMapper.parse("""
            grammar Bad {
              @root Root ::= First | Second;
              @mapping(Box, params=[name]) First ::= 'a' @name;
              @mapping(Box, params=[name]) Second ::= ('a' @name) @name;
            }
            """).grammars().get(0);
        assertThrows(IllegalArgumentException.class, () -> new ASTGenerator().generate(grammar));
        assertThrows(IllegalArgumentException.class, () -> new MapperGenerator().generate(grammar));
    }
}
