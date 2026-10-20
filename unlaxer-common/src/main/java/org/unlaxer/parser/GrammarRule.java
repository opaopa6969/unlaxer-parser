package org.unlaxer.parser;

/**
 * Marks the parser of one grammar rule, so that it appears in the rule stack of a failure
 * ({@link org.unlaxer.context.RuleStack}, #703). Generated rule classes implement it and override
 * {@link #grammarRuleName()} with the rule's name; helper parsers a generator adds for a rule body
 * do not implement it.
 *
 * <p>The marker changes no parsing behavior. Rule frames are recorded only with
 * {@code ParseOptions.Diagnostics.DETAILED}.
 */
public interface GrammarRule {

  /**
   * The rule name reported in a rule stack. By default it is the simple class name without a
   * trailing {@code Parser}; generated classes return the rule name itself, so subclasses keep it.
   */
  default String grammarRuleName() {
    return defaultName(getClass());
  }

  /**
   * The reported name of {@code parser}: its {@link #grammarRuleName()}, or the class-derived
   * default when that is null or blank, so a misbehaving override never loses a frame.
   */
  static String nameOf(Object parser) {
    String name = parser instanceof GrammarRule rule ? rule.grammarRuleName() : null;
    return name == null || name.isBlank() ? defaultName(parser.getClass()) : name;
  }

  private static String defaultName(Class<?> type) {
    String name = type.getSimpleName();
    if (name.isEmpty()) name = type.getName();
    return name.endsWith("Parser") && name.length() > "Parser".length()
        ? name.substring(0, name.length() - "Parser".length())
        : name;
  }
}
