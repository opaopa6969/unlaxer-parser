package org.unlaxer.source;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Language;
import org.unlaxer.source.LanguageRegions.State;

public class EmbeddedLanguagesTest {
    private final DocumentSnapshot source = new DocumentSnapshot("host", 1, "abc");
    private final Language language = new Language("x", "example", "1", "G", "Document");
    private EmbeddedLanguages.Grammar provider(String mode) {
        return new EmbeddedLanguages.Grammar() {
            @Override public String name() { return mode.equals("wrong-name") ? "Other" : "G"; }
            @Override public EmbeddedLanguages.Parsed parse(String entry, DocumentSnapshot snapshot) {
                if (mode.equals("stale")) return new EmbeddedLanguages.Parsed(new DocumentSnapshot(snapshot.uri(), 2, snapshot.text()), State.COMPLETE, List.of());
                var child = new EmbeddedLanguages.Child(language, new Span(0, 3), new Span(0, 3));
                return new EmbeddedLanguages.Parsed(snapshot, mode.equals("failed-children") ? State.FAILED : mode.equals("partial") ? State.PARTIAL : State.COMPLETE,
                    mode.equals("cycle") || mode.equals("failed-children") ? List.of(child) : mode.equals("siblings") ? List.of(child, child) : List.of());
            }
        };
    }
    @Test public void rejectStaleIdentityOverlappingAndUnboundedProviderResults() {
        for (String mode : List.of("stale", "wrong-name", "cycle", "failed-children", "siblings")) {
            assertThrows(mode, IllegalArgumentException.class, () -> EmbeddedLanguages.parse(source, language, Map.of(language, provider(mode)), 3, 16));
        }
        assertThrows(IllegalArgumentException.class, () -> EmbeddedLanguages.parse(source, language, Map.of(), 0, 16));
    }
    @Test public void partialAndUnavailableStatesAndExactRegistryIdentityAreRetained() {
        assertEquals(State.PARTIAL, EmbeddedLanguages.parse(source, language, Map.of(language, provider("partial")), 3, 16).regions().get(0).parseState());
        var wrongVersion = new Language("x", "example", "2", "G", "Document");
        assertEquals(State.UNAVAILABLE, EmbeddedLanguages.parse(source, language, Map.of(wrongVersion, provider("complete")), 3, 16).regions().get(0).parseState());
        assertEquals(State.COMPLETE, EmbeddedLanguages.parse(new DocumentSnapshot("host", 1, ""), language, Map.of(language, provider("complete")), 3, 16).regions().get(0).parseState());
    }
}
