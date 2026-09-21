package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.CodePointIndex;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.PackratMemoTable;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.NonTerminallSymbol;
import org.unlaxer.parser.Parser;

public interface Occurs extends MetaFunctionParser , NonTerminallSymbol {
  
//  InfiniteLoopDetector infiniteLoopDetector = new InfiniteLoopDetector();
  
	@Override
	public default Parsed parse(ParseContext parseContext,TokenKind tokenKind,boolean invertMatch) {
	  
        // Generated whitespace delimitors use Occurs directly, bypassing AbstractParser.
        var memo = PackratMemoTable.lookup(parseContext, this, tokenKind, invertMatch);
        if (memo != null) return PackratMemoTable.replay(parseContext, this, tokenKind, invertMatch, memo);
        var memoDiagnostic = PackratMemoTable.beginFailure(parseContext, this);
        var memoStart = PackratMemoTable.successKey(parseContext, this, tokenKind, invertMatch, memoDiagnostic);

		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		
		parseContext.begin(this);
		int matchCount = 0;
		Optional<Parser> terminator = getTerminator();
		while (true) {
		  
//		  infiniteLoopDetector.incrementsAndThrow(1000);
		  
		  CodePointIndex startPosition = parseContext.getPosition(tokenKind);
			
			if(terminator.isPresent()){
				parseContext.begin(this);
				Parser terminatorParser = terminator.get();
				Parsed terminatorParsed = terminatorParser.parse(parseContext);
				
				if(terminatorParsed.isSucceeded() && tokenKind.isConsumed()){
					parseContext.commit(terminatorParser,terminatorParser.getTokenKind());
				}else{
					parseContext.rollback(this);
				}
				if(terminatorParsed.isSucceeded()){
					break;
				}
			}
			
			Parser child = getChild();
      Parsed parsed = child.parse(parseContext,tokenKind,invertMatch);
			
			if (parsed.isFailed() ||parsed.isStopped() ){
				break;
			}
			
			matchCount++;
			if (startPosition.eq(parseContext.getPosition(tokenKind))) {
				break;
			}
			
			
			if (matchCount >= max()) {
				break;
			}
		}
		
		if (matchCount >= min() && matchCount <=max()) {
			
            return PackratMemoTable.commitSuccess(parseContext, this, tokenKind, invertMatch,
                memoStart, memoDiagnostic, null, null);
		} else {
			
			parseContext.rollback(this);
			parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);
            PackratMemoTable.memoizeFailure(parseContext, this, tokenKind, invertMatch, memoDiagnostic);
			return Parsed.FAILED;
		}
	}
	
	public int min();
	
	public int max();
	
	public Optional<Parser> getTerminator();

}