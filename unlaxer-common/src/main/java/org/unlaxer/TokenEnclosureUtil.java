package org.unlaxer;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.unlaxer.parser.ParsersSpecifier;

public class TokenEnclosureUtil{
	
	public static Optional<Token> getEnclosureWithToken(
			Token baseToken,
			EnclosureDirection direction ,
			CodePointIndex position,
			Optional<Token> currentToken,
			ParsersSpecifier parserSpecifier){
		return getEnclosureWithRange(baseToken , direction , position , currentToken.map(x->x.getSource().cursorRange()), parserSpecifier);
	}

	
	public static Optional<Token> getEnclosureWithRange(
			Token baseToken,
			EnclosureDirection direction ,
			CodePointIndex position,
			Optional<CursorRange> currentRange,
			ParsersSpecifier parserSepcifier){
		
		List<Token> collect = baseToken.flatten().stream()
			.filter(parserSepcifier::contains)
			.filter(token-> token.getSource().cursorRange().match(position))
			.filter(token-> {
				return currentRange.map(
					range->range.relation(token.getSource().cursorRange()) == rangeRelation(direction))
					.orElse(token.getSource().cursorRange().match(position));
			})
			.collect(Collectors.toList());
		return collect.isEmpty() ? Optional.empty() : 
				Optional.of(direction.isInner() ? collect.get(0) : collect.get(collect.size()-1));
	}
	
	static RangesRelation rangeRelation(EnclosureDirection enclosureDirection){
		return enclosureDirection.isInner()?
				RangesRelation.inner :
				RangesRelation.outer;
	}

	
}