package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class SnapshotFixtureWriterTest {

    @Test
    public void testWritesSnapshotsToCustomDirectory() throws Exception {
        Path out = Files.createTempDirectory("snapshot-writer-test");

        SnapshotFixtureWriter.main(new String[] {"--output-dir", out.toString()});

        assertTrue(Files.exists(out.resolve("ast_snapshot.java.txt")));
        assertTrue(Files.exists(out.resolve("evaluator_snapshot.java.txt")));
        assertTrue(Files.exists(out.resolve("parser_snapshot.java.txt")));
        assertTrue(Files.exists(out.resolve("mapper_snapshot.java.txt")));
        assertTrue(Files.exists(out.resolve("lsp_snapshot.java.txt")));
        assertTrue(Files.exists(out.resolve("dap_snapshot.java.txt")));
        assertTrue(Files.exists(out.resolve("parser_right_assoc_snapshot.java.txt")));
        assertTrue(Files.exists(out.resolve("mapper_right_assoc_snapshot.java.txt")));

        String parser = Files.readString(out.resolve("parser_snapshot.java.txt"));
        assertTrue(parser.contains("class SnapshotParsers"));
    }
}
