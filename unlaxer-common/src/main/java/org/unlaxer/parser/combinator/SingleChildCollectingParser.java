package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.NonTerminallSymbol;
import org.unlaxer.parser.Parser;

public abstract class SingleChildCollectingParser extends ConstructedSingleChildParser implements NonTerminallSymbol{

	private static final long serialVersionUID = -5599677266143767670L;

	public SingleChildCollectingParser(Name name, Parser child) {
		super(name, child);
	}

	public SingleChildCollectingParser(Parser child) {
		super(child);
	}
}