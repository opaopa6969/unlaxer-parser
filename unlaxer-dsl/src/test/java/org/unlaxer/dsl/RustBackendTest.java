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
        reject(SIMPLE.replace("'hello' @value", "[ 'hello' ] @value"), "OptionalElement");
        reject(SIMPLE.replace("'hello' @value", "{ 'hello' } @value"), "RepeatElement");
        reject(SIMPLE.replace("'hello' @value", "('hello' @value | 'bye')"), "different captures");
        reject(SIMPLE.replace("'hello' @value", "'hello' @value 'bye' @value"), "repeated capture");
        reject(SIMPLE.replace("'hello' @value", "'' @value"), "empty literal");
        reject(SIMPLE.replace("grammar Example {", "grammar Example {\n@whitespace: python"), "whitespace");
        reject(SIMPLE.replace("grammar Example {", "grammar Example {\ntoken TEXT = StringParser"), "only the built-in");
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
