package org.unlaxer.parser.elementary;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Parsed;
import org.unlaxer.Parsed.Status;
import org.unlaxer.Source;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.AbstractParser;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class StartOfLineParser extends AbstractParser{
	
	private static final long serialVersionUID = -2867989603696996683L;
	
	static final String LF = new String(new byte[] {0x0a/*lf*/});
	static final String CR = new String(new byte[] {0x0d/*cr*/});
	static final String CRLF = new String(new byte[] {0x0d/*cr*/,0x0a/*lf*/});

	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.none;
	}

	@Override
	public Parser createParser() {
		return this;
	}
	
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		CodePointIndex position = parseContext.getPosition(tokenKind);
		if(position.isZero()) {
			boolean match = (position.isZero()) ^ invertMatch;
			return new Parsed(match ? Status.succeeded : Status.failed);
		}
		if(position.isGreaterThanZero()) {
			Source peekLast = parseContext.peekLast(position, new CodePointLength(1));
			String string = peekLast.toString();
			if(string.equals(CR) || string.equals(LF)) {
				boolean match = true ^ invertMatch;
				return new Parsed(match ? Status.succeeded : Status.failed);
			}
		}
		return new Parsed(Status.failed);
	}

}