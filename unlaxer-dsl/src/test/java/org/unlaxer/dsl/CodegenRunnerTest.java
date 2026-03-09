package org.unlaxer.dsl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class CodegenRunnerTest {

    @Test
    public void testHasErrorRowsDetectsNonWarningSeverity() {
        var rows = List.of(
            new ReportJsonWriter.ValidationIssueRow("G", "R", "E-X", "ERROR", "GENERAL", "m", "h")
        );
        assertTrue(CodegenRunner.hasErrorRows(rows));
        assertFalse(CodegenRunner.hasWarningRows(rows));
    }

    @Test
    public void testHasWarningRowsDetectsWarningSeverity() {
        var rows = List.of(
            new ReportJsonWriter.ValidationIssueRow("G", "R", "W-X", "WARNING", "GENERAL", "m", "h")
        );
        assertFalse(CodegenRunner.hasErrorRows(rows));
        assertTrue(CodegenRunner.hasWarningRows(rows));
    }

    @Test
    public void testExecuteUsesInjectedFileSystemPort() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        RecordingFs fs = new RecordingFs();
        fs.files.put("g.ubnf", source);

        CodegenCliParser.CliOptions config = new CodegenCliParser.CliOptions(
            "g.ubnf",
            null,
            List.of("Parser"),
            true,
            false,
            false,
            false,
            false,
            false,
            "json",
            null,
            null,
            null,
            null,
            "json",
            1,
            false,
            false,
            "always",
            "conflict",
            -1
        );
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenRunner.execute(
            config,
            new PrintStream(out),
            new PrintStream(err),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            "test",
            "hash",
            fs
        );

        assertEquals(CodegenMain.EXIT_OK, exitCode);
        assertTrue(out.toString().contains("\"mode\":\"validate\""));
        assertTrue(out.toString().contains("\"generatedAt\":\"2026-01-01T00:00:00Z\""));
        assertTrue(out.toString().contains("\"warningsCount\":0"));
        assertTrue(fs.createdDirs.isEmpty());
        assertTrue(fs.writes.isEmpty());
    }

    private static final class RecordingFs implements CodegenRunner.FileSystemPort {
        private final Map<String, String> files = new HashMap<>();
        private final List<String> createdDirs = new java.util.ArrayList<>();
        private final Map<String, String> writes = new HashMap<>();

        @Override
        public String readString(Path path) {
            String value = files.get(path.toString());
            if (value == null) {
                throw new IllegalArgumentException("missing file: " + path);
            }
            return value;
        }

        @Override
        public boolean exists(Path path) {
            return files.containsKey(path.toString()) || writes.containsKey(path.toString());
        }

        @Override
        public boolean deleteIfExists(Path path) {
            boolean removedFile = files.remove(path.toString()) != null;
            boolean removedWrite = writes.remove(path.toString()) != null;
            return removedFile || removedWrite;
        }

        @Override
        public void createDirectories(Path path) {
            createdDirs.add(path.toString());
        }

        @Override
        public void writeString(Path path, String content) {
            writes.put(path.toString(), content);
            files.put(path.toString(), content);
        }
    }
}
