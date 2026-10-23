package org.unlaxer.dsl;

/**
 * Grammars at the nesting boundaries of #740 / #741 (docs/java-parser-limits.md). The Rust
 * twins are in rust/unlaxer-generator/tests/nesting_limits.rs and rust/unlaxer-ubnf/tests/frontend.rs.
 */
public final class NestingLimitGrammars {
    /** Last depth that both hosts lower; one more group exceeds the structural depth of 256. */
    public static final int LOWERED = 121;
    /** Rule body levels of the native frontend ({@code MAX_NESTING}); Java has no such counter. */
    public static final int NATIVE_BODY_LEVELS = 128;
    /** Java frontend: the 1024 parser frames end after this many plain groups ... */
    public static final int JAVA_GROUPS = 168;
    /** ... and after this many groups that each open an alternative. */
    public static final int JAVA_ALTERNATIVES = 126;

    private NestingLimitGrammars() {}

    /** #740: a recursive helper repeated inside {@code depth} groups. */
    public static String recursivePlus(int depth) {
        return "grammar Pbt { @package: pbt.corpus @root @mapping(N0, params=[x]) R0 ::= (H H) @x ;\n"
            + "  H ::= 'n' R0 | 'y' " + "(".repeat(depth) + "H" + ")".repeat(depth) + "+ | 'a' ; }\n";
    }

    /** #740 comment: the recursion runs through the separated operand. */
    public static String recursiveSeparator(int depth) {
        return "grammar Pbt { @package: pbt.corpus @root @mapping(N0, params=[x]) R0 ::= (T T) @x ;\n"
            + "  T ::= 'n' T | 'a' (',' % " + "(".repeat(depth) + "U" + ")".repeat(depth) + ") ; U ::= 'u' T ; }\n";
    }

    /** {@link #recursivePlus} with a numeric capture beside the deep recursive one. */
    public static String recursivePlusWithNumber(int depth) {
        return recursivePlus(depth)
            .replace("@package: pbt.corpus", "@package: pbt.corpus token NUM = org.unlaxer.parser.elementary.NumberParser\n")
            .replace("params=[x]) R0 ::= (H H) @x", "params=[x, n]) R0 ::= NUM @n (H H) @x");
    }

    /** #741: {@code depth} plain groups. */
    public static String groups(int depth) {
        return "grammar Pbt { @package: pbt.corpus @root @mapping(N0, params=[x]) R0 ::= 'x' "
            + "(".repeat(depth) + "'a'" + ")".repeat(depth) + " @x ; }\n";
    }

    /** #741: {@code depth} groups that each open an alternative. */
    public static String alternatives(int depth) {
        return "grammar Pbt { @package: pbt.corpus @root @mapping(N0, params=[x]) R0 ::= 'x' "
            + "('b' | ".repeat(depth) + "'a'" + ")".repeat(depth) + " @x ; }\n";
    }

    /** #741: {@code depth} groups in a declarative token. */
    public static String tokenGroups(int depth) {
        return "grammar Pbt { @package: pbt.corpus @ubnf: v2\n token T ::= " + "(".repeat(depth) + "'a'"
            + ")".repeat(depth) + " ;\n @root @mapping(N0, params=[x]) R0 ::= 'x' T @x ; }\n";
    }
}
