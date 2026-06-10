package org.unlaxer.parser.elementary;

import java.util.Optional;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Name;
import org.unlaxer.Source;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;

public abstract class SingleCharacterParser extends AbstractTokenParser
    implements TerminalSymbol {

	private static final long serialVersionUID = -4800259064123105938L;

	public SingleCharacterParser() {
		super();
	}

	public SingleCharacterParser(Name name) {
		super(name);
	}

	@Override
	public Token getToken(ParseContext parseContext,TokenKind tokenKind , boolean invertMatch) {

		Source peeked = parseContext.peek(tokenKind , new CodePointLength(1));
		Token token =
			peeked.isPresent() && (invertMatch ^ isMatch(peeked.codePointAt(new CodePointIndex(0)).value()))?
				new Token(tokenKind , peeked, this) :
				Token.empty(tokenKind , parseContext.getCursor(TokenKind.consumed),this);
		return token;
	}

	/**
	 * Returns true if the given Unicode code point should be matched.
	 *
	 * <p>Subclasses that only handle BMP characters may override {@link #isMatch(char)} instead;
	 * this default implementation delegates to {@code isMatch((char) codePoint)} for code points
	 * that fit in a {@code char}, and returns {@code false} for supplementary code points
	 * (U+10000 and above) so that existing subclasses remain correct without modification.
	 *
	 * @param codePoint the Unicode code point to test
	 * @return {@code true} if the code point matches
	 */
	public boolean isMatch(int codePoint) {
		if (Character.isBmpCodePoint(codePoint)) {
			return isMatch((char) codePoint);
		}
		return false;
	}

	/**
	 * Returns true if the given BMP character should be matched.
	 *
	 * @deprecated Override {@link #isMatch(int)} to support supplementary Unicode code points.
	 *             This method is kept for backward compatibility with existing subclasses.
	 */
	@Deprecated
	public boolean isMatch(char target) {
		return false;
	}

  @Override
  public Optional<String> expectedDisplayText() {
    Character matched = null;
    for (char c = 32; c < 127; c++) {
      if (isMatch(c)) {
        if (matched != null) {
          return Optional.empty();
        }
        matched = c;
      }
    }
    if (matched == null) {
      return Optional.empty();
    }
    return Optional.of("'" + escape(matched.charValue()) + "'");
  }

  private String escape(char c) {
    if (c == '\'') {
      return "\\'";
    }
    if (c == '\\') {
      return "\\\\";
    }
    return String.valueOf(c);
  }

}
