package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.CollectingParser;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.LazyAbstractParser;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.NonTerminallSymbol;

public abstract class LazyCombinatorParser extends LazyAbstractParser
	implements HasChildrenParser, CollectingParser, NonTerminallSymbol, MetaFunctionParser {

	private static final long serialVersionUID = 3966216675279889282L;

	public LazyCombinatorParser() {
		super();
	}

	public LazyCombinatorParser(Name name) {
		super(name);
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.multi;
	}
}