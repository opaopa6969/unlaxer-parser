package org.unlaxer.source;

import static org.junit.Assert.*;
import java.nio.file.*;
import org.junit.Test;

public class LanguageProfileSelectionTest {
    @Test public void sharedSelectionAndCapabilityFixtures() throws Exception {
        Path repository = Path.of("..");
        String source = Files.readString(repository.resolve("language-profiles/java/profile.tsv"));
        for (String row : Files.readAllLines(repository.resolve("docs/fixtures/language-profiles/selection.tsv"))) {
            String[] fields = row.split("\t");
            String changed = fields[1].equals("-") ? source : source.replace(fields[1].replace("\\t", "\t"), fields[2].replace("\\t", "\t"));
            var profile = LanguageProfile.parse(changed);
            if (!Boolean.parseBoolean(fields[5])) {
                assertThrows(fields[0], IllegalArgumentException.class, () -> profile.select(fields[3], fields[4])); continue;
            }
            var selection = profile.select(fields[3], fields[4]);
            assertEquals(fields[0], Boolean.parseBoolean(fields[6]), selection.allowsLocal("COMPLETION"));
            assertEquals(fields[0], Boolean.parseBoolean(fields[7]), selection.allowsLocal("DEFINITION"));
            assertEquals(fields[0], Boolean.parseBoolean(fields[8]), selection.allows("COMPLETION", true));
            assertEquals(fields[0], Boolean.parseBoolean(fields[9]), selection.allows("DEFINITION", true));
            assertTrue(selection.allowsLocal("PARSE")); assertFalse(selection.allowsLocal("UNKNOWN"));
            assertEquals("lang/java", selection.language().packageId());
            assertEquals("0.1.0", selection.language().version());
        }
    }
}
