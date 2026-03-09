package org.unlaxer.parser;

import java.util.List;

public class ChainParsers extends Parsers{

	private static final long serialVersionUID = -8565527389285738230L;

	public ChainParsers(List<Class<? extends Parser>> parsers) {
		super(parsers);
	}

	@SafeVarargs
	public ChainParsers(Class<? extends Parser>... parsers) {
		super(parsers);
	}

	public ChainParsers(Parser... parsers) {
		super(parsers);
	}
}