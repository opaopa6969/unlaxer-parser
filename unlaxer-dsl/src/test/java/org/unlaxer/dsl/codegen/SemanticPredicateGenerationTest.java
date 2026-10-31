package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SemanticPredicateBindings.Candidate;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.PredicateAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class SemanticPredicateGenerationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void frontendReservesSyntaxAndValidatesNames() {
        var grammar = UBNFMapper.parse("grammar G { @predicate('valid') R ::= 'x'; }").grammars().get(0);
        assertEquals(new PredicateAnnotation("valid"), grammar.rules().get(0).annotations().get(0));
        for (String annotations : List.of("@predicate('')", "@predicate('1bad')", "@predicate('a b')")) {
            var invalid = UBNFMapper.parse("grammar G { " + annotations + " R ::= 'x'; }").grammars().get(0);
            assertEquals("E-PREDICATE-NAME", GrammarValidator.validate(invalid).stream()
                .filter(issue -> issue.severity().equals("ERROR")).findFirst().orElseThrow().code());
        }
        var duplicate = UBNFMapper.parse("grammar G { @predicate('a') @predicate('b') R ::= 'x'; }").grammars().get(0);
        assertEquals("E-PREDICATE-DUPLICATE", GrammarValidator.validate(duplicate).stream()
            .filter(issue -> issue.severity().equals("ERROR")).findFirst().orElseThrow().code());
        assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parse("grammar G { @predicate R ::= 'x'; }"));
    }

    @Test public void generatedMapperInvokesRegistryAndPreservesFallbackAst() throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Checked {
              @package: org.example.checked
              @root @mapping(Result, params=[value])
              Start ::= Checked @value | Other @value;
              @predicate('small')
              Checked ::= '123';
              Other ::= '123';
            }
            """).grammars().get(0);
        try (var loader = compile(grammar)) {
            var mapper = loader.loadClass("org.example.checked.CheckedMapper");
            var parse = mapper.getMethod("parseWithPredicates", String.class, Map.class, ParseOptions.class);
            var diagnose = mapper.getMethod("diagnoseWithPredicates", String.class, Map.class, ParseOptions.class);
            for (var memo : Memoization.values()) for (var diagnostics : ParseOptions.Diagnostics.values()) {
                var options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
                for (boolean accepted : List.of(false, true)) {
                    Map<String, Predicate<Candidate>> bindings = Map.of("small", candidate -> {
                        assertEquals(new Candidate("123", 0, 3), candidate);
                        return accepted;
                    });
                    Object ast = parse.invoke(null, "123", bindings, options);
                    assertEquals("123", ast.getClass().getMethod("value").invoke(ast));
                    assertTrue(((Optional<?>) diagnose.invoke(null, "123", bindings, options)).isEmpty());
                }
                Object missing = ((Optional<?>) diagnose.invoke(null, "123", Map.of(), options)).orElseThrow();
                assertEquals("predicate_missing", missing.getClass().getMethod("kind").invoke(missing));
                Predicate<Candidate> failing = candidate -> { throw new IllegalStateException("application detail"); };
                Object failure = ((Optional<?>) diagnose.invoke(null, "123", Map.of("small", failing), options)).orElseThrow();
                assertEquals("predicate_error", failure.getClass().getMethod("kind").invoke(failure));
            }
        }
    }

    /** B1: a taken default wrapper name gets the first free suffix; the generated Java compiles. */
    @Test public void wrapperClassNamesAvoidRuleAndTokenClasses() throws Exception {
        String source = """
            grammar Named {
              @ubnf: v2
              @package: org.example.named
              token NUMBER_PREDICATE ::= 'z';
              @root @predicate('root') @mapping(Result, params=[value])
              Start ::= Number @value 'a' | Other @value;
              @predicate('number') @mapping(NumberValue, params=[text])
              Number ::= '1' @text;
              @predicate('other') @mapping(OtherValue, params=[text])
              Other ::= '2' @text;
              StartPredicate ::= '3';
            }
            """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        assertTrue(GrammarValidator.validateWithoutClassLoading(grammar).stream()
            .noneMatch(issue -> issue.code().startsWith("E-")));
        var expected = Map.of("Start", "StartPredicateParser_1", "Number", "NumberPredicateParser_1",
            "Other", "OtherPredicateParser");
        assertEquals(expected, SemanticPredicates.wrapperClassNames(grammar));
        // Pure function of the grammar: a fresh parse and a generation run do not change it.
        assertEquals(expected, SemanticPredicates.wrapperClassNames(UBNFMapper.parse(source).grammars().get(0)));
        String parsers = new ParserGenerator().generate(grammar).source();
        assertEquals(expected, SemanticPredicates.wrapperClassNames(grammar));
        for (String name : expected.values()) assertTrue(name, parsers.contains("public static class " + name + " extends"));
        assertTrue(parsers.contains("new org.unlaxer.parser.combinator.SemanticPredicateScope(Parser.get(StartPredicateParser_1.class)"));
        try (var loader = compile(grammar)) {
            var mapper = loader.loadClass("org.example.named.NamedMapper");
            Map<String, Predicate<Candidate>> bindings = Map.of("root", candidate -> true,
                "number", candidate -> false, "other", candidate -> true);
            Object ast = mapper.getMethod("parseWithPredicates", String.class, Map.class, ParseOptions.class)
                .invoke(null, "2", bindings, ParseOptions.DEFAULT);
            Object value = ast.getClass().getMethod("value").invoke(ast);
            assertEquals("OtherValue", value.getClass().getSimpleName());
            assertTrue(((Optional<?>) mapper.getMethod("diagnoseWithPredicates", String.class, Map.class, ParseOptions.class)
                .invoke(null, "1a", bindings, ParseOptions.DEFAULT)).isPresent());
        }
    }

    /** N7: parsing mapper entries always run the scope; only direct rule classes bypass it (Java only). */
    @Test public void plainMapperEntriesNeverSkipThePredicateScope() throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Plain {
              @package: org.example.plain
              @root @predicate('small') @mapping(Result, params=[value])
              Start ::= '123' @value;
            }
            """).grammars().get(0);
        try (var loader = compile(grammar)) {
            var mapper = loader.loadClass("org.example.plain.PlainMapper");
            var parse = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> mapper.getMethod("parse", String.class).invoke(null, "123"));
            assertTrue(parse.getCause().getMessage(), parse.getCause().getMessage().contains("predicate_missing"));
            Object diagnostic = ((Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, "123")).orElseThrow();
            assertEquals("predicate_missing", diagnostic.getClass().getMethod("kind").invoke(diagnostic));
            // Documented low-level difference (docs/semantic-predicates.md): the Java rule class and
            // getRootSyntaxParser() do not contain the wrapper, so they skip the predicate.
            var parsers = loader.loadClass("org.example.plain.PlainParsers");
            var syntax = (org.unlaxer.parser.Parser) parsers.getMethod("getRootSyntaxParser").invoke(null);
            try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource("123"))) {
                assertTrue(syntax.parse(context).isSucceeded());
            }
        }
    }

    private URLClassLoader compile(GrammarDecl grammar) throws Exception {
        var sources = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar));
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/" + source.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call());
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }
}
