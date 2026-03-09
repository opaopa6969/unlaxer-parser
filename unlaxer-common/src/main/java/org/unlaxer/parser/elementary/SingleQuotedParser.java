package org.unlaxer.parser.elementary;

public class SingleQuotedParser extends QuotedParser{

	private static final long serialVersionUID = 5819498054242437000L;
	
	public SingleQuotedParser() {
		super(new SingleQuoteParser());
	}
	
}