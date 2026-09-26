package org.unlaxer.dsl.codegen;

/**
 * Java release used by tests that compile generated sources including an Evaluator (#311).
 *
 * <p>On a Java 21+ test JVM the historical default is exercised: {@code new EvaluatorGenerator()}
 * (exhaustive sealed {@code switch}) compiled with {@code --release 21}. On a Java 17 test JVM the
 * Evaluator is generated for {@code --java-release 17} ({@code instanceof} dispatch) and compiled with
 * {@code --release 17}. Tests that compile only Parser / AST / Mapper / LSP / DAP sources always use
 * {@code --release 17}, so the minimum supported release is checked on every JDK.</p>
 */
final class GeneratedJavaRelease {

    private GeneratedJavaRelease() {}

    /** Release of the running JVM, capped at the release the default generator output targets. */
    static final int EVALUATOR_RELEASE = Math.min(Runtime.version().feature(), 21);

    static final String EVALUATOR_RELEASE_OPTION = Integer.toString(EVALUATOR_RELEASE);

    /** Whether generated Evaluators get compile-time exhaustiveness ({@code not.exhaustive}). */
    static boolean sealedSwitchDispatch() {
        return EVALUATOR_RELEASE >= 21;
    }

    static EvaluatorGenerator evaluatorGenerator() {
        return new EvaluatorGenerator(EVALUATOR_RELEASE);
    }
}
