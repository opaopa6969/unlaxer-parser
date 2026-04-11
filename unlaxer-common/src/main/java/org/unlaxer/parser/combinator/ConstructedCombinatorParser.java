package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.CollectingParser;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.NonTerminallSymbol;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public abstract class ConstructedCombinatorParser extends ConstructedAbstractParser
	implements HasChildrenParser, CollectingParser, NonTerminallSymbol, MetaFunctionParser {

	private static final long serialVersionUID = -517554162836750441L;

	public ConstructedCombinatorParser(Parsers children) {
		super(children);
	}

	public ConstructedCombinatorParser(Parser... children) {
		super(new Parsers(children));
	}

	@SafeVarargs
	public ConstructedCombinatorParser(Class<? extends Parser>... children) {
		super(new Parsers(children));
	}

	public ConstructedCombinatorParser(Name name, Parsers children){
		super(name, children);
	}

	public ConstructedCombinatorParser(Name name, Parser... children) {
		super(name, new Parsers(children));
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.multi;
	}
}
