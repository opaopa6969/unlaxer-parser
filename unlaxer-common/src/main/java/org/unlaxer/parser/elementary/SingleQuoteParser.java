package org.unlaxer.parser.elementary;

import org.unlaxer.parser.StaticParser;

public class SingleQuoteParser extends SingleStringParser implements StaticParser{

	private static final long serialVersionUID = -2018076837810041764L;

	@Override
	public boolean isMatch(String target) {
		return "'".equals(target);
	}
}