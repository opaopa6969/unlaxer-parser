package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

/** The stronger success proof must exclude listeners throughout recursive dependency closures. */
public class JavaSafeSuccessMemoizationGenerationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String generate(String declarations) {
        return new ParserGenerator().generate(UBNFMapper.parse("""
            grammar Success {
              @package: org.example.success
              @whitespace: javaStyle
              %s
            }
            """.formatted(declarations)).grammars().get(0)).source();
    }

    private static void marker(String source, String name, boolean expected) {
        String declaration = source.lines().filter(line -> line.contains("class " + name + " "))
            .findFirst().orElseThrow(() -> new AssertionError(name + " missing: " + source.lines().filter(line -> line.contains("class ")).toList()));
        assertEquals(declaration, expected, declaration.contains("SafeSuccessMemoizable"));
    }

    @Test public void listenerRulesTaintAncestorsAndHelpersButOrdinaryCapturesRemainPure() {
        String source = generate("""
            @root Root ::= Scoped | Declaration | Reference | Pure ;
            @scopeTree(mode=lexical) Scoped ::= 's' ;
            @declares(symbol=name) Declaration ::= ('d' 'e') @name ;
            @backref(name=name) Reference ::= 'r' @name ;
            Pure ::= 'x' @value ;
            CycleA ::= CycleB | 'a' ;
            CycleB ::= CycleA | Reference ;
            Wrapped ::= [ (Scoped 'x') ] ;
            """);
        for (String name : List.of("RootParser", "ScopedParser", "DeclarationParser", "ReferenceParser",
                "CycleAParser", "CycleBParser", "WrappedParser", "WrappedOpt0Parser",
                "WrappedGroup0Parser")) marker(source, name, false);
        marker(source, "PureParser", true);
        marker(source, "DeclarationGroup0Parser", true); // immutable capture metadata; listener stays on Declaration
        marker(source, "__CaptureSite", false); // generic wrapper may contain any unsafe child
        marker(source, "SuccessSpaceDelimitor", true);
    }

    @Test public void backrefWithoutScopeTreeStillEmitsAnUnsafeListener() {
        String source = generate("""
            @root Root ::= Reference ;
            @backref(name=name) Reference ::= 'r' @name ;
            """);
        marker(source, "ReferenceParser", false);
        marker(source, "RootParser", false);
        assertTrue(source.contains("org.unlaxer.listener.TransactionListener"));
    }

    @Test public void unknownCustomMatchedAndUserStateTokensAreFailClosedTransitively() {
        String source = generate("""
            token CUSTOM = IdentifierParser
            token MATCHED = org.unlaxer.parser.referencer.MatchedTokenParser
            token STATE = example.UserStateParser
            @root Root ::= Custom | Matched | State ;
            Custom ::= CUSTOM ;
            Matched ::= MATCHED ;
            State ::= STATE ;
            Ancestor ::= [ Root 'x' ] ;
            """);
        for (String name : List.of("RootParser", "CustomParser", "MatchedParser", "StateParser",
                "AncestorParser", "AncestorOpt0Parser")) marker(source, name, false);
        marker(source, "SuccessSpaceDelimitor", true);
    }

    @Test public void certifiedTokensAndPureRecursiveHelpersReceiveMarkers() {
        String source = generate("""
            @memoSafeToken: ID
            token ID = org.unlaxer.parser.clang.IdentifierParser
            @root Root ::= Pure ;
            Pure ::= '(' [ Pure 'x' ] ')' | ID ;
            ListRule ::= ('x' 'y') % ',' ;
            """);
        for (String name : List.of("RootParser", "PureParser", "PureOpt0Parser",
                "ListRuleParser", "ListRuleGroup0Parser", "ListRuleSep0BodyParser", "ListRuleSep0Parser")) {
            marker(source, name, true);
        }
    }

    @Test public void generatedRuntimeReplaysWhitespaceAndPreservesMappedAstAndDiagnostics() throws Exception {
        var grammar = UBNFMapper.parse("""
            grammar Replay {
              @package: org.example.replay
              @whitespace: javaStyle
              @root @mapping(Value, params=[value]) Root ::= (Item '!') @value | (Item '?') @value ;
              @mapping(Item, params=[text]) Item ::= 'a' @text ;
            }
            """).grammars().get(0);
        var sources = List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar),
            new MapperGenerator().generate(grammar));
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
                URI.create("string:///org/example/replay/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, units).call());
        }
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var parsers = loader.loadClass("org.example.replay.ReplayParsers");
            Parser root = (Parser) parsers.getMethod("getRootParser").invoke(null);
            var mapper = loader.loadClass("org.example.replay.ReplayMapper");
            var parse = mapper.getMethod("parseWithOptions", String.class, ParseOptions.class);
            var diagnose = mapper.getMethod("diagnose", String.class, ParseOptions.class);
            var on = ParseOptions.withMemoization(Memoization.SAFE_FAILURES);
            for (String input : List.of("a?", " /*x*/ a //y\n ?", "a!")) {
                assertEquals(parse.invoke(null, input, ParseOptions.DEFAULT), parse.invoke(null, input, on));
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input), on)) {
                    assertTrue(root.parse(context).isSucceeded());
                    assertTrue(context.allConsumed());
                    assertTrue("the generated grammar must exercise success replay", context.getPackratMemoTable().successHits() > 0);
                }
            }
            for (String input : List.of("a#", "a", " /*x*/ a #")) {
                assertEquals(diagnose.invoke(null, input, ParseOptions.DEFAULT), diagnose.invoke(null, input, on));
            }
        }
    }
}
