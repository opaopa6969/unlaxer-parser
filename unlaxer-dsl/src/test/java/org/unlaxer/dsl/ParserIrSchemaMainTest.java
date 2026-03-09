package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class ParserIrSchemaMainTest {

    @Test
    public void testRunAcceptsValidIrFile() throws Exception {
        Path irFile = writeFixtureToTemp("valid-minimal.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = ParserIrSchemaMain.run(
            new String[] { "--ir", irFile.toString() },
            new PrintStream(out),
            new PrintStream(err)
        );

        assertEquals(ParserIrSchemaMain.EXIT_OK, exit);
        assertTrue(out.toString().contains("OK:"));
        assertTrue(err.toString().isBlank());
    }

    @Test
    public void testRunRejectsInvalidIrFile() throws Exception {
        Path irFile = writeFixtureToTemp("invalid-source-blank.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = ParserIrSchemaMain.run(
            new String[] { "--ir", irFile.toString() },
            new PrintStream(out),
            new PrintStream(err)
        );

        assertEquals(ParserIrSchemaMain.EXIT_VALIDATION_ERROR, exit);
        assertTrue(err.toString().contains("E-PARSER-IR-CONSTRAINT"));
    }

    @Test
    public void testRunRejectsUnknownArgument() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = ParserIrSchemaMain.run(
            new String[] { "--unknown" },
            new PrintStream(out),
            new PrintStream(err)
        );

        assertEquals(ParserIrSchemaMain.EXIT_CLI_ERROR, exit);
        assertTrue(err.toString().contains("Unknown argument"));
    }

    private static Path writeFixtureToTemp(String fixtureName) throws Exception {
        String payload = Files.readString(Path.of("src/test/resources/schema/parser-ir").resolve(fixtureName));
        Path file = Files.createTempFile("parser-ir-", ".json");
        Files.writeString(file, payload);
        file.toFile().deleteOnExit();
        return file;
    }
}
