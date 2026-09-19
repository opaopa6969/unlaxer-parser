package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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

/** #124: zero-width CST coordinates survive generated AST mapping. */
public class ZeroWidthSourceRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String token, String part, String prefix, String suffix) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar EmptyPosition {
              @package: org.example.emptyposition
              token T = %s
              @mapping(Item, params=[text]) Part ::= %s ;
              @root @mapping(Value, params=[value]) Root ::= '%s' Part @value %s ;
            }
            """.formatted(token, part, prefix, suffix)).grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/emptyposition/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call());
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private void assertMappedSpan(URLClassLoader loader, String input, int offset, Object expectedText) throws Exception {
        var mapper = loader.loadClass("org.example.emptyposition.EmptyPositionMapper");
        var parser = (org.unlaxer.parser.Parser) loader.loadClass("org.example.emptyposition.EmptyPositionParsers")
            .getMethod("getRootParser").invoke(null);
        Object mapped;
        // Keep the raw CST: pruning empty nodes is a separate mapper-entry-point concern.
        try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
            var parsed = parser.parse(context);
            assertTrue(parsed.isSucceeded());
            assertTrue(context.allConsumed());
            mapped = mapper.getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class)
                .invoke(null, parsed.getRootToken(false));
        }
        var ast = mapped.getClass().getMethod("ast").invoke(mapped);
        var item = ast.getClass().getMethod("value").invoke(ast);
        assertNotNull(item);
        assertEquals(expectedText, item.getClass().getMethod("text").invoke(item));
        var span = (Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, item);
        assertArrayEquals(new int[]{offset, offset}, (int[]) span.orElseThrow());
    }

    @Test public void emptyTokenMapsToItsGrammarPositionIncludingUnicodePrefix() throws Exception {
        try (var loader = compile("EMPTY", "T @text", "a", "'b'")) {
            assertMappedSpan(loader, "ab", 1, "");
        }
        try (var loader = compile("EMPTY", "T @text", "a😀", "'b'")) {
            assertMappedSpan(loader, "a😀b", 2, "");
        }
    }

    @Test public void eofAndMissingOptionalKeepTheirZeroWidthSourcePosition() throws Exception {
        try (var loader = compile("EOF", "T @text", "a😀", "")) {
            assertMappedSpan(loader, "a😀", 2, "");
        }
        try (var loader = compile("EMPTY", "['x'] @text", "a😀", "'b'")) {
            assertMappedSpan(loader, "a😀b", 2, Optional.empty());
        }
    }
}
