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

/** #129: a reference is not necessarily an AST sum variant. */
public class ZeroFieldMappingRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private URLClassLoader compile(String declarations) throws Exception {
        var grammar = UBNFMapper.parse("grammar ZeroFields { @package: org.example.zerofields "
            + declarations + " }").grammars().get(0);
        var units = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar)).stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/zerofields/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
                }).toList();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private Object parse(URLClassLoader loader, String input, String type, int start, int end) throws Exception {
        var mapper = loader.loadClass("org.example.zerofields.ZeroFieldsMapper");
        var mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
        var ast = mapped.getClass().getMethod("ast").invoke(mapped);
        assertEquals(type, ast.getClass().getSimpleName());
        assertTrue(ast.getClass().isRecord());
        var span = (Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast);
        assertArrayEquals(new int[]{start, end}, (int[]) span.orElseThrow());
        return ast;
    }

    @Test public void tokenAliasesProduceConcreteZeroFieldRecords() throws Exception {
        for (String declaration : List.of("token T=EMPTY", "token T=EOF")) {
            try (var loader = compile(declaration + " @root @mapping(Item) Part ::= T;")) {
                assertEquals(0, parse(loader, "", "Item", 0, 0).getClass().getRecordComponents().length);
            }
        }
        try (var loader = compile("token T=NumberParser @root @mapping(Item) Part ::= T;")) {
            parse(loader, "12", "Item", 0, 2);
        }
    }

    @Test public void unmappedAliasesAndLiteralBodiesRemainConcrete() throws Exception {
        for (String body : List.of("T", "'a'", "'a' 'b'", "'a' | 'b'", "T | U")) {
            try (var loader = compile("T ::= 'a'; U ::= 'b'; @root @mapping(Item) Part ::= " + body + ";")) {
                String input = body.equals("'a' 'b'") ? "ab" : "a";
                assertEquals(0, parse(loader, input, "Item", 0, input.length()).getClass().getRecordComponents().length);
            }
        }
    }

    @Test public void mappedRuleAliasesAndSumsDispatchToConcreteVariants() throws Exception {
        try (var loader = compile("@root @mapping(Node) Root ::= Leaf; @mapping(Item) Leaf ::= 'a';")) {
            Object ast = parse(loader, "a", "Item", 0, 1);
            assertTrue(loader.loadClass("org.example.zerofields.ZeroFieldsAST$Node").isInstance(ast));
        }
        try (var loader = compile("@root @mapping(Node) Root ::= First | Second; "
                + "@mapping(A) First ::= 'a'; @mapping(B) Second ::= 'b';")) {
            parse(loader, "a", "A", 0, 1);
            parse(loader, "b", "B", 0, 1);
        }
        try (var loader = compile("@root @mapping(Node) Root ::= First | Second; "
                + "@mapping(Item) First ::= 'a'; @mapping(Item) Second ::= 'b';")) {
            parse(loader, "a", "Item", 0, 1);
            parse(loader, "b", "Item", 0, 1);
        }
    }

    @Test public void dottedZeroFieldRecordsAndSumsCompileTogether() throws Exception {
        try (var loader = compile("token T=EMPTY @root @mapping(Node) Root ::= First | Second; "
                + "@mapping(Node.A) First ::= 'a'; @mapping(Node.B) Second ::= T;")) {
            parse(loader, "a", "A", 0, 1);
            parse(loader, "", "B", 0, 0);
        }
    }

    @Test public void selectedOuterVariantIsNotConfusedWithItsNestedVariant() throws Exception {
        try (var loader = compile("token NUM=NumberParser @root @mapping(Node) Root ::= First | Second; "
                + "@mapping(A, params=[value]) First ::= NUM @value; "
                + "@mapping(B, params=[value]) Second ::= '(' First @value ')';")) {
            Object ast = parse(loader, "(12)", "B", 0, 4);
            Object child = ast.getClass().getMethod("value").invoke(ast);
            assertEquals("A", child.getClass().getSimpleName());
            assertEquals(12, child.getClass().getMethod("value").invoke(child));
        }
    }

    @Test public void nestedAliasesAndSharedSumVariantsKeepDirectSubtypeRelations() throws Exception {
        try (var loader = compile("@root @mapping(Node) Root ::= Alias; @mapping(Expr) Alias ::= Leaf; "
                + "@mapping(Item) Leaf ::= 'a';")) {
            parse(loader, "a", "Item", 0, 1);
        }
        try (var loader = compile("@root @mapping(Node) Root ::= Left | Right; "
                + "@mapping(LeftNode) Left ::= Leaf; @mapping(RightNode) Right ::= Leaf; "
                + "@mapping(Item) Leaf ::= 'a';")) {
            Object ast = parse(loader, "a", "Item", 0, 1);
            assertTrue(loader.loadClass("org.example.zerofields.ZeroFieldsAST$LeftNode").isInstance(ast));
            assertTrue(loader.loadClass("org.example.zerofields.ZeroFieldsAST$RightNode").isInstance(ast));
        }
    }
}
