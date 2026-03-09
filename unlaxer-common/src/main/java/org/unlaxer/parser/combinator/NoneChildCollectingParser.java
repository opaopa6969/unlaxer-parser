package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.CollectingParser;
import org.unlaxer.parser.NonTerminallSymbol;

public abstract class NoneChildCollectingParser extends NoneChildParser implements CollectingParser , NonTerminallSymbol {

	private static final long serialVersionUID = 1310269977913592620L;

	public NoneChildCollectingParser() {
		super();
	}

	public NoneChildCollectingParser(Name name) {
		super(name);
	}
	
	
}