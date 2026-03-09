package org.unlaxer.context;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.ParserCursor;
import org.unlaxer.Source;
import org.unlaxer.TokenKind;
import org.unlaxer.listener.OutputLevel;

public class ParserContextPrinter {

	public static String get(ParseContext parseContext , OutputLevel level){
		
		CodePointIndex position = parseContext.getPosition(TokenKind.consumed);
		if(level.isMostDetail()) {
			ParserCursor parserCursor = parseContext.getCurrent().getParserCursor();
			EndExclusiveCursor consumed= parserCursor.getCursor(TokenKind.consumed);
			EndExclusiveCursor matchOnly= parserCursor.getCursor(TokenKind.matchOnly);
			Source peek = parseContext.peekLast(position, new CodePointLength(20));
			
			return String.format("CON(L:%d,P:%d) MO(L:%d,P:%d) Last20='%s' ", 
					consumed.lineNumber().value(),
//					consumed.getPositionInLine(), // きちんと実装されてない
					consumed.position().value(),
					matchOnly.lineNumber().value(),
//					matchOnly.getPositionInLine(),
					matchOnly.position().value(),
					normalize(peek.toString()));
		}
		
		CodePointIndex matchOnlyPosition = parseContext.getPosition(TokenKind.matchOnly);
		Source peek = parseContext.peek(position,new CodePointLength(1));
		return String.format("position:(c:%d m:%d) targetchar='%s' ", 
				position.value(),
				matchOnlyPosition.value(),
				normalize(peek.toString()));
	}
	
	static String normalize(String word){
		if("\r".equals(word)){
			return "\\r";
		}
		if("\n".equals(word)){
			return "\\n";
		}
		return word;
	}

}
