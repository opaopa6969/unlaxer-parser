package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.MetaFunctionParser;

public abstract class LazyCombinatorParser extends LazyMultiChildCollectingParser 
	implements MetaFunctionParser  {

	private static final long serialVersionUID = 3966216675279889282L;

	public LazyCombinatorParser() {
		super();
	}

	public LazyCombinatorParser(Name name) {
		super(name);
	}
}