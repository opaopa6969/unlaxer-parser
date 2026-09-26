package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** #130: evaluator API, runtime dispatch and Generation Gap checked by real javac. */
public class EvaluatorVariantRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private GrammarDecl grammar(String body) {
        return UBNFMapper.parse("grammar Variant { @package: example.variants " + body + " }").grammars().get(0);
    }

    private record Compilation(boolean success, String diagnostics, Path output) {}

    private Compilation compile(GrammarDecl grammar, String evaluator, String methods) throws Exception {
        var sources = new ArrayList<CodeGenerator.GeneratedSource>();
        sources.add(new ASTGenerator().generate(grammar));
        sources.add(new CodeGenerator.GeneratedSource("example.variants", "VariantEvaluator", evaluator));
        if (methods != null) sources.add(new CodeGenerator.GeneratedSource("example.variants", "Semantics",
            "package example.variants; public class Semantics extends VariantEvaluator<Integer> { " + methods + " }"));
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///example/variants/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return source.source(); }
            }).toList();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var output = temporary.newFolder().toPath();
        boolean success;
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", GeneratedJavaRelease.EVALUATOR_RELEASE_OPTION, "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call();
        }
        String messages = diagnostics.getDiagnostics().stream().map(d -> d.getCode() + ": " + d.getMessage(Locale.ROOT))
            .reduce("", (a, b) -> a + "\n" + b);
        return new Compilation(success, messages, output);
    }

    private void run(GrammarDecl grammar, String methods, int expected) throws Exception {
        var result = compile(grammar, GeneratedJavaRelease.evaluatorGenerator().generate(grammar).source(), methods);
        assertTrue(result.diagnostics(), result.success());
        try (var loader = new URLClassLoader(new URL[]{result.output().toUri().toURL()}, getClass().getClassLoader())) {
            assertEquals(expected, loader.loadClass("example.variants.Semantics").getMethod("run").invoke(null));
        }
    }

    @Test public void simpleRecordKeepsItsExistingSemanticMethodName() throws Exception {
        run(grammar("token T=EMPTY @root @mapping(Item) Root ::= T;"), """
            @Override protected Integer evalItem(VariantAST.Item node) { return 7; }
            public static int run() { return new Semantics().eval(new VariantAST.Item()); }
            """, 7);
    }

    @Test public void sumParentNeverShadowsDottedConcreteMethodsAndLegacyOverrideStillCompiles() throws Exception {
        run(grammar("token NUM=NumberParser @root @mapping(Node) Root ::= Number | Empty; "
            + "@mapping(Node.Num, params=[value]) Number ::= NUM @value; @mapping(Node.Nothing) Empty ::= 'empty';"), """
            @Override protected Integer evalNode(VariantAST.Node node) { throw new AssertionError("parent shadowed leaf"); }
            @Override protected Integer evalNodeNum(VariantAST.Node.Num node) { return node.value(); }
            @Override protected Integer evalNodeNothing(VariantAST.Node.Nothing node) { return 3; }
            public static int run() {
                Semantics semantics = new Semantics();
                int[] steps = {0};
                semantics.setDebugStrategy(new StepCounterStrategy((step, node) -> steps[0]++));
                int result = semantics.eval(new VariantAST.Node.Num(9)) + semantics.eval(new VariantAST.Node.Nothing());
                if (steps[0] != 2) throw new AssertionError("duplicate debug event");
                if (semantics.superNode(new VariantAST.Node.Nothing()) != 3) throw new AssertionError("adapter");
                return result;
            }
            private int superNode(VariantAST.Node node) { return super.evalNode(node); }
            """, 12);
    }

    @Test public void nestedAliasesAndSharedVariantsNeedOnlyConcreteSemantics() throws Exception {
        for (String body : List.of(
                "@root @mapping(Node) Root ::= Alias; @mapping(Expr) Alias ::= Leaf; @mapping(Item) Leaf ::= 'a';",
                "@root @mapping(Node) Root ::= Left | Right; @mapping(LeftNode) Left ::= Leaf; "
                    + "@mapping(RightNode) Right ::= Leaf; @mapping(Item) Leaf ::= 'a';",
                "@mapping(Item) Leaf ::= 'a'; @root @mapping(Node) @eval(kind='literal', strategy='manual') Root ::= Leaf;")) {
            run(grammar(body), """
                @Override protected Integer evalItem(VariantAST.Item node) { return 4; }
                public static int run() { return new Semantics().eval(new VariantAST.Item()); }
                """, 4);
        }
    }

    @Test public void automaticSumSemanticsAreRejectedRatherThanSilentlyIgnored() {
        var grammar = grammar("@root @mapping(Node) @eval(kind='literal', strategy='default') Root ::= Leaf; "
            + "@mapping(Item) Leaf ::= 'a';");
        var error = assertThrows(IllegalArgumentException.class, () -> new EvaluatorGenerator().generate(grammar));
        assertTrue(error.getMessage(), error.getMessage().contains("concrete mapping, not sum Node"));
        assertTrue(error.getMessage(), error.getMessage().contains("annotate its variants"));
    }

    @Test public void dottedAutoSemanticsAndFlattenedNameOverloadsAreLegal() throws Exception {
        for (String kind : List.of("literal", "passthrough")) {
            run(grammar("token NUM=NumberParser @root @mapping(Node.Num, params=[value]) "
                + "@eval(kind='" + kind + "', strategy='default') Root ::= NUM @value;"), """
                public static int run() { return new Semantics().eval(new VariantAST.Node.Num(8)); }
                """, 8);
        }
        run(grammar("@root @mapping(Node.Wrapper, params=[value]) @eval(kind='passthrough', strategy='default') "
            + "Root ::= Leaf @value; @mapping(Node.Item) Leaf ::= 'a';"), """
            @Override protected Integer evalNodeItem(VariantAST.Node.Item node) { return 11; }
            public static int run() { return new Semantics().eval(new VariantAST.Node.Wrapper(new VariantAST.Node.Item())); }
            """, 11);
        run(grammar("@root Root ::= First | Second; @mapping(Node.Item) First ::= 'a'; @mapping(NodeItem) Second ::= 'b';"), """
            @Override protected Integer evalNodeItem(VariantAST.Node.Item node) { return 1; }
            @Override protected Integer evalNodeItem(VariantAST.NodeItem node) { return 2; }
            public static int run() { var s = new Semantics(); return s.eval(new VariantAST.Node.Item()) + s.eval(new VariantAST.NodeItem()); }
            """, 3);
    }

    @Test public void skippedAndEnumRulesDoNotCreateSemanticRequirements() throws Exception {
        run(grammar("@root @mapping(Item) Root ::= 'a'; @skip @mapping(Hidden) Hidden ::= 'x'; "
            + "@enum @mapping(Flag) Flags ::= 'on' | 'off';"), """
            @Override protected Integer evalItem(VariantAST.Item node) { return 5; }
            public static int run() { return new Semantics().eval(new VariantAST.Item()); }
            """, 5);
    }

    /**
     * #311: {@code --java-release 17} cannot use a sealed pattern switch, so a stale instanceof dispatch
     * still compiles; the unknown variant is rejected when it is evaluated. Handwritten semantics are
     * still checked at compile time (abstract methods), on every JDK.
     */
    @Test public void java17StaleDispatchRejectsNewVariantAtRunTime() throws Exception {
        GrammarDecl before = grammar("@root @mapping(Node) Root ::= First; @mapping(Node.A) First ::= 'a';");
        GrammarDecl after = grammar("@root @mapping(Node) Root ::= First | Second; "
            + "@mapping(Node.A) First ::= 'a'; @mapping(Node.B) Second ::= 'b';");
        String source = new EvaluatorGenerator(17).generate(before).source();
        assertFalse(source, source.contains("switch (node)"));
        var stale = compile(after, source, """
            @Override protected Integer evalNodeA(VariantAST.Node.A node) { return 1; }
            public static int run() { return new Semantics().eval(new VariantAST.Node.B()); }
            """);
        assertTrue(stale.diagnostics(), stale.success());
        try (var loader = new URLClassLoader(new URL[]{stale.output().toUri().toURL()}, getClass().getClassLoader())) {
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> loader.loadClass("example.variants.Semantics").getMethod("run").invoke(null));
            assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof IllegalStateException);
            assertTrue(error.getCause().getMessage(), error.getCause().getMessage().startsWith("unhandled node: "));
        }
    }

    @Test public void newVariantBreaksStaleDispatchAndMissingHandwrittenSemantics() throws Exception {
        GrammarDecl before = grammar("@root @mapping(Node) Root ::= First; @mapping(Node.A) First ::= 'a';");
        GrammarDecl after = grammar("@root @mapping(Node) Root ::= First | Second; "
            + "@mapping(Node.A) First ::= 'a'; @mapping(Node.B) Second ::= 'b';");
        String oldMethods = "@Override protected Integer evalNodeA(VariantAST.Node.A node) { return 1; }";
        if (GeneratedJavaRelease.sealedSwitchDispatch()) {
            var stale = compile(after, new EvaluatorGenerator().generate(before).source(), null);
            assertFalse("stale dispatch must be rejected", stale.success());
            assertTrue(stale.diagnostics(), stale.diagnostics().contains("not.exhaustive"));
        }
        var missing = compile(after, GeneratedJavaRelease.evaluatorGenerator().generate(after).source(), oldMethods);
        assertFalse("new semantics must be implemented", missing.success());
        assertTrue(missing.diagnostics(), missing.diagnostics().contains("does not override"));
        assertTrue(missing.diagnostics(), missing.diagnostics().contains("evalNodeB"));
        run(after, oldMethods + """
            @Override protected Integer evalNodeB(VariantAST.Node.B node) { return 2; }
            public static int run() { return new Semantics().eval(new VariantAST.Node.B()); }
            """, 2);
    }
}
