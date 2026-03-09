package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleStringParser;

public class DoubleQuoteParser extends SingleStringParser implements StaticParser{

	private static final long serialVersionUID = -6671765861053115237L;

	@Override
	public boolean isMatch(String target) {
		return "\"".equals(target);
	}
}