package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parsers;

public abstract class ConstructedMultiChildParser extends ConstructedAbstractParser implements HasChildrenParser{

	private static final long serialVersionUID = -7780719290508748711L;

	public ConstructedMultiChildParser(Parsers children) {
		super(children);
	}

	public ConstructedMultiChildParser(Name name, Parsers children) {
		super(name, children);
	}

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.multi;
	}
}