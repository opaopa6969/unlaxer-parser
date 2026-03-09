package org.unlaxer.parser;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public interface HasChildrenParser extends Parser{
	
	public Parsers getChildren();
	
	public default HasChildrenParser newFiltered(Predicate<Parser> cutFilter){
		
		Predicate<Parser> passFilter = cutFilter.negate();
		List<Parser> newChildren = getChildren().stream()
			.filter(passFilter)
			.collect(Collectors.toList());
		
		return createWith(Parsers.of(newChildren));
	}
	
	public default HasChildrenParser newWithoutRecursive(Predicate<Parser> cutFilter){
		
		List<Parser> newChildren = getChildren().stream()
			.map(childParser->newWithRecursiveChild(childParser, cutFilter))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.collect(Collectors.toList());
		
		return createWith(Parsers.of(newChildren));
	}
	
	default Optional<Parser> newWithRecursiveChild(Parser target , Predicate<Parser> cutFilter){
		if(cutFilter.test(target)){
			return Optional.empty();
		}
		if(target instanceof HasChildrenParser){
			HasChildrenParser hasChildParser = ((HasChildrenParser) target);
			@SuppressWarnings("unchecked")
			List<Parser> children = (List<Parser>) hasChildParser.getChildren().stream()
				.map(childParser->{
					if(cutFilter.test(target)){
						return Optional.empty();
					}
					if(childParser instanceof HasChildrenParser){
						return Optional.of(((HasChildrenParser)childParser).newWithoutRecursive(cutFilter));
					}
					return Optional.of(target);
				})
				.filter(Optional::isPresent)
				.map(Optional::get)
				.collect(Collectors.toList());
			return Optional.of(createWith(Parsers.of(children)));
		}
		return Optional.of(target);
	}

	
	HasChildrenParser createWith(Parsers children);

}