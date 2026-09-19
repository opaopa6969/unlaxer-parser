package org.unlaxer.dsl;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

public class RustBackendTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SIMPLE = """
        grammar Example {
          @root
          @mapping(Value, params=[value])
          Root ::= 'hello' @value ;
        }
        """;

    @Test public void generationIsDeterministicAndExhaustive() {
        var grammar = UBNFMapper.parse(SIMPLE).grammars().get(0);
        var files = new RustBackend().generate(grammar);
        assertEquals(files, new RustBackend().generate(grammar));
        assertEquals(5, files.size());
        assertTrue(files.get(1).content().contains("r#Value { span: Span, r#value: String }"));
        assertTrue(files.get(4).content().contains("fn eval_value(&mut self, r#value: &str, span: Span) -> Self::Output;"));
        assertFalse(files.get(4).content().contains("_ =>"));
    }

    @Test public void unsupportedFeaturesAreRejectedBeforeEmission() {
        reject(SIMPLE.replace("'hello' @value", "Missing @value"), "unknown reference");
        reject(SIMPLE.replace("'hello' @value", "Root @value"), "left recursion");
        reject(SIMPLE.replace("@root", "@root\n@root"), "multiple @root");
        reject(SIMPLE.replace("value", "span"), "reserved");
        reject(SIMPLE.replace("value", "semantics"), "reserved");
        reject(SIMPLE.replace("'hello' @value", "[ 'x' ] Root @value"), "left recursion");
        reject(SIMPLE.replace("'hello' @value", "{ [ 'hello' ] } 'x' @value"), "nullable unbounded");
        reject(SIMPLE.replace("'hello' @value", "[ [ 'hello' ] ] @value"), "nested container capture");
        reject(SIMPLE.replace("'hello' @value", "{ Maybe } @value").replace("Root ::=", "Maybe ::= [ 'x' ];\nRoot ::="), "nullable unbounded");
        reject(SIMPLE.replace("'hello' @value", "'' @value"), "empty literal");
        reject(SIMPLE.replace("grammar Example {", "grammar Example {\n@whitespace: python"), "whitespace");
        reject(SIMPLE.replace("grammar Example {", "grammar Example {\ntoken TEXT = StringParser"), "external token");
    }

    @Test public void zeroWidthTokensCannotCreateUnboundedLoopsOrLeftRecursion() {
        for (String declaration : new String[]{"EMPTY", "EOF", "LOOKAHEAD('x')", "NEGATIVE_LOOKAHEAD('x')", "UNTIL('#')"}) {
            String source = SIMPLE.replace("grammar Example {", "grammar Example { token T = " + declaration + "\n");
            reject(source.replace("'hello' @value", "{ T } 'hello' @value"), "nullable unbounded");
            reject(source.replace("'hello' @value", "T @value Root @value"), "left recursion");
        }
        reject(SIMPLE.replace("grammar Example {", "grammar Example { token T = REGEX('x')\n"), "token T");
        reject(SIMPLE.replace("'hello' @value", "Root{0} @value"), "left recursion");
    }

    @Test public void invalidUnicodeTextIsRejectedBeforeRustEmission() {
        var grammar = UBNFMapper.parse(SIMPLE).grammars().get(0);
        String invalid = String.valueOf((char) 0xd800);
        for (var token : java.util.List.of(
            new org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Until("T", invalid),
            new org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Negation("T", invalid),
            new org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Lookahead("T", invalid))) {
            var modified = new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl(
                grammar.name(), grammar.settings(), java.util.List.of(token), grammar.rules());
            var error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(modified));
            assertTrue(error.toString(), error.getMessage().contains("unpaired surrogate"));
        }
    }

    @Test public void cardinalityReachesAstAndSemantics() {
        for (String expression : new String[]{"[ 'hello' ] @value", "[ 'hello' @value ]", "('hello' @value | 'bye')"}) {
            var files = new RustBackend().generate(UBNFMapper.parse(SIMPLE.replace("'hello' @value", expression)).grammars().get(0));
            assertTrue(expression, files.get(1).content().contains("r#value: Option<String>"));
            assertTrue(expression, files.get(4).content().contains("r#value: Option<&str>"));
        }
        for (String expression : new String[]{"{ 'hello' } @value", "{ 'hello' @value }", "'hello'+ @value",
            "'hello'{1,2} @value", "'hello' % ',' @value", "'hello' @value 'bye' @value"}) {
            var files = new RustBackend().generate(UBNFMapper.parse(SIMPLE.replace("'hello' @value", expression)).grammars().get(0));
            assertTrue(expression, files.get(1).content().contains("r#value: Vec<String>"));
            assertTrue(expression, files.get(4).content().contains("r#value: &[String]"));
        }
    }

    private void reject(String source, String reason) {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar));
        assertTrue(error.getMessage(), error.getMessage().contains(reason));
    }

    @Test public void cliChecksDriftAndProtectsHandwrittenFiles() throws Exception {
        Path grammar = temporary.newFile("sample.ubnf").toPath();
        Files.writeString(grammar, SIMPLE);
        Path output = temporary.getRoot().toPath().resolve("generated");
        String[] command = {"generate", "--target", "rust", "--grammar", grammar.toString(), "--output", output.toString()};
        assertEquals(0, run(command));
        String[] check = java.util.Arrays.copyOf(command, command.length + 1);
        check[command.length] = "--check";
        assertEquals(0, run(check));
        Files.writeString(output.resolve("evaluator.rs"), "// handwritten\n");
        assertEquals(4, run(check));
        String ast = Files.readString(output.resolve("ast.rs"));
        assertEquals(4, run(command));
        assertEquals(ast, Files.readString(output.resolve("ast.rs")));
        assertEquals("// handwritten\n", Files.readString(output.resolve("evaluator.rs")));
        Files.writeString(grammar, SIMPLE.replace("'hello' @value", "Missing @value"));
        assertEquals(3, run(command));
        assertEquals(2, run("generate", "--target", "java"));
    }

    private int run(String... args) {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            return CodegenMain.run(args, stream, stream);
        }
    }
}
