package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.util.annotation.TokenExtractor;

public interface ChoiceInterface extends Parser{
	
	@Override
	public default Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		List<Parser> children = getChildren();
		
		for (Parser parser : children) {
			parseContext.begin(this);
			Parsed parsed = parser.parse(parseContext, tokenKind, invertMatch);
			
			if (parsed.isSucceeded()) {
				parseContext.commit(this, tokenKind , new ChoiceCommitAction(parser));
				parseContext.endParse(this, parsed , parseContext, tokenKind, invertMatch);
				return parsed;
			}
			parseContext.rollback(this);
		}
		parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
		return Parsed.FAILED;
	}

	@TokenExtractor
	public static Token choiced(Token thisChoiceToken) {
		return thisChoiceToken.getChildFromAstNodes(0);
	}
}