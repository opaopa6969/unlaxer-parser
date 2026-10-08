package org.unlaxer.dsl;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlaygroundCommandTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path grammar(String source) throws Exception {
        Path path = temporary.newFile().toPath(); Files.writeString(path, source); return path;
    }
    private static final String VALID = "grammar Demo { @root @mapping(Value, params=[text]) @doc('hello と書く') Start ::= 'hello' @text; }";

    @Test public void newProjectAndCheckAreDeterministicAndNeverOverwrite() throws Exception {
        Path input = grammar(VALID), output = temporary.getRoot().toPath().resolve("project");
        var first = CodegenTestHelper.runCodegen("playground", "--grammar", input.toString(), "--output", output.toString());
        assertEquals(first.err(), 0, first.exitCode());
        assertTrue(Files.exists(output.resolve("public/help/catalog.json")));
        assertTrue(Files.readString(output.resolve("src/lib.rs")).contains("generated::parser::RULE_DOCS"));
        var repeat = CodegenTestHelper.runCodegen("playground", "--grammar", input.toString(), "--output", output.toString());
        assertEquals(4, repeat.exitCode());
        var check = CodegenTestHelper.runCodegen("playground", "--grammar", input.toString(), "--output", output.toString(), "--check");
        assertEquals(check.err(), 0, check.exitCode());
        Files.writeString(output.resolve("public/index.html"), "handwritten");
        check = CodegenTestHelper.runCodegen("playground", "--grammar", input.toString(), "--output", output.toString(), "--check");
        assertEquals(4, check.exitCode());
        assertEquals("handwritten", Files.readString(output.resolve("public/index.html")));
    }

    @Test public void unsupportedHostBindingAndInvalidOptionsCreateNothing() throws Exception {
        Path input = grammar("grammar Host { token NUMBER = org.unlaxer.parser.elementary.NumberParser @root @mapping(Value, params=[text]) Start ::= NUMBER @text; }");
        Path output = temporary.getRoot().toPath().resolve("forbidden");
        var run = CodegenTestHelper.runCodegen("playground", "--grammar", input.toString(), "--output", output.toString());
        assertEquals(run.err(), 3, run.exitCode());
        assertTrue(run.err().contains("declarative"));
        assertFalse(Files.exists(output));
        assertEquals(2, CodegenTestHelper.runCodegen("playground", "--unknown", "x").exitCode());
        assertEquals(2, CodegenTestHelper.runCodegen("playground", "--grammar", "").exitCode());
        assertEquals(2, CodegenTestHelper.runCodegen("playground", "--check", "--check").exitCode());
        assertEquals(0, CodegenTestHelper.runCodegen("playground", "--help").exitCode());
    }

    @Test public void symlinkAncestorsAreRejectedBeforeAnyWrite() throws Exception {
        Path input = grammar(VALID), real = temporary.newFolder().toPath();
        Path link = temporary.getRoot().toPath().resolve("link"); Files.createSymbolicLink(link, real);
        var run = CodegenTestHelper.runCodegen("playground", "--grammar", input.toString(), "--output", link.resolve("project").toString());
        assertEquals(run.err(), 4, run.exitCode());
        try (var entries = Files.list(real)) { assertEquals(0, entries.count()); }
    }
}
