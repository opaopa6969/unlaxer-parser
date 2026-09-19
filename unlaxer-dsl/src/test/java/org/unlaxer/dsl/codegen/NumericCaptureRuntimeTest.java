package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

/** Real javac and execution coverage for direct numeric captures (#115). */
public class NumericCaptureRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void scalarKeepsIntApiAndRejectsNonIntegerSpellingsAndOverflow() throws Exception {
        for (String parser : List.of("NumberParser", "org.unlaxer.parser.elementary.NumberParser")) {
            try (URLClassLoader loader = compile(parser, "NUMBER @value")) {
                assertEquals(int.class, loader.loadClass("numeric.NumericAST$Item")
                    .getRecordComponents()[0].getType());
                for (var element : corpus()) {
                    var row = element.getAsJsonObject();
                    String text = row.get("input").getAsString();
                    // Valid NumberParser syntax is not necessarily valid for the legacy int AST API.
                    Class<?> mapper = loader.loadClass("numeric.NumericMapper");
                    assertEquals(Optional.empty(), mapper.getMethod("diagnose", String.class)
                        .invoke(null, "n" + text + ";"));
                    if (row.get("javaInt").isJsonNull()) {
                        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                            () -> value(loader, "n" + text + ";"));
                        assertTrue(text + ": " + failure.getCause(), failure.getCause() instanceof NumberFormatException);
                    } else {
                        assertEquals(row.get("javaInt").getAsInt(), value(loader, "n" + text + ";"));
                    }
                }
            }
        }
    }

    @Test public void rustRetainsLexemeForTheSameNumericBoundaryCorpus() throws Exception {
        org.junit.Assume.assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        Path directory = temporary.newFolder().toPath();
        Path library = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            Path.of("../rust/unlaxer-runtime/src/lib.rs").toAbsolutePath().toString(), "-o", library.toString()), "");
        Path generated = Files.createDirectory(directory.resolve("generated"));
        var grammar = grammar("NumberParser", "NUMBER @value");
        for (var file : new RustBackend().generate(grammar)) {
            Files.writeString(generated.resolve(file.relativePath()), file.content());
        }
        Files.writeString(directory.resolve("main.rs"), """
            mod generated;
            use std::io::{self, BufRead};
            fn main() {
                for line in io::stdin().lock().lines() {
                    let source = line.unwrap();
                    let tree = generated::parser::parse_tree(&source).unwrap();
                    let ast = generated::mapper::map(&tree).unwrap();
                    println!("{}", ast.canonical_json());
                }
            }
            """);
        Path binary = directory.resolve("probe");
        run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
            directory.resolve("main.rs").toString(), "-o", binary.toString()), "");
        var corpus = corpus();
        String input = String.join("\n", corpus.asList().stream()
            .map(row -> "n" + row.getAsJsonObject().get("input").getAsString() + ";").toList()) + "\n";
        var output = run(List.of(binary.toString()), input).lines().toList();
        assertEquals(corpus.size(), output.size());
        for (int i = 0; i < corpus.size(); i++) {
            assertEquals(corpus.get(i).getAsJsonObject().get("input"), JsonParser.parseString(output.get(i))
                .getAsJsonObject().getAsJsonObject("fields").get("value"));
        }
    }

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/test/resources/numeric-capture/conformance.json")))
            .getAsJsonArray();
    }

    private String run(List<String> command, String input) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(command.toString(), process.waitFor(45, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(output, 0, process.exitValue());
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test public void optionalAndRepeatedNumbersAreBoxedAndActuallyMapped() throws Exception {
        for (String parser : List.of("NumberParser", "org.unlaxer.parser.elementary.NumberParser")) {
            for (String body : List.of("[ NUMBER @value ]", "[ NUMBER ] @value")) {
                try (URLClassLoader loader = compile(parser, body)) {
                    assertEquals(Optional.empty(), value(loader, "n;"));
                    assertEquals(Optional.of(-12), value(loader, "n-12;"));
                    assertThrows(InvocationTargetException.class, () -> value(loader, "n1.5;"));
                }
            }
            for (String body : List.of("{ NUMBER @value }", "{ NUMBER } @value")) {
                try (URLClassLoader loader = compile(parser, body)) {
                    assertEquals(List.of(), value(loader, "n;"));
                    assertEquals(List.of(1, -2, 3), value(loader, "n1-2+3;"));
                    assertThrows(InvocationTargetException.class, () -> value(loader, "n1+1e2;"));
                }
            }
        }
    }

    @Test public void digitTokenSupportsPrimitiveAndBoxedCaptures() throws Exception {
        for (String parser : List.of("DigitParser", "org.unlaxer.parser.posix.DigitParser")) {
            try (URLClassLoader loader = compile(parser, "NUMBER @value")) {
                assertEquals(7, value(loader, "n7;"));
            }
            try (URLClassLoader loader = compile(parser, "{ NUMBER @value }")) {
                assertEquals(List.of(7, 3), value(loader, "n73;"));
            }
        }
    }

    @Test public void missingRequiredNumericCaptureDoesNotSilentlyBecomeZero() throws Exception {
        try (URLClassLoader loader = compile("NumberParser", "( NUMBER @value | 'absent' )")) {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> value(loader, "nabsent;"));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals("Required numeric capture not found: value", failure.getCause().getMessage());
        }
    }

    private Object value(URLClassLoader loader, String input) throws Exception {
        Object ast = loader.loadClass("numeric.NumericMapper").getMethod("parse", String.class).invoke(null, input);
        return ast.getClass().getMethod("value").invoke(ast);
    }

    private org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar(String parser, String body) {
        return UBNFMapper.parse("""
            grammar Numeric {
              @package: numeric
              @whitespace: javaStyle
              token NUMBER = %s
              @root @mapping(Item, params=[value])
              Item ::= 'n' %s ';' ;
            }
            """.formatted(parser, body)).grammars().get(0);
    }

    private URLClassLoader compile(String parser, String body) throws Exception {
        var grammar = grammar(parser, body);
        var generated = List.of(new ASTGenerator().generate(grammar), new ParserGenerator().generate(grammar),
            new MapperGenerator().generate(grammar), new EvaluatorGenerator().generate(grammar));
        List<JavaFileObject> sources = generated.stream().<JavaFileObject>map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/" + source.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source.source(); }
            }).toList();
        Path output = temporary.newFolder().toPath();
        var compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter diagnostics = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            boolean success = compiler.getTask(diagnostics, manager, null,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-d", output.toString()), null, sources).call();
            assertTrue(parser + " " + body + "\n" + diagnostics, success);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }
}
