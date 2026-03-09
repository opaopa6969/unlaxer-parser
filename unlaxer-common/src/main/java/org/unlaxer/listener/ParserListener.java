package org.unlaxer.listener;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public interface ParserListener extends BreakPointHolder{

	public void setLevel(OutputLevel level);

	public void onStart(Parser parser , ParseContext parseContext , 
			TokenKind tokenKind , boolean invertMatch);
	
	public void onEnd(Parser parser , Parsed parsed , ParseContext parseContext ,
			TokenKind tokenKind , boolean invertMatch);
	
	/**
	 * set break point on this method if you needs 
	 */
	@BreakPointMethod
	public default void onStartBreakPoint(){}
	
	/**
	 * set break point on this method if you needs 
	 */
	@BreakPointMethod
	public default void onEndBreakPoint(){}
	
	/**
	 * set break point on this method if you needs 
	 */
	@BreakPointMethod
	public default void onUpdateParseBreakPoint(){}

	
	public static class ParseParameters{
		public final Parser parser;
		public final ParseContext parseContext;
		public final TokenKind tokenKind;
		public final boolean invertMatch;
		public ParseParameters(Parser parser, ParseContext parseContext, 
				TokenKind tokenKind, boolean invertMatch) {
			
			super();
			this.parser = parser;
			this.parseContext = parseContext;
			this.tokenKind = tokenKind;
			this.invertMatch = invertMatch;
		}
	}
}