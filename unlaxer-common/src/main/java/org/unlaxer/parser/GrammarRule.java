package org.unlaxer.parser;

/**
 * Marks the parser of one grammar rule, so that it appears in the rule stack of a failure
 * ({@link org.unlaxer.context.RuleStack}, #703). Generated rule classes implement it; helper
 * parsers a generator adds for a rule body do not.
 *
 * <p>The marker changes no parsing behavior. Rule frames are recorded only with
 * {@code ParseOptions.Diagnostics.DETAILED}.
 */
public interface GrammarRule {

  /**
   * The rule name reported in a rule stack. By default it is the simple class name without a
   * trailing {@code Parser}, which is the rule name for generated classes ({@code Rule + "Parser"}).
   */
  default String grammarRuleName() {
    String name = getClass().getSimpleName();
    if (name.isEmpty()) name = getClass().getName();
    return name.endsWith("Parser") && name.length() > "Parser".length()
        ? name.substring(0, name.length() - "Parser".length())
        : name;
  }
}
