package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
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
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.ParseOptions.Diagnostics;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

public class JavaDetailedOnFailureRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** Observational probe only: acceptance does not depend on diagnostics or invocation count. */
    public static class OpenParser extends WordParser {
        static final List<ParseContext> contexts = new ArrayList<>();
        public OpenParser() { super("("); }
        @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
            contexts.add(context);
            return super.getToken(context, kind, invert);
        }
    }

    private URLClassLoader compile() throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Deferred {
              @package: org.example.deferred
              @memoSafeToken: OPEN
              token OPEN = org.unlaxer.dsl.codegen.JavaDetailedOnFailureRuntimeTest.OpenParser
              @root @scopeTree(mode=lexical) @mapping(Value, params=[name])
              Root ::= OPEN Decl @name ')' ;
              @declares(symbol=name) Decl ::= '😀' @name ;
            }
            """).grammars().get(0);
        var sources = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar));
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/deferred/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test public void generatedDiagnoseRetriesSyntaxAndTrailingFailuresWithIdenticalDiagnostics() throws Exception {
        try (var loader = compile()) {
            var mapper = loader.loadClass("org.example.deferred.DeferredMapper");
            var diagnose = mapper.getMethod("diagnose", String.class, ParseOptions.class);
            for (Memoization memo : Memoization.values()) {
                for (String input : List.of("(😀)", "(😀", "(😀)@", "(😀]")) {
                    var detailedOptions = ParseOptions.withMemoization(memo);
                    var detailed = (Optional<?>) diagnose.invoke(null, input, detailedOptions);
                    OpenParser.contexts.clear();
                    var deferred = (Optional<?>) diagnose.invoke(null, input,
                        detailedOptions.withDiagnostics(Diagnostics.DETAILED_ON_FAILURE));
                    assertEquals(input, detailed, deferred);
                    assertPasses(detailed.isEmpty() ? 1 : 2, memo);
                    if (input.equals("(😀)@")) {
                        var diagnostic = deferred.orElseThrow();
                        assertEquals("trailing_input", diagnostic.getClass().getMethod("kind").invoke(diagnostic));
                        assertEquals(3, diagnostic.getClass().getMethod("offset").invoke(diagnostic));
                        assertEquals(List.of("end of input"), diagnostic.getClass().getMethod("expected").invoke(diagnostic));
                    }
                }
            }
        } finally { OpenParser.contexts.clear(); }
    }

    @Test public void generatedParseRetriesOnlyParseFailuresAndKeepsAstAndExceptionMessages() throws Exception {
        try (var loader = compile()) {
            var mapper = loader.loadClass("org.example.deferred.DeferredMapper");
            var parse = mapper.getMethod("parse", String.class, String.class, ParseOptions.class);
            var parseWithOptions = mapper.getMethod("parseWithOptions", String.class, ParseOptions.class);
            for (Memoization memo : Memoization.values()) {
                var detailedOptions = ParseOptions.withMemoization(memo);
                var deferredOptions = detailedOptions.withDiagnostics(Diagnostics.DETAILED_ON_FAILURE);
                var expectedAst = parse.invoke(null, "(😀)", "Value", detailedOptions);
                OpenParser.contexts.clear();
                assertEquals(expectedAst, parseWithOptions.invoke(null, "(😀)", deferredOptions));
                assertPasses(1, memo);
                for (String input : List.of("(😀", "(😀)@", "(😀]")) {
                    var detailed = assertThrows(InvocationTargetException.class,
                        () -> parse.invoke(null, input, "Value", detailedOptions)).getCause();
                    OpenParser.contexts.clear();
                    var deferred = assertThrows(InvocationTargetException.class,
                        () -> parse.invoke(null, input, "Value", deferredOptions)).getCause();
                    assertEquals(IllegalArgumentException.class, deferred.getClass());
                    assertEquals(detailed.getMessage(), deferred.getMessage());
                    assertTrue(deferred.getMessage().contains("ParseDiagnostic["));
                    assertPasses(2, memo);
                }
            }
        } finally { OpenParser.contexts.clear(); }
    }

    @Test public void directGeneratedParserKeepsCapturesAndScopesWithoutRetry() throws Exception {
        try (var loader = compile()) {
            var parsers = loader.loadClass("org.example.deferred.DeferredParsers");
            var mapper = loader.loadClass("org.example.deferred.DeferredMapper");
            Parser root = (Parser) parsers.getMethod("getRootParser").invoke(null);
            for (Memoization memo : Memoization.values()) {
                for (String input : List.of("(😀)", "(😀", "(😀)@", "(😀]")) {
                    List<List<Object>> observations = new ArrayList<>();
                    for (Diagnostics policy : Diagnostics.values()) {
                        OpenParser.contexts.clear();
                        try (var context = ParseContext.withOptions(StringSource.createRootSource(input),
                                ParseOptions.withMemoization(memo).withDiagnostics(policy))) {
                            var parsed = root.parse(context);
                            assertEquals("low-level API never retries", 1, OpenParser.contexts.size());
                            List<Object> observation = new ArrayList<>();
                            observation.add(parsed.isSucceeded());
                            observation.add(ScopeStore.getAllDeclarations(context));
                            observation.add(ScopeStore.getAllReferences(context));
                            observation.add(ScopeStore.getDiagnostics(context));
                            observation.add(ScopeStore.currentScopeDepth(context));
                            if (parsed.isSucceeded()) {
                                Token committed = context.getCurrent().getTokens().stream()
                                    .filter(t -> t.parser == root).findFirst().orElseThrow();
                                var mapped = mapper.getMethod("mapParsedToken", Token.class).invoke(null, committed);
                                observation.add(mapped.getClass().getMethod("ast").invoke(mapped));
                                assertEquals(List.of(new ScopeStore.SymbolInfo("😀", 1)),
                                    ScopeStore.getAllDeclarations(context));
                            } else {
                                assertTrue(ScopeStore.getAllDeclarations(context).isEmpty());
                            }
                            if (policy == Diagnostics.DETAILED_ON_FAILURE) {
                                assertEquals(0, context.getParseFailureDiagnostics().getFarthestOffset());
                                assertTrue(context.getParseFailureDiagnostics().getExpectedTokens().isEmpty());
                            }
                            observations.add(observation);
                        }
                    }
                    assertEquals(input, observations.get(0), observations.get(1));
                }
            }
        } finally { OpenParser.contexts.clear(); }
    }

    private static void assertPasses(int count, Memoization memo) {
        assertEquals(count, OpenParser.contexts.size());
        assertEquals(Diagnostics.DETAILED_ON_FAILURE, OpenParser.contexts.get(0).getOptions().diagnostics());
        for (ParseContext context : OpenParser.contexts) assertEquals(memo, context.getOptions().memoization());
        if (count == 2) {
            assertNotSame(OpenParser.contexts.get(0), OpenParser.contexts.get(1));
            assertEquals(Diagnostics.DETAILED, OpenParser.contexts.get(1).getOptions().diagnostics());
            if (memo == Memoization.SAFE_FAILURES) {
                assertNotSame(OpenParser.contexts.get(0).getPackratMemoTable(),
                    OpenParser.contexts.get(1).getPackratMemoTable());
            }
        }
    }
}
