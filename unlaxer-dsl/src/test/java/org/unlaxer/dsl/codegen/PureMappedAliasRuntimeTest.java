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

/** #163: transparent aliases preserve node values rather than their source spelling. */
public class PureMappedAliasRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String body) throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Alias { @package: org.example.alias
            %s
            @mapping(Leaf, params=[text]) Leaf ::= 'x' @text;
            @mapping(Other, params=[text]) Other ::= 'z' @text;
            }
            """.formatted(body)).grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar), GeneratedJavaRelease.evaluatorGenerator().generate(grammar))
            .stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/alias/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean succeeded = compiler.getTask(null, manager, diagnostics,
                List.of("--release", GeneratedJavaRelease.EVALUATOR_RELEASE_OPTION, "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(body + "\n" + diagnostics.getDiagnostics(), succeeded);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object mapped(URLClassLoader loader, String input) throws Exception {
        return loader.loadClass("org.example.alias.AliasMapper").getMethod("parseWithSourceMap", String.class).invoke(null, input);
    }

    private Object field(Object node, String name) throws Exception {
        return node.getClass().getMethod(name).invoke(node);
    }

    private void assertNode(URLClassLoader loader, Object node, String kind, String text) throws Exception {
        assertTrue("must preserve an AST, not String or arbitrary Object", loader.loadClass("org.example.alias.AliasAST").isInstance(node));
        assertEquals(kind, node.getClass().getSimpleName());
        assertEquals(text, field(node, "text"));
    }

    private void assertSpan(Object snapshot, Object node, int start, int end) throws Exception {
        var span = (Optional<?>) snapshot.getClass().getMethod("sourceSpanOf", Object.class).invoke(snapshot, node);
        assertArrayEquals(new int[]{start, end}, (int[]) span.orElseThrow());
    }

    @Test public void scalarAliasesGroupsAndDelimitersPreserveNodeAndItsOwnSpan() throws Exception {
        for (String[] fixture : new String[][] {
            {"Alias", "Alias ::= Leaf;", "x", "1", "2"},
            {"Alias", "Alias ::= Next; Next ::= Last; Last ::= Leaf;", "x", "1", "2"},
            {"Alias", "Alias ::= (Leaf);", "x", "1", "2"},
            {"(Alias)", "Alias ::= Leaf;", "x", "1", "2"},
            {"Alias", "Alias ::= '(' Leaf ')';", "(x)", "2", "3"},
            {"('(' Alias ')')", "Alias ::= Leaf;", "(x)", "2", "3"}
        }) {
            try (var loader = compile("@root @mapping(Box, params=[value]) Root ::= '😀' "
                    + fixture[0] + " @value;" + fixture[1])) {
                Object snapshot = mapped(loader, "😀" + fixture[2]);
                Object ast = field(snapshot, "ast");
                assertEquals(Object.class, ast.getClass().getMethod("value").getReturnType());
                Object leaf = field(ast, "value");
                assertNode(loader, leaf, "Leaf", "x");
                assertSpan(snapshot, leaf, Integer.parseInt(fixture[3]), Integer.parseInt(fixture[4]));
                assertSpan(snapshot, ast, 0, 1 + fixture[2].length());
                mapped(loader, "😀" + fixture[2]);
                assertSpan(snapshot, leaf, Integer.parseInt(fixture[3]), Integer.parseInt(fixture[4]));
            }
        }
    }

    @Test public void multipleTargetsAndMappedSumAliasesRetainActualVariant() throws Exception {
        for (String rules : List.of("Alias ::= Leaf | Other;", "Alias ::= Next; Next ::= (Leaf | Other);",
                "Alias ::= Sum; @mapping(Variant) Sum ::= Leaf | Other;")) {
            try (var loader = compile("@root @mapping(Box, params=[value]) Root ::= Alias @value;" + rules)) {
                for (String input : List.of("x", "z")) {
                    Object snapshot = mapped(loader, input);
                    Object value = field(field(snapshot, "ast"), "value");
                    assertNode(loader, value, input.equals("x") ? "Leaf" : "Other", input);
                    assertSpan(snapshot, value, 0, 1);
                }
            }
        }
    }

    @Test public void optionalAndListAliasesExposeContainersOfObjectsContainingOnlyNodes() throws Exception {
        for (String targets : List.of("Leaf", "Leaf | Other")) {
            try (var loader = compile("""
                @root @mapping(Box, params=[head,values]) Root ::= [Alias] @head ':' {Alias} @values;
                Alias ::= Next; Next ::= %s;
                """.formatted(targets))) {
                Object empty = field(mapped(loader, ":"), "ast");
                assertEquals("java.util.Optional<java.lang.Object>", empty.getClass().getMethod("head").getGenericReturnType().getTypeName());
                assertEquals("java.util.List<java.lang.Object>", empty.getClass().getMethod("values").getGenericReturnType().getTypeName());
                assertEquals(Optional.empty(), field(empty, "head"));
                assertEquals(List.of(), field(empty, "values"));
                String last = targets.contains("Other") ? "z" : "x";
                Object snapshot = mapped(loader, "x:x" + last);
                Object ast = field(snapshot, "ast");
                assertNode(loader, ((Optional<?>) field(ast, "head")).orElseThrow(), "Leaf", "x");
                List<?> values = (List<?>) field(ast, "values");
                assertEquals(2, values.size());
                assertNode(loader, values.get(0), "Leaf", "x");
                assertNode(loader, values.get(1), last.equals("z") ? "Other" : "Leaf", last);
                assertNotSame(values.get(0), values.get(1));
                assertSpan(snapshot, values.get(0), 2, 3);
                assertSpan(snapshot, values.get(1), 3, 4);
            }
        }
    }

    @Test public void hiddenOptionalAndParallelAliasValuesKeepSemanticCardinality() throws Exception {
        for (String helper : List.of("[Alias]", "{Alias}", "Alias Alias")) {
            try (var loader = compile("""
                @root @mapping(Box, params=[value]) Root ::= '(' Helper @value ')';
                Helper ::= %s;
                Alias ::= Next; Next ::= Leaf;
                """.formatted(helper))) {
                boolean optional = helper.startsWith("[");
                Object snapshot = mapped(loader, optional ? "(x)" : "(xx)");
                Object ast = field(snapshot, "ast");
                String container = optional ? "java.util.Optional" : "java.util.List";
                assertEquals(container + "<java.lang.Object>", ast.getClass().getMethod("value").getGenericReturnType().getTypeName());
                Object value = field(ast, "value");
                List<?> nodes = optional ? List.of(((Optional<?>) value).orElseThrow()) : (List<?>) value;
                for (int i = 0; i < nodes.size(); i++) {
                    assertNode(loader, nodes.get(i), "Leaf", "x");
                    assertSpan(snapshot, nodes.get(i), i + 1, i + 2);
                }
                if (!helper.equals("Alias Alias")) {
                    assertEquals(optional ? Optional.empty() : List.of(), field(field(mapped(loader, "()"), "ast"), "value"));
                }
            }
        }
    }

    @Test public void mappedOuterBoundaryIsNotReplacedByItsNestedMappedChild() throws Exception {
        try (var loader = compile("""
            @root @mapping(Box, params=[value]) Root ::= Alias @value;
            Alias ::= Wrapper;
            @mapping(Wrapper, params=[child]) Wrapper ::= '(' Leaf @child ')';
            """)) {
            Object snapshot = mapped(loader, "(x)");
            Object wrapper = field(field(snapshot, "ast"), "value");
            assertEquals("Wrapper", wrapper.getClass().getSimpleName());
            assertSpan(snapshot, wrapper, 0, 3);
            Object leaf = field(wrapper, "child");
            assertNode(loader, leaf, "Leaf", "x");
            assertSpan(snapshot, leaf, 1, 2);
        }
    }

    @Test public void quantifiedAliasItemsPreserveOrderAndIndividualSpans() throws Exception {
        for (String expression : List.of("Alias+", "Alias{1,2}", "Alias % ','")) {
            try (var loader = compile("@root @mapping(Box, params=[value]) Root ::= " + expression
                    + " @value; Alias ::= Leaf | Other;")) {
                boolean separated = expression.contains("%");
                Object snapshot = mapped(loader, separated ? "x,z" : "xz");
                Object ast = field(snapshot, "ast");
                assertEquals("java.util.List<java.lang.Object>", ast.getClass().getMethod("value").getGenericReturnType().getTypeName());
                List<?> values = (List<?>) field(ast, "value");
                assertEquals(2, values.size());
                assertNode(loader, values.get(0), "Leaf", "x");
                assertNode(loader, values.get(1), "Other", "z");
                assertSpan(snapshot, values.get(0), 0, 1);
                assertSpan(snapshot, values.get(1), separated ? 2 : 1, separated ? 3 : 2);
            }
        }
    }

    @Test public void zeroWidthMappedAliasKeepsItsCodePointAnchor() throws Exception {
        try (var loader = compile("""
            token EMPTY_TOKEN = EMPTY
            @root @mapping(Box, params=[value]) Root ::= '😀' Alias @value 'x';
            Alias ::= Next; Next ::= Zero;
            @mapping(Zero) Zero ::= EMPTY_TOKEN;
            """)) {
            Object snapshot = mapped(loader, "😀x");
            Object node = field(field(snapshot, "ast"), "value");
            assertTrue(loader.loadClass("org.example.alias.AliasAST").isInstance(node));
            assertEquals("Zero", node.getClass().getSimpleName());
            assertSpan(snapshot, node, 1, 1);
        }
    }
}
