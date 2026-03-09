package org.unlaxer.parser.combinator;

import java.util.Collections;
import java.util.List;

import org.unlaxer.Name;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class Reverse extends Chain {

	private static final long serialVersionUID = -4962065414105156677L;

	public Reverse(Name name, Parsers children) {
		super(name, reverse(children));
	}

	public Reverse(List<Parser> children) {
		super(reverse(Parsers.of(children)));
	}

	public Reverse() {
		super();
	}

	public Reverse(Name name, Parser... children) {
		super(name, children);
	}

	public Reverse(Name name) {
		super(name);
	}

	public Reverse(Parser... children) {
		super(children);
	}

	static Parsers reverse(Parsers list) {
		Collections.reverse(list);
		return list;
	}
}