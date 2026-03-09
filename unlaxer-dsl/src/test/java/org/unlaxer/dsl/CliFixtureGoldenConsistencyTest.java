package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class CliFixtureGoldenConsistencyTest {

    @Test
    public void testWriterOutputMatchesCommittedCliGoldenFixtures() throws Exception {
        Path out = Files.createTempDirectory("cli-fixture-golden-consistency");
        CliFixtureWriter.main(new String[] {"--output-dir", out.toString()});

        for (String file : CliFixtureData.GOLDEN_FILES) {
            String expected = Files.readString(Path.of("src/test/resources/golden").resolve(file));
            String actual = Files.readString(out.resolve(file));
            assertEquals(normalize(expected), normalize(actual));
        }
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n");
    }
}
