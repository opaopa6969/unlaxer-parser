package org.unlaxer.context;

import java.util.*;

/** Immutable, explicit name classifications. Missing names are unresolved, never assumed values. */
public record NameSnapshot(String id, String version, Map<String, Kind> names) {
    public enum Kind { TYPE, VALUE }
    public record Requirement(String id, String version) {
        public Requirement { validateId(id); validateVersion(version); }
    }
    public NameSnapshot {
        validateId(id); validateVersion(version);
        Objects.requireNonNull(names);
        if (names.size() > 4096) throw new IllegalArgumentException("name snapshot exceeds 4096 names");
        names.forEach((name, kind) -> { validateName(name); Objects.requireNonNull(kind); });
        names = Map.copyOf(names);
    }
    public Optional<Kind> lookup(String name) { return Optional.ofNullable(names.get(name)); }
    public static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_./-]{0,127}"))
            throw new IllegalArgumentException("invalid name snapshot id");
    }
    public static void validateVersion(String version) {
        if (version == null || version.isEmpty() || version.length() > 128
                || version.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("invalid name snapshot version");
    }
    public static void validateName(String name) {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 256
                || name.codePoints().anyMatch(c -> c <= 0x20 || c >= 0x7f && c <= 0x9f || c >= 0xd800 && c <= 0xdfff))
            throw new IllegalArgumentException("invalid snapshot name");
    }
    public static String bindingPrefix(String id) { validateId(id); return "ubnf.name." + id + "."; }
    /** Uses the existing immutable context-local binding transport; no registry or I/O. */
    public static Map<String, List<String>> bindingsOf(List<NameSnapshot> snapshots) {
        if (snapshots.size() > 64) throw new IllegalArgumentException("name snapshot count exceeds 64");
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        int count = 0;
        for (NameSnapshot snapshot : snapshots) {
            if (!ids.add(snapshot.id())) throw new IllegalArgumentException("duplicate name snapshot id");
            if ((count += snapshot.names().size()) > 4096) throw new IllegalArgumentException("name snapshots exceed 4096 names");
            String prefix = bindingPrefix(snapshot.id());
            bindings.put(prefix + "version", List.of(snapshot.version()));
            for (Kind kind : Kind.values()) bindings.put(prefix + kind.name().toLowerCase(Locale.ROOT),
                snapshot.names().entrySet().stream().filter(entry -> entry.getValue() == kind)
                    .map(Map.Entry::getKey).sorted((left, right) -> Arrays.compare(
                        left.codePoints().toArray(), right.codePoints().toArray())).toList());
        }
        return Map.copyOf(bindings);
    }
    /** Validates even bindings supplied through the lower-level public binding API. */
    public static Optional<NameSnapshot> fromContext(ParseContext context, String id) {
        String prefix = bindingPrefix(id);
        List<String> version = context.bindingValues(prefix + "version");
        List<String> types = context.bindingValues(prefix + "type"), values = context.bindingValues(prefix + "value");
        if (version.isEmpty() && types.isEmpty() && values.isEmpty()) return Optional.empty();
        if (version.size() != 1 || types.size() + values.size() > 4096)
            throw new IllegalArgumentException("invalid name snapshot bindings");
        Map<String, Kind> names = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) for (String name : kind == Kind.TYPE ? types : values)
            if (names.putIfAbsent(name, kind) != null) throw new IllegalArgumentException("duplicate snapshot name");
        return Optional.of(new NameSnapshot(id, version.get(0), names));
    }
}
