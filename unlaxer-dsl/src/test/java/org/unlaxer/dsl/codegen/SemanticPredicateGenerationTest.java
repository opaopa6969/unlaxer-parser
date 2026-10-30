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
