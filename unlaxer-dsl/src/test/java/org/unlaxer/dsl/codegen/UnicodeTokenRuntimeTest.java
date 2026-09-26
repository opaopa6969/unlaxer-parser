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
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** #121: Unicode token behavior is verified in compiled generated Java code. */
public class UnicodeTokenRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String grammar(String token) {
        return """
            grammar UnicodeTokens {
              @package: org.example.unicodetokens
              token VALUE = %s
              token END = EOF
              @root @mapping(Value, params=[value]) Root ::= VALUE @value END ;
            }
            """.formatted(token);
    }

    private URLClassLoader compile(String token) throws Exception {
        var grammar = UBNFMapper.parse(grammar(token)).grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/unicodetokens/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private void accepted(URLClassLoader loader, String input) throws Exception {
        var mapper = loader.loadClass("org.example.unicodetokens.UnicodeTokensMapper");
        var mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
        assertEquals(input, ast.getClass().getMethod("value").invoke(ast));
        var parser = (org.unlaxer.parser.Parser) loader.loadClass("org.example.unicodetokens.UnicodeTokensParsers")
            .getMethod("getRootParser").invoke(null);
        try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
            assertTrue(parser.parse(context).isSucceeded());
            assertEquals("one Unicode code point consumed", 1, context.getPosition(org.unlaxer.TokenKind.consumed).value());
            assertTrue(context.allConsumed());
        }
    }

    private void rejected(URLClassLoader loader, String input) throws Exception {
        var mapper = loader.loadClass("org.example.unicodetokens.UnicodeTokensMapper");
        var diagnostic = (java.util.Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
        assertTrue("must reject " + input, diagnostic.isPresent());
    }

    @Test public void negationExcludesWholeCodePointsAndKeepsCharApi() throws Exception {
        try (var loader = compile("NEGATION('x😀')")) {
            for (String input : List.of("y", "界", "🚀")) accepted(loader, input);
            for (String input : List.of("", "x", "😀", "yy")) rejected(loader, input);
            var type = loader.loadClass("org.example.unicodetokens.UnicodeTokensParsers$ValueParser");
            var parser = type.getConstructor().newInstance();
            assertEquals(false, type.getMethod("isMatch", char.class).invoke(parser, 'x'));
            assertEquals(true, type.getMethod("isMatch", char.class).invoke(parser, 'y'));
            assertEquals(false, type.getMethod("isMatch", int.class).invoke(parser, 0x1f600));
            assertEquals(true, type.getMethod("isMatch", int.class).invoke(parser, 0x1f680));
        }
        try (var loader = compile("NEGATION('')")) {
            accepted(loader, "😀");
            accepted(loader, "x");
            rejected(loader, "");
        }
    }

    @Test public void validBmpRangesKeepInclusiveMatchingAndCapture() throws Exception {
        try (var loader = compile("CHAR_RANGE('α','ω')")) {
            for (String input : List.of("α", "β", "ω")) accepted(loader, input);
            for (String input : List.of("", "z", "😀", "αβ")) rejected(loader, input);
        }
        try (var loader = compile("CHAR_RANGE('x','x')")) {
            accepted(loader, "x");
            rejected(loader, "y");
        }
    }

    @Test public void invalidUbnfRangeBoundariesAreNeverSilentlyTruncated() {
        for (String boundary : List.of("", "ab", "😀", String.valueOf('\ud800'), String.valueOf('\udfff'))) {
            for (String declaration : List.of("CHAR_RANGE('" + boundary + "','z')", "CHAR_RANGE('a','" + boundary + "')")) {
                var error = assertThrows(IllegalArgumentException.class, () -> UBNFMapper.parse(grammar(declaration)));
                assertTrue(error.toString(), error.getMessage().contains("CHAR_RANGE token VALUE"));
                assertTrue(error.toString(), error.getMessage().contains("BMP"));
            }
        }
        var error = assertThrows(IllegalArgumentException.class,
            () -> UBNFMapper.parse(grammar("CHAR_RANGE('z','a')")));
        assertTrue(error.toString(), error.getMessage().contains("minimum <= maximum"));
    }

    @Test public void programmaticAstCannotBypassRangeValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TokenDecl.CharRange("BAD", '\ud800', '\udfff'));
        assertThrows(IllegalArgumentException.class, () -> new TokenDecl.CharRange("BAD", 'a', '\udfff'));
        assertThrows(IllegalArgumentException.class, () -> new TokenDecl.CharRange("BAD", 'z', 'a'));
        assertEquals('a', new TokenDecl.CharRange("OK", 'a', 'z').min());
        var escaped = (TokenDecl.CharRange) UBNFMapper.parse(grammar("CHAR_RANGE('\\n','\\r')"))
            .grammars().get(0).tokens().get(0);
        assertEquals('\n', escaped.min());
        assertEquals('\r', escaped.max());
    }
}
