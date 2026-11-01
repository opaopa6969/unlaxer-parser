package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

public class JavaParseDepthLimitTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void generatedRecursionStopsBeforeTheJvmStackInColdInterpretedProcess() throws Exception {
        Path classes = temporary.newFolder("classes").toPath();
        for (String name : List.of("Deep", "Wrapped")) {
            String child = name.equals("Deep") ? "Root" : "(".repeat(8) + "Root" + ")".repeat(8);
            var grammar = UBNFMapper.parse("grammar " + name
                + " { @package: audit @whitespace: none @root Root ::= '(' " + child + " ')' | 'x'; }")
                .grammars().get(0);
            var generated = new ParserGenerator().generate(grammar);
            Path source = classes.resolve(name + "Parsers.java");
            Files.writeString(source, generated.source());
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()));
        }
        Path log = temporary.newFile("probe.log").toPath();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = org.unlaxer.dsl.TestProcesses.builder(java, "-Xss1m", "-Xint", "-classpath",
            classes + File.pathSeparator + System.getProperty("java.class.path"), Probe.class.getName())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("recursion probe timeout", process.waitFor(60, TimeUnit.SECONDS));
            assertEquals(Files.readString(log), 0, process.exitValue());
            assertTrue(Files.readString(log), Files.readString(log).contains("controlled recursion cases=8"));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            int cases = 0;
            for (String name : List.of("Deep", "Wrapped")) {
                Parser parser = (Parser) Class.forName("audit." + name + "Parsers").getMethod("getRootParser").invoke(null);
                for (var diagnostic : new ParseOptions.Diagnostics[]{ParseOptions.Diagnostics.DETAILED,
                        ParseOptions.Diagnostics.DETAILED_ON_FAILURE}) {
                    for (var memo : Memoization.values()) {
                        var options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostic);
                        ParseLimitException failure = null;
                        try (var context = ParseContext.withOptions(StringSource.createRootSource("(".repeat(10_000) + "x" + ")".repeat(10_000)), options)) {
                            try { parser.parse(context); }
                            catch (ParseLimitException expected) { failure = expected; }
                            if (failure == null || failure.limit() != ParseContext.MAX_PARSE_DEPTH
                                    || failure.offset() < 1 || failure.offset() >= 10_000) throw new AssertionError("missing resource failure");
                        }
                        if (failure.getSuppressed().length != 0) throw new AssertionError("secondary close failure");
                        try (var fresh = ParseContext.withOptions(StringSource.createRootSource("(".repeat(64) + "x" + ")".repeat(64)), options)) {
                            if (!parser.parse(fresh).isSucceeded() || !fresh.allConsumed()) throw new AssertionError("fresh parse failed");
                        }
                        cases++;
                    }
                }
            }
            System.out.println("controlled recursion cases=" + cases);
        }
    }
}
