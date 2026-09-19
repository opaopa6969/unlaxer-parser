package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** #165: selected tokens and immutable position snapshots belong to the same mapping. */
public class SelectedSourceSnapshotRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String RULES = """
        @root Root ::= Pair | Atom;
        @mapping(Pair, params=[left,right]) Pair ::= '😀' Atom @left '+' Atom @right;
        @mapping(Item, params=[text]) Atom ::= ('1' | '2') @text;
        """;

    private URLClassLoader compile(String rules) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Snapshot {
              @package: org.example.snapshot
            %s
            }
            """.formatted(rules)).grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/snapshot/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean succeeded = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), succeeded);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Class<?> mapper(URLClassLoader loader) throws Exception {
        return loader.loadClass("org.example.snapshot.SnapshotMapper");
    }

    private Parser parser(URLClassLoader loader) throws Exception {
        return (Parser) loader.loadClass("org.example.snapshot.SnapshotParsers")
            .getMethod("getRootParser").invoke(null);
    }

    private Token parseToken(URLClassLoader loader, String input) throws Exception {
        Parser parser = parser(loader);
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            assertTrue(parser.parse(context).isSucceeded());
            assertTrue(context.allConsumed());
            return context.getCurrent().getTokens().stream().filter(t -> t.parser == parser).findFirst().orElseThrow();
        }
    }

    private Object field(Object value, String name) throws Exception {
        return value.getClass().getMethod(name).invoke(value);
    }

    private Object select(Class<?> mapper, Token token, String preferred) throws Exception {
        return mapper.getMethod("selectParsedTokenWithSourceMap", Token.class, String.class)
            .invoke(null, token, preferred);
    }

    private Optional<?> span(Object snapshot, Object node) throws Exception {
        return (Optional<?>) snapshot.getClass().getMethod("sourceSpanOf", Object.class).invoke(snapshot, node);
    }

    private void assertSpan(Object snapshot, Object node, int start, int end) throws Exception {
        assertArrayEquals(new int[]{start, end}, (int[]) span(snapshot, node).orElseThrow());
    }

    @Test public void preferredAndDefaultReturnLegacySelectedTokenAndMatchingAstIdentity() throws Exception {
        try (var loader = compile(RULES)) {
            Class<?> mapper = mapper(loader);
            Token root = parseToken(loader, "😀1+1");
            for (String preferred : new String[]{null, "Pair", "Item"}) {
                Object old = mapper.getMethod("mapParsedToken", Token.class, String.class).invoke(null, root, preferred);
                Object selected = select(mapper, root, preferred);
                Object snapshot = field(selected, "sourceMap");
                Object ast = field(snapshot, "ast");
                assertSame(field(old, "token"), field(selected, "token"));
                assertEquals(field(old, "ast"), ast);
                assertNotSame(field(old, "ast"), ast);
                assertSame(ast, field(snapshot, "ast"));
                assertTrue(((Optional<?>) mapper.getMethod("sourceSpanOf", Object.class).invoke(null, ast)).isPresent());
                assertFalse(span(snapshot, field(old, "ast")).isPresent());
                // Existing preferred traversal selects the rightmost Item; the new API must not reselect.
                assertSpan(snapshot, ast, "Item".equals(preferred) ? 3 : 0, 4);
            }
            Object selected = mapper.getMethod("selectParsedTokenWithSourceMap", Token.class).invoke(null, root);
            Object old = mapper.getMethod("mapParsedToken", Token.class).invoke(null, root);
            assertSame(field(old, "token"), field(selected, "token"));
            assertEquals(field(old, "ast"), field(field(selected, "sourceMap"), "ast"));
            // The old public record constructor and both old snapshot entry points remain available.
            var astType = loader.loadClass("org.example.snapshot.SnapshotAST");
            Object rebuilt = old.getClass().getConstructor(Token.class, astType)
                .newInstance(field(old, "token"), field(old, "ast"));
            assertEquals(old, rebuilt);
            Object legacySnapshot = mapper.getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, root);
            assertEquals(field(old, "ast"), field(legacySnapshot, "ast"));
            Object parsed = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, "😀1+1");
            assertEquals(field(old, "ast"), field(parsed, "ast"));
        }
    }

    @Test public void snapshotSurvivesLaterMappingsAndDefendsUnicodeIdentityBasedSpans() throws Exception {
        try (var loader = compile(RULES)) {
            Class<?> mapper = mapper(loader);
            Object selected = select(mapper, parseToken(loader, "😀1+1"), null);
            Object snapshot = field(selected, "sourceMap");
            Object ast = field(snapshot, "ast");
            Object left = field(ast, "left");
            Object right = field(ast, "right");
            assertEquals(left, right);
            assertNotSame(left, right);
            assertSpan(snapshot, left, 1, 2);
            assertSpan(snapshot, right, 3, 4);
            int[] exposed = (int[]) span(snapshot, right).orElseThrow();
            exposed[0] = 99;
            assertSpan(snapshot, right, 3, 4);
            assertFalse(span(snapshot, new Object()).isPresent());
            assertFalse(span(snapshot, null).isPresent());
            Object other = select(mapper, parseToken(loader, "2"), "Item");
            Object otherMap = field(other, "sourceMap");
            assertSpan(otherMap, field(otherMap, "ast"), 0, 1);
            assertFalse(span(otherMap, left).isPresent());
            mapper.getMethod("parse", String.class).invoke(null, "😀2+2");
            assertSpan(snapshot, ast, 0, 4);
            assertSpan(snapshot, left, 1, 2);
            assertSpan(snapshot, right, 3, 4);
        }
    }

    @Test public void parallelMappingsRetainTheirOwnSnapshotAfterAllThreadsOverwriteLegacyMap() throws Exception {
        try (var loader = compile(RULES)) {
            Class<?> mapper = mapper(loader);
            Token pair = parseToken(loader, "😀1+1");
            Token atom = parseToken(loader, "2");
            var barrier = new CyclicBarrier(4);
            var jobs = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                final boolean usePair = worker % 2 == 0;
                jobs.add(() -> {
                    for (int iteration = 0; iteration < 30; iteration++) {
                        barrier.await(20, TimeUnit.SECONDS);
                        Object selection = select(mapper, usePair ? pair : atom, usePair ? "Pair" : "Item");
                        Object snapshot = field(selection, "sourceMap");
                        Object ast = field(snapshot, "ast");
                        barrier.await(20, TimeUnit.SECONDS);
                        assertSpan(snapshot, ast, 0, usePair ? 4 : 1);
                        if (usePair) {
                            assertSpan(snapshot, field(ast, "left"), 1, 2);
                            assertSpan(snapshot, field(ast, "right"), 3, 4);
                        } else {
                            assertEquals("2", field(ast, "text"));
                        }
                    }
                    return null;
                });
            }
            var executor = Executors.newFixedThreadPool(4);
            try {
                for (var future : executor.invokeAll(jobs, 60, TimeUnit.SECONDS)) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test public void invalidInputsPreserveLegacyFailureAndDoNotDamageEarlierSnapshot() throws Exception {
        try (var loader = compile("""
            @root @mapping(Node) Root ::= A | B;
            @mapping(First) A ::= 'a';
            @mapping(Second) B ::= 'b';
            """)) {
            Class<?> mapper = mapper(loader);
            Token committed = parseToken(loader, "a");
            Object snapshot = field(select(mapper, committed, "First"), "sourceMap");
            Parser parser = parser(loader);
            try (var context = new ParseContext(StringSource.createRootSource("a"))) {
                var parsed = parser.parse(context);
                for (Token bad : new Token[]{null, parsed.getRootToken(false), parsed.getRootToken(true)}) {
                    for (boolean preferred : List.of(false, true)) {
                        var old = assertThrows(InvocationTargetException.class, () -> {
                            if (preferred) mapper.getMethod("mapParsedToken", Token.class, String.class).invoke(null, bad, "First");
                            else mapper.getMethod("mapParsedToken", Token.class).invoke(null, bad);
                        });
                        var current = assertThrows(InvocationTargetException.class, () -> {
                            if (preferred) select(mapper, bad, "First");
                            else mapper.getMethod("selectParsedTokenWithSourceMap", Token.class).invoke(null, bad);
                        });
                        assertEquals(old.getCause().getClass(), current.getCause().getClass());
                        assertEquals(old.getCause().getMessage(), current.getCause().getMessage());
                    }
                }
            }
            assertSpan(snapshot, field(snapshot, "ast"), 0, 1);
        }
    }
}
