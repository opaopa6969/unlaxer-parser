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

/** Mixed text/node captures must preserve the selected value, not normalize to the first branch. */
public class MixedValueRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String body) throws Exception {
        var grammar = UBNFMapper.parse("grammar Mixed { @package: org.example.mixed " + body + " }")
            .grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/mixed/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean succeeded = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(body + "\n" + diagnostics.getDiagnostics(), succeeded);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object parse(URLClassLoader loader, String text) throws Exception {
        return loader.loadClass("org.example.mixed.MixedMapper").getMethod("parse", String.class).invoke(null, text);
    }

    private Object field(Object value, String name) throws Exception {
        return value.getClass().getMethod(name).invoke(value);
    }

    private void assertLeaf(Object value) {
        assertEquals("Leaf", value.getClass().getSimpleName());
    }

    @Test public void oneMappedAndOneTextBranchRemainDistinctThroughAliasesAndGroups() throws Exception {
        for (String expression : List.of("Factor", "Alias", "(Factor)", "('a' | Leaf)", "(Leaf | 'a')")) {
            try (var loader = compile("""
                @root @mapping(Box, params=[value]) Root ::= %s @value;
                Alias ::= (Factor);
                Factor ::= 'a' | Leaf;
                @mapping(Leaf) Leaf ::= 'x';
                """.formatted(expression))) {
                Object text = parse(loader, "a");
                assertEquals(Object.class, text.getClass().getMethod("value").getReturnType());
                assertEquals("a", field(text, "value"));
                assertLeaf(field(parse(loader, "x"), "value"));
            }
        }
    }

    @Test public void mixedOptionalAndRepeatedCapturesRetainBothBranches() throws Exception {
        for (String expression : List.of("Factor", "Alias", "('a' | Leaf)")) {
            try (var loader = compile("""
                @root @mapping(Box, params=[head, values])
                Root ::= [%s] @head ':' {%s} @values;
                Alias ::= (Factor);
                Factor ::= 'a' | Leaf;
                @mapping(Leaf) Leaf ::= 'x';
                """.formatted(expression, expression))) {
                Object empty = parse(loader, ":");
                assertEquals(Optional.empty(), field(empty, "head"));
                assertEquals(List.of(), field(empty, "values"));
                Object ast = parse(loader, "x:axa");
                assertLeaf(((Optional<?>) field(ast, "head")).orElseThrow());
                List<?> values = (List<?>) field(ast, "values");
                assertEquals(3, values.size());
                assertEquals("a", values.get(0));
                assertLeaf(values.get(1));
                assertEquals("a", values.get(2));
                assertEquals(Optional.of("a"), field(parse(loader, "a:"), "head"));
            }
        }
    }

    @Test public void mixedBoundariesKeepFullTextAndMappedSourceSpans() throws Exception {
        try (var loader = compile("""
            @root @mapping(Box, params=[value]) Root ::= '!' (('a' ':' 'b') | Leaf) @value;
            @mapping(Leaf, params=[text]) Leaf ::= '😀' @text;
            """)) {
            assertEquals("a:b", field(parse(loader, "!a:b"), "value"));
            Object leaf = field(parse(loader, "!😀"), "value");
            assertLeaf(leaf);
            assertEquals("😀", field(leaf, "text"));
            var mapper = loader.loadClass("org.example.mixed.MixedMapper");
            var span = (Optional<?>) mapper.getMethod("sourceSpanOf", Object.class).invoke(null, leaf);
            assertArrayEquals(new int[]{1, 2}, (int[]) span.orElseThrow());
        }
    }

    @Test public void sharedMixedSchemasAreIndependentOfRepresentativeRule() throws Exception {
        for (String container : List.of("%s", "[%s]", "{%s}")) {
            String text = "@mapping(Box, params=[value]) First ::= 'f' " + container.formatted("'a'") + " @value;";
            String node = "@mapping(Box, params=[value]) Second ::= 's' " + container.formatted("Leaf") + " @value;";
            for (String family : List.of(text + node, node + text)) {
                try (var loader = compile("@root Root ::= First | Second;" + family + "@mapping(Leaf) Leaf ::= 'x';")) {
                    Object a = field(parse(loader, "fa"), "value");
                    Object x = field(parse(loader, "sx"), "value");
                    if (container.startsWith("[")) {
                        assertEquals(Optional.empty(), field(parse(loader, "f"), "value"));
                        assertEquals(Optional.empty(), field(parse(loader, "s"), "value"));
                        a = ((Optional<?>) a).orElseThrow();
                        x = ((Optional<?>) x).orElseThrow();
                    } else if (container.startsWith("{")) {
                        assertEquals(List.of(), field(parse(loader, "f"), "value"));
                        assertEquals(List.of(), field(parse(loader, "s"), "value"));
                        assertEquals(2, ((List<?>) field(parse(loader, "sxx"), "value")).size());
                        a = ((List<?>) a).get(0);
                        x = ((List<?>) x).get(0);
                    }
                    assertEquals("a", a);
                    assertLeaf(x);
                }
            }
        }
    }

    @Test public void incompatibleSharedParametersAndCardinalityAreRejectedExplicitly() {
        String source = """
            grammar Shared {
              @root Root ::= First | Second;
              @mapping(Box, params=[value]) First ::= 'a' @value;
              @mapping(Box, params=[value]) Second ::= ['b'] @value;
            }
            """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new ASTGenerator().generate(grammar))
            .getMessage().contains("capture cardinality"));
        var names = UBNFMapper.parse(source.replace("params=[value]) Second ::= ['b'] @value",
            "params=[other]) Second ::= 'b' @other")).grammars().get(0);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new ASTGenerator().generate(names))
            .getMessage().contains("parameter names/order"));
    }
}
