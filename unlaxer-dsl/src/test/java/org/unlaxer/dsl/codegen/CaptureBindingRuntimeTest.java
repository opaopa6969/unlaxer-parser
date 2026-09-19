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

/** #116: compile and execute the actual generated parser/mapper, not source-string proxies. */
public class CaptureBindingRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String body) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Bound {
              @package: org.example.bound
              @whitespace: javaStyle
              token NUMBER = NumberParser
            """ + body + "\n}").grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/bound/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object parse(URLClassLoader loader, String input) throws Exception {
        return loader.loadClass("org.example.bound.BoundMapper").getMethod("parse", String.class).invoke(null, input);
    }

    private Object field(Object value, String field) throws Exception {
        return value.getClass().getMethod(field).invoke(value);
    }

    @Test public void missingOptionalCannotStealRepeatedValuesOrDelimiters() throws Exception {
        try (var loader = compile("""
            @root @mapping(Container, params=[head, values, tail, flags])
            Root ::= [ Item ] @head ':' { Item } @values ':' [ '!' ] @tail ':' { 'x' } @flags ;
            @mapping(Item, params=[value]) Item ::= Digits @value ';' ;
            Digits ::= NUMBER ;
            """)) {
            Object empty = parse(loader, ":::");
            assertEquals(Optional.empty(), field(empty, "head"));
            assertEquals(List.of(), field(empty, "values"));
            assertEquals(Optional.empty(), field(empty, "tail"));
            assertEquals(List.of(), field(empty, "flags"));
            Object repeated = parse(loader, ":2;3;::xx");
            assertEquals(Optional.empty(), field(repeated, "head"));
            List<?> values = (List<?>) field(repeated, "values");
            assertEquals(2, values.size());
            assertEquals("2", field(values.get(0), "value"));
            assertEquals("3", field(values.get(1), "value"));
            assertEquals(List.of("x", "x"), field(repeated, "flags"));
            Object all = parse(loader, "1;:2;:!:x");
            assertEquals("1", field(((Optional<?>) field(all, "head")).orElseThrow(), "value"));
            assertEquals(Optional.of("!"), field(all, "tail"));
        }
    }

    @Test public void allListQuantifiersExcludeSameParserClassSeparators() throws Exception {
        for (String expression : List.of("Item+", "Item{1,2}", "Item{1,}", "Item % ','")) {
            try (var loader = compile("""
                @root @mapping(Container, params=[values]) Root ::= %s @values ;
                @mapping(Item, params=[value]) Item ::= Digits @value ';' ;
                Digits ::= NUMBER ;
                """.formatted(expression))) {
                String input = expression.contains("%") ? "1;,2;" : "1;2;";
                List<?> values = (List<?>) field(parse(loader, input), "values");
                assertEquals(expression, 2, values.size());
                assertEquals("1", field(values.get(0), "value"));
                assertEquals("2", field(values.get(1), "value"));
            }
        }
        try (var loader = compile("""
            @root @mapping(Container, params=[values]) Root ::= 'x' % 'x' @values ;
            """)) {
            assertEquals(List.of("x", "x", "x"), field(parse(loader, "xxxxx"), "values"));
        }
    }

    @Test public void repeatedCaptureSitesPreserveSourceOrderAndUncapturedLiteralsStayOut() throws Exception {
        try (var loader = compile("""
            @root @mapping(Container, params=[values, first, last])
            Root ::= 'x' 'x' @first { 'a' @values 'b' @values } 'x' @last 'x' ;
            """)) {
            Object ast = parse(loader, "xxababxx");
            assertEquals(List.of("a", "b", "a", "b"), field(ast, "values"));
            assertEquals("x", field(ast, "first"));
            assertEquals("x", field(ast, "last"));
        }
    }

    @Test public void nestedQuantifiersAndRecursiveRuleCapturesDoNotLeak() throws Exception {
        try (var loader = compile("""
            @root @mapping(Container, params=[values]) Root ::= [ { Item @values } ] ;
            @mapping(Item, params=[value]) Item ::= Digits @value ';' ;
            Digits ::= NUMBER ;
            """)) {
            assertEquals(List.of(), field(parse(loader, ""), "values"));
            assertEquals(2, ((List<?>) field(parse(loader, "1;2;"), "values")).size());
        }
        try (var loader = compile("""
            @root @mapping(Node, params=[value, children])
            Root ::= [ '!' ] @value '(' { Root } @children ')' ;
            """)) {
            Object ast = parse(loader, "(!())");
            assertEquals(Optional.empty(), field(ast, "value"));
            Object child = ((List<?>) field(ast, "children")).get(0);
            assertEquals(Optional.of("!"), field(child, "value"));
        }
    }

    @Test public void helperNamesDoNotReserveOrdinaryRuleNames() throws Exception {
        try (var loader = compile("""
            @root @mapping(Container, params=[value]) Root ::= Capture @value ;
            Capture ::= 'x' ;
            """)) {
            assertEquals("x", field(parse(loader, "x"), "value"));
        }
    }
}
