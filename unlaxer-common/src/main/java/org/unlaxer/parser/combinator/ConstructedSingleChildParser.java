package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.HasChildParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public abstract class ConstructedSingleChildParser extends ConstructedAbstractParser implements HasChildParser {

	private static final long serialVersionUID = 2620160704955343801L;
	
	public ConstructedSingleChildParser(Class<? extends Parser> child) {
		super(new Parsers(child));
	}
	
	public ConstructedSingleChildParser(Parser child) {
		super(new Parsers(child));
	}

	public ConstructedSingleChildParser(Name name, Class<? extends Parser> child) {
		super(name, new Parsers(child));
	}
	
	public ConstructedSingleChildParser(Name name, Parser child) {
		super(name, new Parsers(child));
	}


	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.single;
	}

	@Override
	public Parser getChild() {
		return getChildren().get(0);
	}

	@Override
	public Parser createParser() {
		return this;
	}
	
}