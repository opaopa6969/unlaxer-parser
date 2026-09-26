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

/** #132: compile and execute captures at their actual grammar sites. */
public class CompoundCaptureRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String identifier, String body) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Compound {
              @package: org.example.compound
              @whitespace: javaStyle
              token T = %s
              token N = NumberParser
            %s
            }
            """.formatted(identifier, body)).grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/compound/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
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

    private Object parse(URLClassLoader loader, String input) throws Exception {
        return loader.loadClass("org.example.compound.CompoundMapper").getMethod("parse", String.class).invoke(null, input);
    }

    private Object field(Object ast, String name) throws Exception {
        return ast.getClass().getMethod(name).invoke(ast);
    }

    @Test public void scalarGroupAndEitherChoiceBranchUseTheirWholeBoundSource() throws Exception {
        for (String identifier : List.of("IdentifierParser", "org.unlaxer.parser.clang.IdentifierParser")) {
            try (var loader = compile(identifier, """
                @root @mapping(Value, params=[group, choice, reversed])
                Root ::= T '|' (T ':' T) @group '|' (T | '!') @choice '|' ('!' | T) @reversed '|' T;
                """)) {
                Object ast = parse(loader, "outside|a:b|!|c|tail");
                assertEquals("a:b", field(ast, "group"));
                assertEquals("!", field(ast, "choice"));
                assertEquals("c", field(ast, "reversed"));
                Object other = parse(loader, "outside|a /*inside*/ : b|c|!|tail");
                assertEquals("a /*inside*/ : b", field(other, "group"));
                assertEquals("c", field(other, "choice"));
                assertEquals("!", field(other, "reversed"));
            }
        }
    }

    @Test public void optionalAndListCapturesDoNotSelectUncapturedSiblingsOrSeparators() throws Exception {
        for (String expression : List.of("{ (T | '!') } @values", "(T | '!')+ @values",
                "(T | '!'){1,2} @values", "(T | '!'){1,} @values", "(T | '!') % ',' @values")) {
            try (var loader = compile("IdentifierParser", """
                @root @mapping(Value, params=[head, values])
                Root ::= T '|' [ (T ':' T) ] @head '|' %s '|' T;
                """.formatted(expression))) {
                String values = expression.contains("%") ? "a,!" : "a !";
                Object absent = parse(loader, "outside||" + values + "|tail");
                assertEquals(Optional.empty(), field(absent, "head"));
                assertEquals(List.of("a", "!"), field(absent, "values"));
                Object present = parse(loader, "outside|x:y|" + values + "|tail");
                assertEquals(Optional.of("x:y"), field(present, "head"));
                assertEquals(List.of("a", "!"), field(present, "values"));
            }
        }
        try (var loader = compile("IdentifierParser", """
            @root @mapping(Value, params=[value]) Root ::= T '|' [ T ':' T ] @value '|' T;
            """)) {
            assertEquals(Optional.empty(), field(parse(loader, "outside||tail"), "value"));
            assertEquals(Optional.of("a:b"), field(parse(loader, "outside|a:b|tail"), "value"));
        }
        try (var loader = compile("IdentifierParser", """
            @root @mapping(Value, params=[values]) Root ::= T '|' { T ':' T ';' } @values '|' T;
            """)) {
            assertEquals(List.of(), field(parse(loader, "outside||tail"), "values"));
            assertEquals(List.of("a:b;", "c:d;"), field(parse(loader, "outside|a:b;c:d;|tail"), "values"));
        }
    }

    @Test public void directNumericTypesAndErrorsRemainDistinctFromCompoundText() throws Exception {
        try (var loader = compile("IdentifierParser", """
            @root @mapping(Value, params=[number, optional, numbers, text, choice, group])
            Root ::= N @number '|' [ N ] @optional '|' N % ',' @numbers '|'
                     (N ':' T) @text '|' (N | '!') @choice '|' (N) @group;
            """)) {
            Object ast = parse(loader, "-7||1,2|1.5:x|!|2e3");
            assertEquals(int.class, ast.getClass().getMethod("number").getReturnType());
            assertEquals(-7, field(ast, "number"));
            assertEquals(Optional.empty(), field(ast, "optional"));
            assertEquals(List.of(1, 2), field(ast, "numbers"));
            assertEquals("1.5:x", field(ast, "text"));
            assertEquals("!", field(ast, "choice"));
            assertEquals("2e3", field(ast, "group"));
            Object withOptional = parse(loader, "7|8|1|1:x|2.5|3");
            assertEquals(Optional.of(8), field(withOptional, "optional"));
            assertEquals("2.5", field(withOptional, "choice"));
            for (String invalid : List.of("1.5", "2e3", "2147483648", "-2147483649")) {
                try {
                    parse(loader, invalid + "||1|1:x|!|3");
                    fail("direct numeric capture must reject " + invalid);
                } catch (InvocationTargetException expected) {
                    assertTrue(expected.getCause().toString(), expected.getCause() instanceof NumberFormatException);
                }
            }
        }
    }

    @Test public void textRulesAndNestedGroupsDoNotLoseTheirBoundaries() throws Exception {
        try (var loader = compile("IdentifierParser", """
            @root @mapping(Value, params=[value, inner])
            Root ::= T '|' ((Lexeme ':' Lexeme)) @value '|' (Lexeme @inner ':' Lexeme) '|' T;
            Lexeme ::= T;
            """)) {
            Object ast = parse(loader, "outside|a:b|c:d|tail");
            assertEquals("a:b", field(ast, "value"));
            assertEquals("c", field(ast, "inner"));
        }
    }

    @Test public void mappedNodesInsideGroupsRetainTypedDispatch() throws Exception {
        try (var loader = compile("IdentifierParser", """
            @root @mapping(Value, params=[node, nodes]) Root ::= (Node) @node '|' { (Node) } @nodes;
            @mapping(Node, params=[text]) Node ::= T @text ';';
            """)) {
            Object ast = parse(loader, "a;|b;c;");
            Object node = field(ast, "node");
            assertEquals("Node", node.getClass().getSimpleName());
            assertEquals("a", field(node, "text"));
            List<?> nodes = (List<?>) field(ast, "nodes");
            assertEquals(2, nodes.size());
            assertEquals("b", field(nodes.get(0), "text"));
            assertEquals("c", field(nodes.get(1), "text"));
        }
    }
}
