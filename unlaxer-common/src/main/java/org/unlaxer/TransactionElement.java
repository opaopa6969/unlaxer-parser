package org.unlaxer;

import java.io.Serializable;
import java.util.Optional;

import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.Source.SourceKind;



public class TransactionElement implements Serializable{
	
	private static final long serialVersionUID = -4168699143819523755L;

	Optional<TokenKind> tokenKind;
	ParserCursor parserCursor ;
	
	boolean resetMatchedWithConsumed = true;
	
	public final TokenList tokens = new TokenList();
	
	public TransactionElement(ParserCursor parserCursor) {
		super();
		this.parserCursor = new ParserCursor(parserCursor,true);
		tokenKind = Optional.empty();
	}
	
	
	public TransactionElement(ParserCursor cursor, boolean resetMatchedWithConsumed) {
		super();
		this.parserCursor = cursor;
		this.resetMatchedWithConsumed = resetMatchedWithConsumed;
	}


	public TransactionElement createNew() {
		return new TransactionElement(new ParserCursor(parserCursor,resetMatchedWithConsumed),resetMatchedWithConsumed);
	}
	
	public void consume(CodePointLength length){
		parserCursor.addPosition(length.toOffset());
	}
	
	public void matchOnly(CodePointLength length){
		parserCursor.addMatchedPosition(length.toOffset());
	}
	
	public void addToken(Token token){
		addToken(token,TokenKind.consumed);
	}
	
	public void addToken(Token token ,TokenKind tokenKind){
		tokens.add(token);
		this.tokenKind = Optional.of(tokenKind);
	}
	
	public Source source(){
	  return tokens.toSource(SourceKind.detached);
	}
	
	public CodePointIndex getPosition(TokenKind tokenKind){
		return parserCursor.getPosition(tokenKind);
	}
	
	public EndExclusiveCursor getCursor(TokenKind tokenKind){
	   return parserCursor.getCursor(tokenKind);
  }
	
	public TokenList getTokens(){
		return tokens;
	}

	public Optional<TokenKind> getTokenKind() {
		return tokenKind;
	}

	public ParserCursor getParserCursor() {
		return parserCursor;
	}

	public void setCursor(ParserCursor cursor) {
		this.parserCursor = cursor;
	}
	
	public void setResetMatchedWithConsumed(boolean resetMatchedWithConsumed) {
		this.resetMatchedWithConsumed = resetMatchedWithConsumed;
	}
}