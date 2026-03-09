package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.CollectingParser;
import org.unlaxer.parser.NonTerminallSymbol;

public abstract class LazyMultiChildCollectingParser extends LazyMultiChildParser implements CollectingParser , NonTerminallSymbol{

	private static final long serialVersionUID = -2709224484413268403L;

	public LazyMultiChildCollectingParser() {
		super();
	}

	public LazyMultiChildCollectingParser(Name name) {
		super(name);
	}
}