package org.unlaxer.dsl.codegen;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;

/** Shared conservative FOLLOW-token heuristic for generated recovery wrappers. */
public final class RecoverySupport {
    private RecoverySupport() {}

    /** Returns the current Java generator's ordered, direct-reference FOLLOW candidates. */
    public static List<String> followTokens(GrammarDecl grammar, String ruleName) {
        Objects.requireNonNull(grammar, "grammar");
        Objects.requireNonNull(ruleName, "ruleName");
        return List.copyOf(ParserRuleEmitter.computeFollowTokens(
            new ParserGenerator.GenContext(grammar), ruleName));
    }

    /** Keeps annotation order, but rejects tokens the runtime cannot use safely. */
    public static List<String> validateSyncTokens(List<String> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        var seen = new HashSet<String>();
        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("recovery sync token must not be empty");
            }
            if (!seen.add(token)) {
                throw new IllegalArgumentException("duplicate recovery sync token: " + token);
            }
        }
        return List.copyOf(tokens);
    }
}
