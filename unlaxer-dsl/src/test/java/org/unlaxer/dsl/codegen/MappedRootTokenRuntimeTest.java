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
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

/** #146: a missing mapped choice root must not silently turn into its nested operand. */
public class MappedRootTokenRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String RIGHT = """
        @root @mapping(Power, params=[left,op,right]) @rightAssoc @precedence(level=10)
        Expr ::= Atom @left { '^' @op Expr @right };
        Atom ::= NUMBER;
        """;

    private URLClassLoader compile(String rules) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar RootToken {
              @package: org.example.roottoken
              @whitespace: javaStyle
              token NUMBER = NumberParser
              token EMPTY_TOKEN = EMPTY
            %s
            }
            """.formatted(rules)).grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/roottoken/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(rules + "\n" + diagnostics.getDiagnostics(), compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call());
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Class<?> mapper(URLClassLoader loader) throws Exception {
        return loader.loadClass("org.example.roottoken.RootTokenMapper");
    }

    private Parser parser(URLClassLoader loader) throws Exception {
        return (Parser) loader.loadClass("org.example.roottoken.RootTokenParsers").getMethod("getRootParser").invoke(null);
    }

    private Object field(Object value, String name) throws Exception {
        return value.getClass().getMethod(name).invoke(value);
    }

    private Object parse(URLClassLoader loader, String source) throws Exception {
        return mapper(loader).getMethod("parse", String.class).invoke(null, source);
    }

    private Token committed(ParseContext context, Parser parser) {
        return context.getCurrent().getTokens().stream().filter(t -> t.parser == parser).findFirst().orElseThrow();
    }

    private void assertRejected(Class<?> mapper, Token stripped, String rootName) throws Exception {
        for (String method : List.of("mapParsedToken", "mapParsedTokenWithSourceMap")) {
            var error = assertThrows(method, InvocationTargetException.class,
                () -> mapper.getMethod(method, Token.class).invoke(null, stripped));
            assertTrue(error.getCause().toString(), error.getCause() instanceof IllegalArgumentException);
            assertTrue(error.getCause().getMessage(), error.getCause().getMessage().contains("Mapped root token is missing for " + rootName));
        }
        var error = assertThrows(InvocationTargetException.class,
            () -> mapper.getMethod("mapParsedToken", Token.class, String.class).invoke(null, stripped, "Power"));
        assertTrue(error.getCause().getMessage(), error.getCause().getMessage().contains("Mapped root token is missing for " + rootName));
    }

    @Test public void rawAndReducedChoiceResultsCannotSelectNestedRecursiveRoot() throws Exception {
        try (var loader = compile(RIGHT)) {
            for (String input : List.of("2", "2^3^2", " /*😀*/2 ^ 3 ^ 2 ")) {
                for (boolean reduced : List.of(false, true)) {
                    Parser parser = parser(loader);
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        var parsed = parser.parse(context);
                        assertTrue(parsed.isSucceeded());
                        assertTrue(context.allConsumed());
                        Token stripped = parsed.getRootToken(reduced);
                        assertNotEquals(parser.getClass(), stripped.parser.getClass());
                        assertRejected(mapper(loader), stripped, "Expr");
                    }
                }
            }
        }
    }

    @Test public void committedChoiceRootMatchesStringEntryPointsAndKeepsSpans() throws Exception {
        try (var loader = compile(RIGHT)) {
            for (String input : List.of("2", "2^3^2", " /*😀*/2 ^ 3 ^ 2 ")) {
                Object expected = parse(loader, input);
                Parser parser = parser(loader);
                Token root;
                try (var context = new ParseContext(StringSource.createRootSource(input))) {
                    assertTrue(parser.parse(context).isSucceeded());
                    root = committed(context, parser);
                }
                Object mapped = mapper(loader).getMethod("mapParsedToken", Token.class).invoke(null, root);
                assertSame(root, field(mapped, "token"));
                assertEquals(expected, field(mapped, "ast"));
                Object sourceMapped = mapper(loader).getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, root);
                assertEquals(expected, field(sourceMapped, "ast"));
                assertSpan(sourceMapped, field(sourceMapped, "ast"), 0, input.codePointCount(0, input.length()));
                if (input.equals("2^3^2")) {
                    Object ast = field(sourceMapped, "ast");
                    assertEquals("2", field(ast, "left"));
                    Object right = ((List<?>) field(ast, "right")).get(0);
                    assertEquals("3", field(right, "left"));
                    assertSpan(sourceMapped, right, 2, 5);
                    parse(loader, "9");
                    assertSpan(sourceMapped, right, 2, 5);
                }
            }
        }
    }

    @Test public void zeroFieldSumChoicesRejectStrippedRootsButMapCommittedAliases() throws Exception {
        try (var loader = compile("""
            @root @mapping(Node) Root ::= A | B;
            @mapping(First) A ::= 'a';
            @mapping(Second) B ::= 'b';
            """)) {
            for (String input : List.of("a", "b")) {
                for (boolean reduced : List.of(false, true)) {
                    Parser parser = parser(loader);
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        var parsed = parser.parse(context);
                        assertRejected(mapper(loader), parsed.getRootToken(reduced), "Root");
                    }
                }
                Parser parser = parser(loader);
                try (var context = new ParseContext(StringSource.createRootSource(input))) {
                    parser.parse(context);
                    Object mapped = mapper(loader).getMethod("mapParsedTokenWithSourceMap", Token.class)
                        .invoke(null, committed(context, parser));
                    assertEquals(parse(loader, input), field(mapped, "ast"));
                    assertSpan(mapped, field(mapped, "ast"), 0, 1);
                }
            }
        }
        try (var loader = compile("""
            @root @mapping(Alias) Root ::= Leaf;
            @mapping(Marker) Leaf ::= EMPTY_TOKEN;
            """)) {
            Parser parser = parser(loader);
            try (var context = new ParseContext(StringSource.createRootSource(""))) {
                parser.parse(context);
                Object mapped = mapper(loader).getMethod("mapParsedTokenWithSourceMap", Token.class)
                    .invoke(null, committed(context, parser));
                assertEquals("Marker", field(mapped, "ast").getClass().getSimpleName());
                assertSpan(mapped, field(mapped, "ast"), 0, 0);
            }
        }
    }

    @Test public void nonChoiceMappedRootsDoNotPruneZeroWidthCaptures() throws Exception {
        try (var loader = compile("""
            @root @mapping(Value, params=[part]) Root ::= '😀' Part @part 'x';
            @mapping(Item, params=[text]) Part ::= EMPTY_TOKEN @text;
            """)) {
            Parser parser = parser(loader);
            try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
                var parsed = parser.parse(context);
                Token root = parsed.getRootToken(false);
                assertEquals(parser.getClass(), root.parser.getClass());
                Object mapped = mapper(loader).getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, root);
                Object item = field(field(mapped, "ast"), "part");
                assertEquals("", field(item, "text"));
                assertSpan(mapped, item, 1, 1);
                assertEquals(parse(loader, "😀x"), field(mapped, "ast"));
            }
        }
    }

    @Test public void transparentRootsRetainDescendantAndPreferredTypeSelection() throws Exception {
        try (var loader = compile("""
            @root Root ::= Pair | Atom;
            @mapping(Pair, params=[left,right]) Pair ::= Atom @left '+' Atom @right;
            @mapping(Number, params=[value]) Atom ::= (NUMBER) @value;
            """)) {
            for (boolean reduced : List.of(false, true)) {
                Parser parser = parser(loader);
                try (var context = new ParseContext(StringSource.createRootSource("1+2"))) {
                    var parsed = parser.parse(context);
                    Token root = parsed.getRootToken(reduced);
                    Object mapped = mapper(loader).getMethod("mapParsedToken", Token.class).invoke(null, root);
                    assertEquals("Pair", field(mapped, "ast").getClass().getSimpleName());
                    Object preferred = mapper(loader).getMethod("mapParsedToken", Token.class, String.class)
                        .invoke(null, root, "Number");
                    assertEquals("Number", field(preferred, "ast").getClass().getSimpleName());
                }
            }
        }
    }

    private void assertSpan(Object mapped, Object node, int start, int end) throws Exception {
        var span = (Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, node);
        assertArrayEquals(new int[]{start, end}, (int[]) span.orElseThrow());
    }
}
