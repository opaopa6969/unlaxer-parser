package org.unlaxer.context;

import org.unlaxer.Name;
import org.unlaxer.listener.DebugTransactionListener;

public class TransactionDebugSpecifier implements ParseContextEffector {

	DebugTransactionListener debugTransactionListener;
	
	public TransactionDebugSpecifier(DebugTransactionListener debugTransactionListener) {
		super();
		this.debugTransactionListener = debugTransactionListener;
	}
	

	@Override
	public void effect(ParseContext parseContext) {
		parseContext.addTransactionListener(
				Name.of(TransactionDebugSpecifier.class),
				debugTransactionListener);
	}
}