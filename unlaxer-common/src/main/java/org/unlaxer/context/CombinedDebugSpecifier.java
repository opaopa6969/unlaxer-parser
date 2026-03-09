package org.unlaxer.context;

import org.unlaxer.Name;
import org.unlaxer.listener.CombinedDebugListener;

public class CombinedDebugSpecifier implements ParseContextEffector {

	CombinedDebugListener combinedDebugListener;
	
	public CombinedDebugSpecifier(CombinedDebugListener combinedDebugListener) {
		super();
		this.combinedDebugListener = combinedDebugListener;
	}
	

	@Override
	public void effect(ParseContext parseContext) {
		parseContext.addParserListener(
				Name.of(ParserDebugSpecifier.class),
				combinedDebugListener);
		parseContext.addTransactionListener(
				Name.of(TransactionDebugSpecifier.class),
				combinedDebugListener);
	}
}