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
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.dsl.runtime.ScopeStore.SymbolDiagnostic;
import org.unlaxer.parser.Parser;

/** Executes the generated Java parser, including its transactional listener. */
public class JavaCaptureEqualityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String declarations, String body) throws Exception {
        return compile(declarations, "", body);
    }

    private URLClassLoader compile(String declarations, String annotation, String body) throws Exception {
        String source = "grammar Equality {\n"
            + "  @package: org.example.equality\n"
            + declarations + "\n"
            + "  @root " + annotation + " @backref(name=tag) Root ::= " + body + " ;\n"
            + "}";
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        var generated = new ParserGenerator().generate(grammar);
        var unit = new SimpleJavaFileObject(
            URI.create("string:///org/example/equality/" + generated.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return generated.source();
            }
        };
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = ToolProvider.getSystemJavaCompiler()
                .getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = ToolProvider.getSystemJavaCompiler().getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, List.of(unit)).call();
            assertTrue(source + "\n" + diagnostics.getDiagnostics(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private List<SymbolDiagnostic> parse(URLClassLoader loader, String input, boolean succeeds)
            throws Exception {
        Parser parser = (Parser) loader.loadClass("org.example.equality.EqualityParsers")
            .getMethod("getRootParser").invoke(null);
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            assertEquals(input, succeeds, parser.parse(context).isSucceeded());
            if (succeeds) {
                assertTrue(input, context.allConsumed());
                int length = input.codePointCount(0, input.length());
                assertEquals(length, context.getPosition(TokenKind.consumed).value());
                assertEquals(length, context.getPosition(TokenKind.matchOnly).value());
            } else {
                assertEquals(0, context.getPosition(TokenKind.consumed).value());
                assertEquals(0, context.getPosition(TokenKind.matchOnly).value());
            }
            return List.copyOf(ScopeStore.getDiagnostics(context));
        }
    }

    private void mismatch(SymbolDiagnostic diagnostic, int offset, int length,
            String expected, String actual) {
        assertEquals(ScopeStore.Severity.ERROR, diagnostic.severity());
        assertEquals(offset, diagnostic.offset());
        assertEquals(length, diagnostic.length());
        assertEquals("back-reference mismatch: expected '" + expected + "' but got '" + actual + "'",
            diagnostic.message());
    }

    @Test public void captureNameExcludesAliasedParserAndNestedRule() throws Exception {
        try (var loader = compile("token TAG = org.unlaxer.parser.clang.IdentifierParser\n  Inner ::= TAG @tag ;",
                "TAG @tag ':' TAG @other ':' Inner ':' TAG @tag")) {
            assertTrue(parse(loader, "a:b:c:a", true).isEmpty());
            List<SymbolDiagnostic> diagnostics = parse(loader, "a:b:c:d", true);
            assertEquals(1, diagnostics.size());
            mismatch(diagnostics.get(0), 6, 1, "a", "d");
        }
    }

    @Test public void distinctParserClassesAndNestedGroupsUseCompletionOrder() throws Exception {
        try (var loader = compile("", "(('a') @tag 'b') @tag ':' ('c') @tag")) {
            List<SymbolDiagnostic> diagnostics = parse(loader, "ab:c", true);
            assertEquals(2, diagnostics.size());
            mismatch(diagnostics.get(0), 0, 2, "a", "ab");
            mismatch(diagnostics.get(1), 3, 1, "a", "c");
        }
    }

    @Test public void repeatedCaptureAndFailureRollback() throws Exception {
        try (var loader = compile("", "('a' @tag 'b' @tag)+ '!'")) {
            List<SymbolDiagnostic> diagnostics = parse(loader, "abab!", true);
            assertEquals(2, diagnostics.size());
            mismatch(diagnostics.get(0), 1, 1, "a", "b");
            mismatch(diagnostics.get(1), 3, 1, "a", "b");
            assertTrue(parse(loader, "abab?", false).isEmpty());
        }
    }

    @Test public void singleCaptureIsNoopAndUnicodeOffsetIsCodePointBased() throws Exception {
        try (var loader = compile("", "'😀' 'a' @tag 'b' @tag")) {
            List<SymbolDiagnostic> diagnostics = parse(loader, "😀ab", true);
            assertEquals(1, diagnostics.size());
            mismatch(diagnostics.get(0), 2, 1, "a", "b");
        }
        try (var loader = compile("", "'😀' @tag")) {
            assertTrue(parse(loader, "😀", true).isEmpty());
        }
    }

    @Test public void emptyCapturesAndTrimmedCodePointRange() throws Exception {
        try (var loader = compile("token E = EMPTY", "E @tag 'a' @tag")) {
            List<SymbolDiagnostic> diagnostics = parse(loader, "a", true);
            assertEquals(1, diagnostics.size());
            mismatch(diagnostics.get(0), 0, 1, "", "a");
        }
        try (var loader = compile("token E = EMPTY", "E @tag E @tag")) {
            assertTrue(parse(loader, "", true).isEmpty());
        }
        try (var loader = compile("", "'😀' ' a ' @tag ':' ' b ' @tag")) {
            List<SymbolDiagnostic> diagnostics = parse(loader, "😀 a : b ", true);
            assertEquals(1, diagnostics.size());
            mismatch(diagnostics.get(0), 6, 1, "a", "b");
        }
    }

    @Test public void skippedRootStillEmitsDiagnosticAndFailedChoiceDoesNot() throws Exception {
        try (var loader = compile("", "@skip", "'a' @tag 'b' @tag")) {
            assertEquals(1, parse(loader, "ab", true).size());
        }
        try (var loader = compile("", "('a' @tag 'b' @tag 'x' | 'a' @tag 'a' @tag)")) {
            assertTrue(parse(loader, "aa", true).isEmpty());
        }
    }

    @Test public void invalidCaptureIsRejectedWithoutScopeTree() {
        var grammar = UBNFMapper.parse("""
            grammar Equality {
              @package: org.example.equality
              @root @backref(name=missing) Root ::= 'a' @tag ;
            }
            """).grammars().get(0);
        try {
            GrammarValidator.validateOrThrow(grammar);
            fail("unknown capture must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("E-ANNOTATION-BACKREF-CAPTURE"));
        }
    }
}
