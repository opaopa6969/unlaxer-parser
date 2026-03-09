package org.unlaxer;

import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.Source.SourceKind;

public class ParserCursor{
	
	final EndExclusiveCursor consumed;
	final EndExclusiveCursor matched;
	
	public ParserCursor(ParserCursor parserCursor ,boolean resetMatched){
		consumed = new EndExclusiveCursorImpl(parserCursor.consumed);
		matched = new EndExclusiveCursorImpl(parserCursor.matched);
		if(resetMatched){
			resetMatchedWithConsumed(consumed, matched);
		}
	}
	
	public ParserCursor(PositionResolver positionResolver) {
		super();
		consumed = new EndExclusiveCursorImpl(SourceKind.root,positionResolver);
		matched = new EndExclusiveCursorImpl(SourceKind.root,positionResolver);
	}
	

	public ParserCursor(EndExclusiveCursor consumed, EndExclusiveCursor matched , boolean resetMatched) {
		super();
		this.consumed = consumed;
		this.matched = matched;
		if(resetMatched){
			resetMatchedWithConsumed(consumed, matched);
		}
	}

	
	 public void addPosition(CodePointOffset adding){
	   consumed.addPosition(adding);
//	    matched.addPosition(adding);
	   matched.setPosition(consumed.position());
	  }

	
	public void addMatchedPosition(CodePointOffset adding){
		matched.addPosition(adding);
	}
	
	 public void addMatchedPosition(Index adding){
	   addMatchedPosition(adding);
  }
	
	public EndExclusiveCursor getCursor(TokenKind tokenKind){
		return tokenKind == TokenKind.consumed ? consumed : matched;
	}
	
	public CodePointIndex getPosition(TokenKind tokenKind){
		return getCursor(tokenKind).position(); 
	}
	
	void resetMatchedWithConsumed(EndExclusiveCursor consumed, EndExclusiveCursor matched){
		matched.setPosition(consumed.position());
	}
	
}