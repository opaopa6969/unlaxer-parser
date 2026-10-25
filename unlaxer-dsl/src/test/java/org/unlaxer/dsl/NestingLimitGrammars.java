package org.unlaxer.dsl;

/**
 * Grammars at the nesting boundaries of #740 / #741 (docs/java-parser-limits.md). The Rust
 * twins are in rust/unlaxer-generator/tests/nesting_limits.rs and rust/unlaxer-ubnf/tests/frontend.rs.
 */
public final class NestingLimitGrammars {
    /** Last depth that both hosts lower; one more group exceeds the structural depth of 256. */
    public static final int LOWERED = 122;
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

    /**
     * #740 review: {@code depth} wrappers around the recursive helper, which the Java host accepted
     * before #740 (62 levels; native rejected them). {@code open} ends with the wrapped operand's start.
     */
    public static String recursiveWrapped(int depth, String open, String close) {
        return "grammar G { @root @mapping(N,params=[x]) R0 ::= (H H) @x; H ::= 'n' R0 | 'y' "
            + open.repeat(depth) + "H" + close.repeat(depth) + " | 'a'; }\n";
    }

    /** #740 review: a chain of {@code length} rule references before the recursive helper. */
    public static String referenceChain(int length) {
        var source = new StringBuilder("grammar G { @root @mapping(R, params=[x]) R0 ::= H0 @x;\n");
        for (int i = 0; i < length; i++) source.append("H").append(i).append(" ::= 'z' H").append(i + 1).append(";\n");
        return source.append("H").append(length).append(" ::= 'n' R0 | 'y' H").append(length).append(" | 'a'; }\n").toString();
    }

    /** The review's grammars that the Java host accepted before #740 and must keep accepting. */
    public static java.util.Map<String, String> acceptedBefore740() {
        var cases = new java.util.LinkedHashMap<String, String>();
        cases.put("optional-62", recursiveWrapped(62, "[", "]"));
        cases.put("repeat-62", recursiveWrapped(62, "{", "}"));
        cases.put("capture-62", recursiveWrapped(62, "CAPTURE(v, ", ")"));
        cases.put("capture-scope-62", recursiveWrapped(62, "CAPTURE_SCOPE(", ")"));
        cases.put("reference-chain-82", referenceChain(82));
        return cases;
    }

    /**
     * Deepest analysis both hosts accept: the fixed point starts at the first revisit after
     * {@code length} references and solves a recursive rule nested in {@code depth} groups.
     */
    public static String chainBesideNestedRecursion(int length, int depth) {
        var source = new StringBuilder("grammar G {\n@root @mapping(N, params=[x]) R0 ::= (H0 H0) @x ;\n");
        for (int i = 0; i < length; i++) source.append("H").append(i).append(" ::= 'z' H").append(i + 1).append(" ;\n");
        return source.append("H").append(length).append(" ::= 'n' H0 | 'a' ;\nD ::= 'n' R0 | 'y' ")
            .append("(".repeat(depth)).append("D").append(")".repeat(depth)).append("+ | 'a' ;\n}\n").toString();
    }

    /** Exceeds the work, not the depth, of the Java AST type inference beside a numeric capture. */
    public static String numericBesideAnalysisWork(int fillers) {
        return besideAnalysisWork(" @root @mapping(R,params=[n,x]) R0 ::= NUM @n T @x;", fillers);
    }

    /** #758: the same work beside {@code @typeof} on a published ({@code m}) and an unpublished ({@code k}) number. */
    public static String typeofBesideAnalysisWork(int fillers) {
        return besideAnalysisWork(" @root @mapping(R,params=[n,m,x]) R0 ::= NUM @n '=' @typeof(n) NUM @m"
            + " ',' @typeof(n) NUM @k T @x;", fillers);
    }

    private static String besideAnalysisWork(String root, int fillers) {
        var source = new StringBuilder("grammar G { token NUM = org.unlaxer.parser.elementary.NumberParser\n"
            + root + " T ::= 'n' T | 'a' M; @mapping(M) M ::= 'm';\n");
        for (int i = 0; i < 80; i++) source.append("C").append(i).append(" ::= ").append(i == 79 ? "M" : "C" + (i + 1)).append(";\n");
        for (int i = 0; i < fillers; i++) source.append("F").append(i).append(" ::= ").append("'a' ".repeat(20)).append(";\n");
        return source.append("}\n").toString();
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
