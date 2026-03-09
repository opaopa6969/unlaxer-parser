package org.unlaxer.parser;

import java.util.function.Supplier;

public interface LazyParserChildSpecifier extends LazyInstance{
	
	public Supplier<Parser> getLazyParser();
	
	@Override
	public default void prepareChildren(Parsers childrenContainer) {
		
		
		if(childrenContainer.isEmpty()){
			childrenContainer.add(getLazyParser().get());
		}
	}
}