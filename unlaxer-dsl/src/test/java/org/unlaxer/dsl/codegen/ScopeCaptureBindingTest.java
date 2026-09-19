package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
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
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;

public class ScopeCaptureBindingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void usesNamedSiteInsteadOfFirstTokenOfSameParserClass() throws Exception {
        check("""
            @root @scopeTree(mode=lexical) Program ::= Decl Ref;
            @declares(symbol=name) Decl ::= ID @noise ':' ID @name ';';
            @backref(name=name) Ref ::= ID @noise ':' ID @name ';';
            """, "prefix:x;prefix:x;", List.of(new ScopeStore.SymbolInfo("x", 7)),
            List.of(new ScopeStore.ReferenceInfo("x", 16, 1)));
    }

    @Test public void trimsCapturedGroupAndReportsCodePointOffsetsAndLength() throws Exception {
        check("""
            @root @scopeTree(mode=lexical) Program ::= Decl Ref;
            @declares(symbol=name) Decl ::= (' ' '😀' ' ') @name ';';
            @backref(name=name) Ref ::= (' ' '😀' ' ') @name ';';
            """, " 😀 ; 😀 ;", List.of(new ScopeStore.SymbolInfo("😀", 1)),
            List.of(new ScopeStore.ReferenceInfo("😀", 5, 1)));
    }

    @Test public void repeatsAllNamedSitesButDoesNotBorrowRecursiveChildCaptures() throws Exception {
        check("""
            @root @scopeTree(mode=lexical) Program ::= Decl Ref;
            @declares(symbol=name) Decl ::= { ID @name ',' };
            @backref(name=name) Ref ::= '(' Ref ')' | ID @name;
            """, "a,b,(a)", List.of(new ScopeStore.SymbolInfo("a", 0), new ScopeStore.SymbolInfo("b", 2)),
            List.of(new ScopeStore.ReferenceInfo("a", 5, 1)));
    }

    @Test public void nestedNamedCapturesPreserveEveryOccurrenceInCompletionOrder() throws Exception {
        for (String captured : List.of("('a' @name) @name", "{ 'a' @name } @name")) {
            check("@root @scopeTree(mode=lexical) Program ::= Decl Ref; "
                + "@declares(symbol=name) Decl ::= " + captured + " ';'; "
                + "@backref(name=name) Ref ::= " + captured + " ';';", "a;a;",
                List.of(new ScopeStore.SymbolInfo("a", 0), new ScopeStore.SymbolInfo("a", 0)),
                List.of(new ScopeStore.ReferenceInfo("a", 2, 1), new ScopeStore.ReferenceInfo("a", 2, 1)));
        }
        check("""
            @root @scopeTree(mode=lexical) Program ::= Decl;
            @declares(symbol=name) Decl ::= ('a' @name 'b') @name;
            """, "ab", List.of(new ScopeStore.SymbolInfo("a", 0), new ScopeStore.SymbolInfo("ab", 0)), List.of());
    }

    @Test public void captureTargetsInsideAllQuantifiersAreValidAndExecuted() throws Exception {
        for (String captured : List.of("('a' @name)+", "('a' @name){1,2}", "('a' @name) % ','")) {
            boolean separated = captured.contains("%");
            check("@root @scopeTree(mode=lexical) Program ::= Decl; @declares(symbol=name) Decl ::= " + captured + ";",
                separated ? "a,a" : "aa", List.of(new ScopeStore.SymbolInfo("a", 0),
                    new ScopeStore.SymbolInfo("a", separated ? 2 : 1)), List.of());
        }
    }

    private void check(String rules, String input, List<ScopeStore.SymbolInfo> declarations,
            List<ScopeStore.ReferenceInfo> references) throws Exception {
        var grammar = UBNFMapper.parse("grammar ScopeBinding { @package: org.example.scopebinding "
            + "token ID = org.unlaxer.parser.clang.IdentifierParser " + rules + " }").grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        var generated = new ParserGenerator().generate(grammar);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        var unit = new SimpleJavaFileObject(URI.create("string:///org/example/scopebinding/ScopeBindingParsers.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return generated.source(); }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "21", "--enable-preview", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, List.of(unit)).call());
        }
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
             var context = new ParseContext(StringSource.createRootSource(input))) {
            var parsers = loader.loadClass("org.example.scopebinding.ScopeBindingParsers");
            Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
            assertTrue(parser.parse(context).isSucceeded());
            assertTrue(context.allConsumed());
            assertEquals(declarations, ScopeStore.getAllDeclarations(context));
            assertEquals(references, ScopeStore.getAllReferences(context));
            assertTrue(ScopeStore.getDiagnostics(context).isEmpty());
            assertEquals(0, ScopeStore.currentScopeDepth(context));
        }
    }
}
