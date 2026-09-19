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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
        @mapping(Number, params=[value]) Alternate ::= (NUMBER) @value;
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
        for (String method : List.of("mapParsedToken", "mapParsedTokenWithSourceMap",
                "selectParsedTokenWithSourceMap")) {
            var error = assertThrows(method, InvocationTargetException.class,
                () -> mapper.getMethod(method, Token.class).invoke(null, stripped));
            assertTrue(error.getCause().toString(), error.getCause() instanceof IllegalArgumentException);
            assertTrue(error.getCause().getMessage(), error.getCause().getMessage().contains("Mapped root token is missing for " + rootName));
        }
        var error = assertThrows(InvocationTargetException.class,
            () -> mapper.getMethod("mapParsedToken", Token.class, String.class).invoke(null, stripped, "Power"));
        assertTrue(error.getCause().getMessage(), error.getCause().getMessage().contains("Mapped root token is missing for " + rootName));
        error = assertThrows(InvocationTargetException.class,
            () -> mapper.getMethod("selectParsedTokenWithSourceMap", Token.class, String.class)
                .invoke(null, stripped, "Power"));
        assertTrue(error.getCause().getMessage(), error.getCause().getMessage().contains("Mapped root token is missing for " + rootName));
    }

    private Object selectSubtree(Class<?> mapper, Token token, String preferred) throws Exception {
        return mapper.getMethod("selectSubtreeTokenWithSourceMap", Token.class, String.class)
            .invoke(null, token, preferred);
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

    @Test public void alternateEntryTokensMapExplicitlyWithoutWeakeningRootValidation() throws Exception {
        try (var loader = compile(RIGHT)) {
            Class<?> mapper = mapper(loader);
            @SuppressWarnings("unchecked")
            Class<? extends Parser> alternateClass = (Class<? extends Parser>)
                loader.loadClass("org.example.roottoken.RootTokenParsers$AlternateParser");
            Parser atomParser = Parser.get(alternateClass);
            Token atom;
            try (var context = new ParseContext(StringSource.createRootSource(" /*😀*/42 "))) {
                var parsed = atomParser.parse(context);
                assertTrue(parsed.isSucceeded());
                assertTrue(context.allConsumed());
                atom = committed(context, atomParser);
            }
            assertRejected(mapper, atom, "Expr");
            Object selected = selectSubtree(mapper, atom, "Number");
            assertSame(atom, field(selected, "token"));
            Object snapshot = field(selected, "sourceMap");
            Object ast = field(snapshot, "ast");
            assertEquals("Number", ast.getClass().getSimpleName());
            assertEquals("42", field(ast, "value"));
            assertSpan(snapshot, ast, 0, " /*😀*/42 ".codePointCount(0, " /*😀*/42 ".length()));
            parse(loader, "9^8");
            assertSpan(snapshot, ast, 0, " /*😀*/42 ".codePointCount(0, " /*😀*/42 ".length()));

            Object mapped = mapper.getMethod("mapSubtreeToken", Token.class).invoke(null, atom);
            assertSame(atom, field(mapped, "token"));
            assertEquals(ast, field(mapped, "ast"));
            Object directSnapshot = mapper.getMethod("mapSubtreeTokenWithSourceMap", Token.class).invoke(null, atom);
            assertEquals(ast, field(directSnapshot, "ast"));
            assertSpan(directSnapshot, field(directSnapshot, "ast"), 0,
                " /*😀*/42 ".codePointCount(0, " /*😀*/42 ".length()));

            var nullError = assertThrows(InvocationTargetException.class,
                () -> mapper.getMethod("mapSubtreeToken", Token.class).invoke(null, new Object[]{null}));
            assertEquals("subtreeToken must not be null", nullError.getCause().getMessage());

            @SuppressWarnings("unchecked")
            Class<? extends Parser> unmappedClass = (Class<? extends Parser>)
                loader.loadClass("org.example.roottoken.RootTokenParsers$AtomParser");
            Parser unmappedParser = Parser.get(unmappedClass);
            Token unmapped;
            try (var context = new ParseContext(StringSource.createRootSource("5"))) {
                assertTrue(unmappedParser.parse(context).isSucceeded());
                unmapped = committed(context, unmappedParser);
            }
            var unmappedError = assertThrows(InvocationTargetException.class,
                () -> mapper.getMethod("mapSubtreeToken", Token.class).invoke(null, unmapped));
            assertEquals("No mapped node found in token tree", unmappedError.getCause().getMessage());
        }
    }

    @Test public void alternateEntrySnapshotsStayIndependentAcrossConcurrentMappings() throws Exception {
        try (var loader = compile(RIGHT)) {
            Class<?> mapper = mapper(loader);
            @SuppressWarnings("unchecked")
            Class<? extends Parser> alternateClass = (Class<? extends Parser>)
                loader.loadClass("org.example.roottoken.RootTokenParsers$AlternateParser");
            Parser alternate = Parser.get(alternateClass);
            List<String> inputs = List.of("1", "22", "333", "4444");
            List<Token> tokens = new java.util.ArrayList<>();
            for (String input : inputs) {
                try (var context = new ParseContext(StringSource.createRootSource(input))) {
                    assertTrue(alternate.parse(context).isSucceeded());
                    assertTrue(context.allConsumed());
                    tokens.add(committed(context, alternate));
                }
            }
            var barrier = new CyclicBarrier(inputs.size());
            List<Callable<Void>> jobs = IntStream.range(0, inputs.size()).mapToObj(index -> (Callable<Void>) () -> {
                for (int iteration = 0; iteration < 20; iteration++) {
                    Object selected = selectSubtree(mapper, tokens.get(index), "Number");
                    Object snapshot = field(selected, "sourceMap");
                    Object ast = field(snapshot, "ast");
                    barrier.await(20, TimeUnit.SECONDS);
                    assertEquals(inputs.get(index), field(ast, "value"));
                    assertSpan(snapshot, ast, 0, inputs.get(index).length());
                }
                return null;
            }).toList();
            try (var executor = Executors.newFixedThreadPool(inputs.size())) {
                for (var future : executor.invokeAll(jobs)) future.get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test public void subtreeApiRejectsTokensFromAnotherGeneratedGrammar() throws Exception {
        try (var expectedLoader = compile(RIGHT); var foreignLoader = compile(RIGHT)) {
            @SuppressWarnings("unchecked")
            Class<? extends Parser> foreignClass = (Class<? extends Parser>)
                foreignLoader.loadClass("org.example.roottoken.RootTokenParsers$AlternateParser");
            Parser foreignParser = Parser.get(foreignClass);
            Token foreign;
            try (var context = new ParseContext(StringSource.createRootSource("7"))) {
                assertTrue(foreignParser.parse(context).isSucceeded());
                foreign = committed(context, foreignParser);
            }
            var error = assertThrows(InvocationTargetException.class,
                () -> mapper(expectedLoader).getMethod("mapSubtreeToken", Token.class)
                    .invoke(null, foreign));
            assertEquals("subtreeToken must be produced by a rule parser from this generated grammar",
                error.getCause().getMessage());
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
