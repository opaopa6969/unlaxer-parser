package org.unlaxer.parser.elementary;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.ascii.DoubleQuoteParser;

public class DoubleQuotedParser extends QuotedParser implements StaticParser{

	private static final long serialVersionUID = -16069537252033990L;

	public DoubleQuotedParser() {
		super(new DoubleQuoteParser());
	}
	
}