package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;

/** File-level names are portable even on case-insensitive filesystems. */
public final class GrammarFileLayout {
    private GrammarFileLayout() {}

    public record Collision(GrammarDecl grammar, String module, int index) {}

    public static String moduleName(GrammarDecl grammar) {
        if (!validName(grammar)) throw new IllegalArgumentException("P-GRAMMAR-NAME: " + grammar.name());
        return "grammar_" + grammar.name().toLowerCase(Locale.ROOT);
    }

    public static boolean validName(GrammarDecl grammar) {
        return grammar.name().matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /** Linking retains declaration order but may replace declaration identities. */
    public static String position(UBNFSourceSnapshot snapshot, int index) {
        if (snapshot == null || index >= snapshot.ast().grammars().size()) return "";
        return snapshot.spanOf(snapshot.ast().grammars().get(index))
            .map(span -> " [" + span.start() + "," + span.end() + ")").orElse("");
    }

    public static IllegalArgumentException failure(UBNFFile file, int index,
            UBNFSourceSnapshot snapshot, RuntimeException error) {
        if (file.grammars().size() == 1 && error instanceof IllegalArgumentException argument) return argument;
        return new IllegalArgumentException("grammar " + file.grammars().get(index).name()
            + position(snapshot, index) + ": " + error.getMessage(), error);
    }

    public static List<Collision> collisions(UBNFFile file) {
        var seen = new HashSet<String>();
        var result = new ArrayList<Collision>();
        for (int index = 0; index < file.grammars().size(); index++) {
            var grammar = file.grammars().get(index);
            if (!validName(grammar)) continue;
            String module = moduleName(grammar);
            if (!seen.add(module)) result.add(new Collision(grammar, module, index));
        }
        return List.copyOf(result);
    }

    public static void validate(UBNFFile file, UBNFSourceSnapshot snapshot) {
        if (file.grammars().isEmpty()) throw new IllegalArgumentException("expected one or more grammars");
        for (int index = 0; index < file.grammars().size(); index++) {
            var grammar = file.grammars().get(index);
            if (!validName(grammar)) throw new IllegalArgumentException("P-GRAMMAR-NAME"
                + position(snapshot, index) + ": " + grammar.name());
        }
        var collisions = collisions(file);
        if (!collisions.isEmpty()) {
            var collision = collisions.get(0);
            throw new IllegalArgumentException("P-OUTPUT-COLLISION" + position(snapshot, collision.index()) + ": " + collision.module());
        }
    }
}
