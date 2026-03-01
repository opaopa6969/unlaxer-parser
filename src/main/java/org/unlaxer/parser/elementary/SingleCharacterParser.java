package org.unlaxer.parser.elementary;

import java.util.Optional;

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
			peeked.isPresent() && (invertMatch ^ isMatch(peeked.charAt(0)))?
				new Token(tokenKind , peeked, this) : 
				Token.empty(tokenKind , parseContext.getCursor(TokenKind.consumed),this);
		return token;
	}

	public abstract boolean isMatch(char target);

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
