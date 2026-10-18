package org.unlaxer.parser.combinator;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.RaisedFailure;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/**
 * Catch labeled failures (#700), scomb's {@code |~}.
 *
 * <p>Evaluate the child. When it fails with a labeled failure whose label is one of
 * {@code labels}, restore the starting state and evaluate the alternative from the same position;
 * its outcome is this parser's outcome. An ordinary failure, a committed error or a labeled
 * failure with another label propagates unchanged without trying the alternative. The operator
 * adds no CST node of its own.
 */
public class CatchLabels extends ConstructedAbstractParser implements MetaFunctionParser {

  private static final long serialVersionUID = 1L;

  private final Set<String> labels;

  public CatchLabels(Parser child, Parser alternative, String... labels) {
    this(child, alternative, List.of(labels));
  }

  public CatchLabels(Parser child, Parser alternative, List<String> labels) {
    super(new Parsers(Objects.requireNonNull(child, "child"),
        Objects.requireNonNull(alternative, "alternative")));
    Objects.requireNonNull(labels, "labels");
    if (labels.isEmpty()) throw new IllegalArgumentException("catch requires at least one label");
    LinkedHashSet<String> checked = new LinkedHashSet<>();
    for (String label : labels) {
      if (false == checked.add(Label.checkedLabel(label))) {
        throw new IllegalArgumentException("duplicate catch label: " + label);
      }
    }
    this.labels = Collections.unmodifiableSet(checked);
  }

  public Set<String> getLabels() {
    return labels;
  }

  @Override
  public ChildOccurs getChildOccurs() {
    return ChildOccurs.multi;
  }

  public Parser getAlternative() {
    return getChildren().get(1);
  }

  @Override
  public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
    parseContext.enterParserCall();
    try {
      parseContext.startParse(this, parseContext, tokenKind, invertMatch);
      parseContext.begin(this);
      Parsed parsed = getChild().parse(parseContext, tokenKind, invertMatch);
      if (parsed.isSucceeded()) {
        Parsed committed = new Parsed(parseContext.commit(this, tokenKind));
        parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
        return committed;
      }
      parseContext.rollback(this);
      RaisedFailure raised = parseContext.raisedFailure();
      if (raised == null || false == raised.isLabel() || false == labels.contains(raised.label())) {
        parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
        return Parsed.FAILED;
      }
      parseContext.clearRaisedFailure();
      parseContext.begin(this);
      parsed = getAlternative().parse(parseContext, tokenKind, invertMatch);
      if (parsed.isSucceeded()) {
        Parsed committed = new Parsed(parseContext.commit(this, tokenKind));
        parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
        return committed;
      }
      parseContext.rollback(this);
      parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
      return Parsed.FAILED;
    } catch (RuntimeException | Error failure) {
      parseContext.failParserCall(failure);
      throw failure;
    } finally {
      parseContext.leaveParserCall();
    }
  }
}
