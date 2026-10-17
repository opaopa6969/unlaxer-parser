package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;

/** Existing Java int contracts, including the effective schema of shared mappings. */
public final class NumericFields {
    private NumericFields() {}

    public static boolean isInteger(GrammarDecl grammar, RuleDecl rule, String name) {
        String type = new ASTGenerator().inferType(grammar, rule, name);
        return type.equals("int")
                || type.equals("Optional<Integer>")
                || type.equals("List<Integer>");
    }
}
