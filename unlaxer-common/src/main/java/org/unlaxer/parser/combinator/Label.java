package org.unlaxer.parser.combinator;

import java.util.Objects;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.RaisedFailure;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;

/**
 * Labeled failure (#700): turn the child's ordinary failure into a failure labeled {@code label}.
 *
 * <p>The failure is raised at the code point where this operator started; its message is the
 * label. Ordinary choice, optional, repetition, lookahead, not and recovery do not catch it;
 * only a {@link CatchLabels} naming the label does. A labeled or committed failure from the
 * child passes through unchanged (the innermost label wins). The operator adds no CST node,
 * transaction or diagnostic frame of its own.
 */
public class Label extends ConstructedSingleChildParser implements MetaFunctionParser,
    org.unlaxer.parser.HasChildrenParser {

  private static final long serialVersionUID = 1L;

  private final String label;

  public Label(String label, Parser child) {
    super(Objects.requireNonNull(child, "child"));
    this.label = checkedLabel(label);
  }

  public Label(Name name, String label, Parser child) {
    super(name, Objects.requireNonNull(child, "child"));
    this.label = checkedLabel(label);
  }

  static String checkedLabel(String label) {
    Objects.requireNonNull(label, "label");
    if (label.isEmpty()) throw new IllegalArgumentException("failure label must not be empty");
    return label;
  }

  public String getLabel() {
    return label;
  }

  @Override
  public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
    parseContext.enterParserCall();
    try {
      parseContext.clearRaisedFailure();
      int offset = parseContext.getPosition(tokenKind).value();
      Parsed parsed = getChild().parse(parseContext, tokenKind, invertMatch);
      if (parsed.isFailed() && false == parseContext.hasRaisedFailure()) {
        parseContext.raise(RaisedFailure.label(label, offset));
      }
      return parsed;
    } catch (RuntimeException | Error failure) {
      parseContext.failParserCall(failure);
      throw failure;
    } finally {
      parseContext.leaveParserCall();
    }
  }

  /** Traversable by parser-graph analyses such as DiagnosticsSafety. */
  @Override
  public org.unlaxer.parser.HasChildrenParser createWith(org.unlaxer.parser.Parsers children) {
    return new Label(label, children.get(0));
  }
}
