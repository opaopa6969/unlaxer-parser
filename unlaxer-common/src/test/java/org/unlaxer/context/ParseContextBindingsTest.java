package org.unlaxer.context;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;

public class ParseContextBindingsTest {
    @Test public void snapshotIsDeepReadOnlyAndVisibleBeforeEffectors() {
        var values = new ArrayList<>(List.of("𠮷", "", "𠮷"));
        var bindings = new HashMap<String, List<String>>();
        bindings.put("words", values);
        var source = StringSource.createRootSource("𠮷");
        try (var a = ParseContext.withBindings(source, bindings, ParseOptions.DEFAULT,
                context -> assertEquals(List.of("𠮷", "", "𠮷"), context.bindingValues("words")))) {
            values.clear(); values.add("changed");
            try (var b = ParseContext.withBindings(source, bindings, ParseOptions.DEFAULT);
                    var legacy = new ParseContext(source)) {
                bindings.clear();
                assertEquals(List.of("𠮷", "", "𠮷"), a.bindingValues("words"));
                assertEquals(List.of("changed"), b.bindingValues("words"));
                assertEquals(List.of(), legacy.bindingValues("words"));
                assertEquals(List.of(), a.bindingValues("missing"));
                assertThrows(UnsupportedOperationException.class, () -> a.bindingValues("words").clear());
                assertThrows(UnsupportedOperationException.class, () -> a.bindingValues("missing").add("x"));
            }
        }
    }
}
