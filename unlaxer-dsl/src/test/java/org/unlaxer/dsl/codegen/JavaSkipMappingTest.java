package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.lang.reflect.InvocationTargetException;
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
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

/** Compiles generated Java and checks that @skip is an AST projection boundary. */
public class JavaSkipMappingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String rules) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar SkipProjection {
              @package: org.example.skipprojection
              @whitespace: javaStyle
            """ + rules + "\n}").grammars().get(0);
        var generated = List.of(new ParserGenerator().generate(grammar),
            new ASTGenerator().generate(grammar), new MapperGenerator().generate(grammar));
        var units = generated.stream().<JavaFileObject>map(source -> new SimpleJavaFileObject(
            URI.create("string:///org/example/skipprojection/" + source.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
            }).toList();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object parse(URLClassLoader loader, String input) throws Exception {
        return loader.loadClass("org.example.skipprojection.SkipProjectionMapper")
            .getMethod("parse", String.class).invoke(null, input);
    }

    private static Object field(Object node, String name) throws Exception {
        return node.getClass().getMethod(name).invoke(node);
    }

    @Test public void mappedSkipCaptureKeepsWholeTextWithoutChildNode() throws Exception {
        try (var loader = compile("""
            @root @mapping(Root, params=[hidden]) Root ::= Hidden @hidden;
            @skip @mapping(Ghost) Hidden ::= '😀' Child 'z';
            @mapping(Child) Child ::= 'q';
            """)) {
            Object root = parse(loader, "😀qz");
            assertEquals("😀qz", field(root, "hidden"));
            Object sourceMap = loader.loadClass("org.example.skipprojection.SkipProjectionMapper")
                .getMethod("parseWithSourceMap", String.class).invoke(null, "😀qz");
            Object captured = field(field(sourceMap, "ast"), "hidden");
            var span = (java.util.Optional<?>) sourceMap.getClass()
                .getMethod("sourceSpanOf", Object.class).invoke(sourceMap, captured);
            assertArrayEquals(new int[]{0, 3}, (int[]) span.orElseThrow());
            assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("org.example.skipprojection.SkipProjectionAST$Ghost"));
        }
    }

    @Test public void optionalAndRepeatedSkipCapturesRemainText() throws Exception {
        try (var loader = compile("""
            @root @mapping(Root, params=[maybe, many]) Root ::= [Hidden] @maybe {Hidden} @many;
            @skip @mapping(Ghost) Hidden ::= 'x' Child;
            @mapping(Child) Child ::= 'q';
            """)) {
            Object root = parse(loader, "xqxq");
            assertEquals(java.util.Optional.of("xq"), field(root, "maybe"));
            assertEquals(List.of("xq"), field(root, "many"));
        }
    }

    @Test public void transparentAliasAndGroupCaptureKeepSkippedText() throws Exception {
        try (var loader = compile("""
            @root @mapping(Root, params=[alias, group]) Root ::= Alias @alias ('-' Hidden) @group;
            Alias ::= Hidden;
            @skip Hidden ::= '😀' Child;
            @mapping(Child) Child ::= 'q';
            """)) {
            Object root = parse(loader, "😀q-😀q");
            assertEquals("😀q", field(root, "alias"));
            assertEquals("-😀q", field(root, "group"));
        }
    }

    @Test public void mixedRepeatedCaptureDoesNotProjectChildOfSkippedRule() throws Exception {
        try (var loader = compile("""
            @root @mapping(Root, params=[values]) Root ::= (Hidden | Visible)+ @values;
            @skip Hidden ::= 'x' Child;
            @mapping(Child) Child ::= 'q';
            @mapping(Visible) Visible ::= 'v';
            """)) {
            List<?> values = (List<?>) field(parse(loader, "xqv"), "values");
            assertEquals(2, values.size());
            assertEquals("xq", values.get(0));
            assertEquals("Visible", values.get(1).getClass().getSimpleName());
        }
    }

    @Test public void mixedSingleCaptureReturnsWholeSkippedText() throws Exception {
        try (var loader = compile("""
            @root @mapping(Root, params=[value]) Root ::= (Hidden | Visible) @value;
            @skip Hidden ::= 'x' Child;
            @mapping(Child) Child ::= 'q';
            @mapping(Visible) Visible ::= 'v';
            """)) {
            assertEquals("xq", field(parse(loader, "xq"), "value"));
            assertEquals("Visible", field(parse(loader, "v"), "value").getClass().getSimpleName());
        }
    }

    @Test public void skippedParserTagDoesNotContaminateSharedMappedChild() throws Exception {
        try (var loader = compile("""
            @root @mapping(Root, params=[visible]) Root ::= Child @visible Hidden;
            @skip Hidden ::= Child;
            @mapping(Child) Child ::= 'q';
            """)) {
            Class<? extends Parser> hiddenClass = loader.loadClass(
                "org.example.skipprojection.SkipProjectionParsers$HiddenParser").asSubclass(Parser.class);
            Class<? extends Parser> childClass = loader.loadClass(
                "org.example.skipprojection.SkipProjectionParsers$ChildParser").asSubclass(Parser.class);
            assertTrue(Parser.get(hiddenClass).hasTag(NodeKind.notNode.getTag()));
            assertEquals("Child", field(parse(loader, "qq"), "visible").getClass().getSimpleName());
            assertFalse(Parser.get(childClass).hasTag(NodeKind.notNode.getTag()));
        }
    }

    @Test public void recursiveSkippedRuleParsesWithoutTagTraversal() throws Exception {
        try (var loader = compile("""
            @root @mapping(Root, params=[text]) Root ::= Hidden @text;
            @skip Hidden ::= 'a' [Hidden];
            """)) {
            assertEquals("aaa", field(parse(loader, "aaa"), "text"));
        }
    }

    @Test public void skippedRootParsesButHasNoMappedAst() throws Exception {
        try (var loader = compile("""
            @root @skip @mapping(Ghost) Root ::= Child;
            @mapping(Child) Child ::= 'q';
            """)) {
            var mapper = loader.loadClass("org.example.skipprojection.SkipProjectionMapper");
            assertEquals(java.util.Optional.empty(), mapper.getMethod("diagnose", String.class).invoke(null, "q"));
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> parse(loader, "q"));
            assertTrue(failure.getCause().getMessage(),
                failure.getCause().getMessage().contains("No mapped node"));
        }
    }

    @Test public void skippedRootWithoutAnyMappedRuleStillCompiles() throws Exception {
        try (var loader = compile("@root @skip Root ::= 'q';")) {
            var mapper = loader.loadClass("org.example.skipprojection.SkipProjectionMapper");
            assertEquals(java.util.Optional.empty(), mapper.getMethod("diagnose", String.class).invoke(null, "q"));
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> parse(loader, "q"));
            assertTrue(failure.getCause().getMessage(),
                failure.getCause().getMessage().contains("No mapped node"));
        }
    }
}
