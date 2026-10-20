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
 * Cut (#700): turn the child's ordinary failure into a committed error.
 *
 * <p>The error is raised at the code point where this operator started, with this operator's
 * message. Choice, optional, repetition, lookahead and not do not catch it; only a
 * {@link SyncPointRecoveryParser} boundary or the outermost parse does. A labeled or committed
 * failure from the child passes through unchanged. The operator adds no CST node, transaction
 * or diagnostic frame of its own, so a successful child is observationally unchanged.
 */
public class Commit extends ConstructedSingleChildParser implements MetaFunctionParser {

  private static final long serialVersionUID = 1L;

  private final String message;

  public Commit(Parser child) {
    this(child, RaisedFailure.DEFAULT_COMMIT_MESSAGE);
  }

  public Commit(Parser child, String message) {
    super(Objects.requireNonNull(child, "child"));
    this.message = checkedMessage(message);
  }

  public Commit(Name name, Parser child, String message) {
    super(name, Objects.requireNonNull(child, "child"));
    this.message = checkedMessage(message);
  }

  private static String checkedMessage(String message) {
    Objects.requireNonNull(message, "message");
    if (message.isEmpty()) throw new IllegalArgumentException("commit message must not be empty");
    return message;
  }

  public String getMessage() {
    return message;
  }

  @Override
  public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
    parseContext.enterParserCall();
    try {
      // Any raised failure still pending here is stale: this is a new attempt.
      parseContext.clearRaisedFailure();
      int offset = parseContext.getPosition(tokenKind).value();
      Parsed parsed = getChild().parse(parseContext, tokenKind, invertMatch);
      if (parsed.isFailed() && false == parseContext.hasRaisedFailure()) {
        parseContext.raise(RaisedFailure.commit(message, offset));
      }
      return parsed;
    } catch (RuntimeException | Error failure) {
      parseContext.failParserCall(failure);
      throw failure;
    } finally {
      parseContext.leaveParserCall();
    }
  }
}
