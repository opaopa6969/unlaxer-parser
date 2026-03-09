package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class SnapshotFixtureGoldenConsistencyTest {

    @Test
    public void testWriterOutputMatchesCommittedGoldenFixtures() throws Exception {
        Path out = Files.createTempDirectory("snapshot-golden-consistency");
        SnapshotFixtureWriter.main(new String[] {"--output-dir", out.toString()});

        for (String file : SnapshotFixtureData.GOLDEN_FILES) {
            String expected = Files.readString(Path.of("src/test/resources/golden").resolve(file));
            String actual = Files.readString(out.resolve(file));
            assertEquals(normalize(expected), normalize(actual));
        }
    }

    private String normalize(String s) {
        return s.replace("\r\n", "\n");
    }
}
