package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.ImportDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.RecoveryDiagnostic;

/** Exercises actual javac output and public Java parser/mapper entry points. */
public class JavaRecoveryGenerationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String source) throws Exception {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        var generated = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            generated.add(generator.generate(grammar));
        }
        var units = generated.stream().map(file -> new SimpleJavaFileObject(
            URI.create("string:///" + file.packageName().replace('.', '/') + "/" + file.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return file.source(); }
        }).toList();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager,
                diagnostics, List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(source + "\n" + diagnostics.getDiagnostics(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Parser root(URLClassLoader loader, String grammarName) throws Exception {
        return (Parser) loader.loadClass("org.example.recovery." + grammarName + "Parsers")
            .getMethod("getRootParser").invoke(null);
    }

    private Class<?> mapper(URLClassLoader loader, String grammarName) throws Exception {
        return loader.loadClass("org.example.recovery." + grammarName + "Mapper");
    }

    @Test public void rootRecoveryPreservesNormalAstAndRejectsRecoveredMapping() throws Exception {
        String source = """
            grammar RootRecovery {
              @package: org.example.recovery
              @root @recovery(sync=';') @mapping(Result, params=[value])
              Root ::= 'ok' @value;
            }
            """;
        try (var loader = compile(source)) {
            assertEquals("RootRecoveryParser", root(loader, "RootRecovery").getClass().getSimpleName());
            Class<?> mapper = mapper(loader, "RootRecovery");
            Object normal = mapper.getMethod("parse", String.class).invoke(null, "ok");
            assertNotNull(normal);
            Parser parser = root(loader, "RootRecovery");
            try (var context = new ParseContext(StringSource.createRootSource("ok"))) {
                assertTrue(parser.parse(context).isSucceeded());
                assertEquals(1, context.getCurrent().getTokens().size());
                var committed = context.getCurrent().getTokens().get(0);
                assertEquals("RootRecoveryParser", committed.parser.getClass().getSimpleName());
                assertNotNull(mapper.getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class)
                    .invoke(null, committed));
            }
            try (var context = new ParseContext(StringSource.createRootSource("😀;"))) {
                assertTrue(parser.parse(context).isSucceeded());
                assertEquals(2, context.getConsumedPosition().value());
                assertEquals(List.of(new RecoveryDiagnostic(0, 2, "syntax error: skipped to sync point")),
                    RecoveryDiagnostic.from(context));
                var token = context.getCurrent().getTokens().get(0);
                assertEquals(RecoveryDiagnostic.from(context), RecoveryDiagnostic.from(token));
                for (String method : List.of("mapParsedToken", "mapParsedTree", "mapSubtreeToken", "mapSubtreeTree")) {
                    var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> mapper.getMethod(method, org.unlaxer.Token.class).invoke(null, token));
                    assertEquals("cannot map recovered syntax", error.getCause().getMessage());
                }
            }
            var recovery = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, "😀;");
            assertEquals("recovery", recovery.orElseThrow().getClass().getMethod("kind")
                .invoke(recovery.orElseThrow()));
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> mapper.getMethod("parse", String.class).invoke(null, "😀;"));
            assertEquals("cannot map recovered syntax", error.getCause().getMessage());
        }
    }

    @Test public void autoAndSkipStopBeforeFollowingDelimiter() throws Exception {
        for (String mode : List.of("auto", "skip")) {
            String grammarName = mode.equals("auto") ? "AutoRecovery" : "SkipRecovery";
            String source = "grammar " + grammarName + " {\n"
                + "  @package: org.example.recovery\n"
                + "  @root @mapping(Result, params=[tail]) Root ::= Item ';' @tail;\n"
                + "  @recovery(" + mode + ") Item ::= 'ok';\n"
                + "}";
            try (var loader = compile(source);
                 var context = new ParseContext(StringSource.createRootSource("😀x;"))) {
                assertTrue(mode, root(loader, grammarName).parse(context).isSucceeded());
                assertEquals(3, context.getConsumedPosition().value());
                assertEquals(List.of(new RecoveryDiagnostic(0, 2, "syntax error: skipped to sync point")),
                    RecoveryDiagnostic.from(context));
            }
        }
    }

    @Test public void duplicateSyncTokensAreRejectedAtGeneration() {
        String source = """
            grammar DuplicateRecovery {
              @package: org.example.recovery
              @root @recovery(sync=';,;') Root ::= 'ok';
            }
            """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        assertThrows(IllegalArgumentException.class, () -> new ParserGenerator().generate(grammar));
    }

    @Test public void mapperOmitsRecoveryWalkOnlyForClosedGeneratedGrammar() {
        GrammarDecl closed = UBNFMapper.parse("""
            grammar Closed { @package: org.example.recovery
              @root @mapping(Result, params=[value]) Root ::= 'ok' @value;
            }
            """).grammars().get(0);
        assertFalse(new MapperGenerator().generate(closed).source().contains("RecoveryDiagnostic.from("));

        GrammarDecl annotated = UBNFMapper.parse("""
            grammar Annotated { @package: org.example.recovery
              @root @mapping(Result) @recovery(skip) Root ::= 'ok';
            }
            """).grammars().get(0);
        assertTrue(new MapperGenerator().generate(annotated).source().contains("RecoveryDiagnostic.from("));

        for (TokenDecl token : List.<TokenDecl>of(
                new TokenDecl.Simple("CUSTOM", "org.example.CustomParser"),
                new TokenDecl.Adapter("CUSTOM", "tinyexpression.string", "1"))) {
            GrammarDecl open = new GrammarDecl(closed.name(), closed.imports(), closed.settings(),
                List.of(token), closed.rules());
            assertTrue(new MapperGenerator().generate(open).source().contains("RecoveryDiagnostic.from("));
        }
        GrammarDecl imported = new GrammarDecl(closed.name(), List.of(new ImportDecl("lib", "lib.ubnf")),
            closed.settings(), closed.tokens(), closed.rules());
        assertTrue(new MapperGenerator().generate(imported).source().contains("RecoveryDiagnostic.from("));
    }

    @Test public void unannotatedExternalSimpleTokenStillEnforcesMappingBoundary() throws Exception {
        String source = """
            grammar ExternalRecovery {
              @package: org.example.recovery
              token EXT = org.unlaxer.dsl.codegen.ExternalRecoveryTokenParser
              @root @mapping(Result) Root ::= EXT;
            }
            """;
        try (var loader = compile(source)) {
            var mapper = mapper(loader, "ExternalRecovery");
            assertNotNull(mapper.getMethod("parse", String.class).invoke(null, "ok"));
            try (var context = new ParseContext(StringSource.createRootSource("bad;"))) {
                assertTrue(root(loader, "ExternalRecovery").parse(context).isSucceeded());
                assertEquals(List.of(new RecoveryDiagnostic(0, 4, "syntax error: skipped to sync point")),
                    RecoveryDiagnostic.from(context));
                var token = context.getCurrent().getTokens().get(0);
                var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> mapper.getMethod("mapParsedToken", org.unlaxer.Token.class).invoke(null, token));
                assertEquals("cannot map recovered syntax", error.getCause().getMessage());
            }
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> mapper.getMethod("parse", String.class).invoke(null, "bad;"));
            assertEquals("cannot map recovered syntax", error.getCause().getMessage());
        }
    }
}
