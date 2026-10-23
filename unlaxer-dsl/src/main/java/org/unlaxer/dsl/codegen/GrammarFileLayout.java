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

    public record Collision(GrammarDecl grammar, String module) {}

    public static String moduleName(GrammarDecl grammar) {
        if (!grammar.name().matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("invalid grammar name: " + grammar.name());
        return "grammar_" + grammar.name().toLowerCase(Locale.ROOT);
    }

    public static List<Collision> collisions(UBNFFile file) {
        var seen = new HashSet<String>();
        var result = new ArrayList<Collision>();
        for (var grammar : file.grammars()) {
            String module = moduleName(grammar);
            if (!seen.add(module)) result.add(new Collision(grammar, module));
        }
        return List.copyOf(result);
    }

    public static void validate(UBNFFile file, UBNFSourceSnapshot snapshot) {
        if (file.grammars().isEmpty()) throw new IllegalArgumentException("expected one or more grammars");
        var collisions = collisions(file);
        if (!collisions.isEmpty()) {
            var collision = collisions.get(0);
            String position = snapshot == null ? "" : snapshot.spanOf(collision.grammar())
                .map(span -> " [" + span.start() + "," + span.end() + ")").orElse("");
            throw new IllegalArgumentException("P-OUTPUT-COLLISION" + position + ": " + collision.module());
        }
    }
}
