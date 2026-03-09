package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.CollectingParser;
import org.unlaxer.parser.NonTerminallSymbol;
import org.unlaxer.parser.Parsers;

public abstract class ConstructedMultiChildCollectingParser extends ConstructedMultiChildParser implements CollectingParser , NonTerminallSymbol{
	
	private static final long serialVersionUID = 3746117207729959189L;
	
	public ConstructedMultiChildCollectingParser(Parsers children) {
		super(children);
	}

	public ConstructedMultiChildCollectingParser(Name name, Parsers children) {
		super(name, children);
	}
}