package org.unlaxer.dsl.tooling;

import static org.junit.Assert.*;
import java.util.HashSet;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.GrammarValidator;

public class AuthoringCatalogTest {
    @Test public void everyLessonUsesAnExecutableFullGrammar() {
        var catalog = com.google.gson.JsonParser.parseString(AuthoringCatalog.json()).getAsJsonObject();
        assertEquals(1, catalog.get("schemaVersion").getAsInt());
        var examples = new HashSet<String>();
        for (var item : catalog.getAsJsonArray("examples")) {
            var example = item.getAsJsonObject();
            assertTrue(examples.add(example.get("id").getAsString()));
            String source = example.get("source").getAsString();
            assertFalse(source.contains("NumberParser"));
            var parsed = UBNFMapper.parse(source);
            assertEquals(1, parsed.grammars().size());
            var issues = GrammarValidator.validate(parsed.grammars().get(0));
            assertFalse(issues.toString(), issues.stream().anyMatch(issue -> "ERROR".equals(issue.severity())));
            assertTrue(example.getAsJsonArray("cases").asList().stream().anyMatch(c -> c.getAsJsonObject().has("fields")));
            assertTrue(example.getAsJsonArray("cases").asList().stream().anyMatch(c -> !c.getAsJsonObject().has("fields")));
        }
        var lessons = new HashSet<String>();
        for (var item : catalog.getAsJsonArray("lessons")) {
            var lesson = item.getAsJsonObject();
            assertTrue(lessons.add(lesson.get("example").getAsString()));
            assertFalse(lesson.getAsJsonArray("steps").isEmpty());
        }
        assertEquals(examples, lessons);
        var ids = new HashSet<String>();
        for (var item : catalog.getAsJsonArray("entries")) assertTrue(ids.add(item.getAsJsonObject().get("id").getAsString()));
    }

    @Test public void keywordsComeFromParsedMetaGrammarNotDocumentationText() {
        var words = AuthoringCatalog.keywords();
        assertTrue(words.containsAll(java.util.List.of("grammar", "token", "CHAR_RANGE", "CAPTURE", "SAME_AS", "ADAPTER", "BOF")));
        assertFalse(words.contains("DIGIT"));
        assertFalse(words.contains("NumberParser"));
        assertFalse(words.contains("Hello"));
    }
}
