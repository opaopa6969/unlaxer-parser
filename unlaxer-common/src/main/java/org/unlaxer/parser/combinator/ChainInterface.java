package org.unlaxer.parser.combinator;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public interface ChainInterface extends Parser{
	
	@Override
	public default Parsed parse(ParseContext parseContext,TokenKind tokenKind,boolean invertMatch) {

		parseContext.getCurrent().setResetMatchedWithConsumed(false);

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		parseContext.begin(this);
		
		Parsers children = getChildren();

		for (Parser parser : children) {
			Parsed parsed = parser.parse(parseContext,tokenKind,invertMatch);

			if(parsed.isStopped()){
				break;
			}
			if (parsed.isFailed()) {
				parseContext.rollback(this);
				parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
				return Parsed.FAILED;
			}
		}
		Parsed committed = new Parsed(parseContext.commit(this,tokenKind));
		parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
		return committed;
	}
}