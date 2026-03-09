package org.unlaxer.parser;

import java.util.Optional;

import org.unlaxer.ParserFinder;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;

public interface PropagatableDestination extends ParserFinder{
	
	
	/**
	 * @return invertedMatch value is method argument when 
	 * {@link Parser#parse(ParseContext, TokenKind, boolean)} 
	 * invoked after (3rd argument is invertMatch).
	 * if this method called before 
	 * {@link Parser#parse(ParseContext, TokenKind, boolean)}, 
	 * throw {@link IllegalStateException}
	 */
	public default boolean getInvertMatchFromParent() throws IllegalStateException {
		
		Optional<Parser> source = findFirstToParent(targetParser->{
			return targetParser instanceof PropagatableSource;
		});
		
		return source
			.map(PropagatableSource.class::cast)
			.map(PropagatableSource::getInvertMatchToChild)
			.orElse(false);
	}

}