package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.dsl.runtime.ScopeStore.Severity;
import org.unlaxer.parser.Parser;

/**
 * End-to-end regression coverage for generated scope annotation listeners.
 *
 * <p>This deliberately compiles and loads the generated parser. Source-shape tests
 * cannot detect a transaction payload regression where a listener receives raw child
 * tokens instead of the collected rule token.</p>
 */
public class ScopeAnnotationsEndToEndTest {

    private static final String GRAMMAR = """
        grammar ScopeE2E {
          @package: org.unlaxer.dsl.generated.scopee2e
          @whitespace: javaStyle
          token VARNAME = IdentifierParser
          @root
          @scopeTree(mode=lexical)
          Program ::= { Statement } ;
          Statement ::= VarDecl | VarRef ;
          @declares(symbol=varName)
          VarDecl ::= 'let' VARNAME @varName ';' ;
          @backref(name=varName)
          VarRef ::= VARNAME @varName ;
        }
        """;

    private static Class<?> generatedParsersClass;

    @BeforeClass
    public static void compileAndLoadGeneratedParser() throws Exception {
        GrammarDecl grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        CodeGenerator.GeneratedSource generated = new ParserGenerator().generate(grammar);
        Path outputDir = Files.createTempDirectory("scope-annotations-e2e");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("tests require a JDK compiler", compiler);
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, null)) {
            String uriPath = "/" + generated.packageName().replace('.', '/')
                + "/" + generated.className() + ".java";
            JavaFileObject source = new SimpleJavaFileObject(
                    URI.create("string://" + uriPath), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return generated.source();
                }
            };
            List<String> options = List.of(
                "--enable-preview", "--release", "21",
                "-classpath", System.getProperty("java.class.path"),
                "-d", outputDir.toString());
            StringWriter diagnostics = new StringWriter();
            boolean compiled = compiler.getTask(
                new PrintWriter(diagnostics), fileManager, null, options, null, List.of(source)).call();
            assertTrue("generated scope parser should compile:\n" + diagnostics, compiled);
        }

        URLClassLoader loader = URLClassLoader.newInstance(
            new URL[]{outputDir.toUri().toURL()},
            ScopeAnnotationsEndToEndTest.class.getClassLoader());
        generatedParsersClass = loader.loadClass(
            "org.unlaxer.dsl.generated.scopee2e.ScopeE2EParsers");
    }

    @Test
    public void generatedParserRegistersDeclarationsAndUndefinedReferenceWarning() throws Exception {
        ParseResult result = parse("let known; known missing");

        assertTrue("generated parser should accept declaration and references",
            result.parsed.isSucceeded());
        assertTrue("generated parser should consume the whole source", result.context.allConsumed());
        assertEquals(1, ScopeStore.getAllDeclarations(result.context).size());
        assertEquals("known", ScopeStore.getAllDeclarations(result.context).get(0).name());
        assertEquals(1, ScopeStore.getDiagnostics(result.context).size());
        assertEquals(Severity.WARNING, ScopeStore.getDiagnostics(result.context).get(0).severity());
        assertTrue(ScopeStore.getDiagnostics(result.context).get(0).message().contains("missing"));

        result.context.close();
    }

    @Test
    public void deprecatedDispatcherIsNoOpAndDoesNotDuplicateDeclarations() throws Exception {
        Parser root = rootParser();
        ParseContext context = new ParseContext(StringSource.createRootSource("let once;"));
        ScopeStore.registerDispatcher(context);

        Parsed parsed = root.parse(context);

        assertTrue(parsed.isSucceeded());
        assertTrue(context.allConsumed());
        assertEquals("dispatcher compatibility call must not double-notify",
            1, ScopeStore.getAllDeclarations(context).size());
        assertEquals("once", ScopeStore.getAllDeclarations(context).get(0).name());
        context.close();
    }

    @Test
    public void generatedRootIsTheLoadedParserClass() throws Exception {
        Parser root = rootParser();
        assertFalse(root.getClass().getName().isBlank());
        assertSame(generatedParsersClass, root.getClass().getEnclosingClass());
    }

    private static ParseResult parse(String source) throws Exception {
        ParseContext context = new ParseContext(StringSource.createRootSource(source));
        return new ParseResult(rootParser().parse(context), context);
    }

    private static Parser rootParser() throws Exception {
        return (Parser) generatedParsersClass.getMethod("getRootParser").invoke(null);
    }

    private record ParseResult(Parsed parsed, ParseContext context) {}
}
