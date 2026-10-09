package org.unlaxer.source;

import java.util.Set;
import java.util.stream.Collectors;
import org.unlaxer.source.LanguageRegions.Operation;

/** Snapshot-bound consumer envelope; edits are already mapped to host code-point spans. */
public record LanguageQueryView(DocumentSnapshot host, Operation operation, int cursor,
                                Set<Operation> capabilities, LanguageQueries.Result result) {
    public LanguageQueryView { host.check(new DocumentSnapshot.Span(cursor, cursor)); capabilities = Set.copyOf(capabilities); }
    public String canonicalJson() {
        String items = result.items().stream().map(item -> {
            String locations = item.locations().stream().map(mapping -> {
                var location = mapping.location();
                return "{\"uri\":" + quote(location.snapshot().uri()) + ",\"version\":" + quote(Long.toString(location.snapshot().version()))
                    + ",\"span\":[" + location.span().start() + "," + location.span().end() + "],\"exact\":" + mapping.exact() + "}";
            }).collect(Collectors.joining(","));
            String edits = item.edits().stream().map(edit -> "{\"span\":[" + edit.span().start() + "," + edit.span().end()
                + "],\"replacement\":" + quote(edit.replacement()) + "}").collect(Collectors.joining(","));
            return "{\"label\":" + quote(item.label()) + ",\"detail\":" + quote(item.detail()) + ",\"locations\":[" + locations + "],\"edits\":[" + edits + "]}";
        }).collect(Collectors.joining(","));
        return "{\"uri\":" + quote(host.uri()) + ",\"version\":" + quote(Long.toString(host.version())) + ",\"operation\":" + quote(operation.name())
            + ",\"cursor\":" + cursor + ",\"region\":" + quote(result.region()) + ",\"state\":" + quote(result.state().name())
            + ",\"capabilities\":[" + capabilities.stream().map(Enum::name).sorted().map(LanguageQueryView::quote).collect(Collectors.joining(","))
            + "],\"items\":[" + items + "]}";
    }
    private static String quote(String text) {
        var result = new StringBuilder("\"");
        text.codePoints().forEach(c -> {
            if (c == '"' || c == '\\') result.append('\\').appendCodePoint(c);
            else if (c == '\n') result.append("\\n");
            else if (c == '\r') result.append("\\r");
            else if (c == '\t') result.append("\\t");
            else if (c < 32) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", c));
            else result.appendCodePoint(c);
        });
        return result.append('"').toString();
    }
}
