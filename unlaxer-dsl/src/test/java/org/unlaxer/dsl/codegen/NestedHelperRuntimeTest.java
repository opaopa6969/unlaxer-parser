package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

/** #123: helper references must identify grammar sites, including after nested helpers. */
public class NestedHelperRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String declarations, String captures, String body) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Nested {
              @package: org.example.nested
              token END = EOF
              %s
              @root @mapping(Value, params=[%s]) Root ::= %s END ;
            }
            """.formatted(declarations, captures, body)).grammars().get(0);
        var parserSource = new ParserGenerator().generate(grammar);
        assertEquals("generation must be deterministic", parserSource.source(),
            new ParserGenerator().generate(grammar).source());
        var units = List.of(parserSource, new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/nested/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(body + "\n" + diagnostics.getDiagnostics(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object accepted(URLClassLoader loader, String input) throws Exception {
        var parser = (Parser) loader.loadClass("org.example.nested.NestedParsers")
            .getMethod("getRootParser").invoke(null);
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            assertTrue("must parse " + input, parser.parse(context).isSucceeded());
            assertTrue("must consume " + input, context.allConsumed());
            assertEquals(input.codePointCount(0, input.length()), context.getPosition(TokenKind.consumed).value());
            assertEquals(input.codePointCount(0, input.length()), context.getPosition(TokenKind.matchOnly).value());
        }
        return loader.loadClass("org.example.nested.NestedMapper").getMethod("parse", String.class).invoke(null, input);
    }

    private void rejected(URLClassLoader loader, String input) throws Exception {
        var parser = (Parser) loader.loadClass("org.example.nested.NestedParsers")
            .getMethod("getRootParser").invoke(null);
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            assertTrue("must reject " + input, parser.parse(context).isFailed());
            assertEquals("failed root rolls back consumption", 0, context.getPosition(TokenKind.consumed).value());
        }
    }

    private Object field(Object ast, String name) throws Exception {
        return ast.getClass().getMethod(name).invoke(ast);
    }

    @Test public void nestedHelpersDoNotShiftFollowingGroups() throws Exception {
        record Example(String prefix, List<String> accepted, List<String> rejected) {}
        for (var example : List.of(
                new Example("(('a'))", List.of("ab"), List.of("aa", "b")),
                new Example("[ ('a') ]", List.of("b", "ab"), List.of("aa", "aab")),
                new Example("{ ('a') }", List.of("b", "ab", "aab"), List.of("a", "aac")),
                new Example("('a')+", List.of("ab", "aab"), List.of("b", "aa")),
                new Example("('a'){1,2}", List.of("ab", "aab"), List.of("b", "aaab")),
                new Example("('a'){1,}", List.of("ab", "aaab"), List.of("b", "aaa")),
                new Example("('a') % (',')", List.of("ab", "a,ab"), List.of("b", "a,b")),
                new Example("((('a')))", List.of("ab"), List.of("aa", "b")))) {
            try (var loader = compile("", "value", example.prefix() + " ('b') @value")) {
                for (String input : example.accepted()) assertEquals("b", field(accepted(loader, input), "value"));
                for (String input : example.rejected()) rejected(loader, input);
            }
        }
    }

    @Test public void eachCounterFamilyKeepsItsNestedAndSiblingLocations() throws Exception {
        record Example(String prefix, List<String> accepted, String rejected) {}
        for (var example : List.of(
                new Example("[ [ 'a' 'c' ] ] [ 'x' 'y' ]", List.of("b", "acxyb", "xyb"), "acacb"),
                new Example("{ { 'a' 'c' } 'd' } { 'x' 'y' }", List.of("b", "acddxyxyb"), "acdxb"),
                new Example("(('a')+) ('x')+", List.of("axb", "aaaxxb"), "aaaab"),
                new Example("(('a'){1,2}) ('x'){1,2}", List.of("axb", "aaxxb"), "aaab"),
                new Example("('a' % ',') % ';' 'x' % ':'", List.of("axb", "a,a;ax:xb"), "a:axb"),
                new Example("(('a') | ('c'))", List.of("ab", "cb"), "aa"))) {
            try (var loader = compile("", "value", example.prefix() + " ('b') @value")) {
                for (String input : example.accepted()) assertEquals("b", field(accepted(loader, input), "value"));
                rejected(loader, example.rejected());
            }
        }
    }

    @Test public void structurallyEqualGroupsRemainDistinctCaptureSites() throws Exception {
        try (var loader = compile("", "first,last", "(('a')) @first ('a') @last")) {
            Object ast = accepted(loader, "aa");
            assertEquals("a", field(ast, "first"));
            assertEquals("a", field(ast, "last"));
            rejected(loader, "ab");
        }
    }

    @Test public void optionalNestedGroupPreservesLookaheadSiblingAndRollback() throws Exception {
        try (var loader = compile("token T = LOOKAHEAD('a') token U = LOOKAHEAD('b')", "value",
                "(T) [ ('x') ] (U) 'ab' @value")) {
            assertEquals("ab", field(accepted(loader, "ab"), "value"));
            rejected(loader, "aa");
        }
    }
}
