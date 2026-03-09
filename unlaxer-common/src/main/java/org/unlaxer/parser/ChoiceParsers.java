package org.unlaxer.parser;

import java.util.List;

public class ChoiceParsers extends Parsers{

	private static final long serialVersionUID = -7959572145076832575L;

	public ChoiceParsers(List<Class<? extends Parser>> parsers) {
		super(parsers);
	}

	@SafeVarargs
	public ChoiceParsers(Class<? extends Parser>... parsers) {
		super(parsers);
	}
}