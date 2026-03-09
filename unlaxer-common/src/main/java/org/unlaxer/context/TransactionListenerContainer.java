package org.unlaxer.context;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.unlaxer.Name;
import org.unlaxer.TokenList;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;

public interface TransactionListenerContainer{

	public Map<Name,TransactionListener> getTransactionListenerByName();
	
	public default void addTransactionListener(Name name , TransactionListener transactionListener){
		getTransactionListenerByName().put(name, transactionListener);
	}
	
	public default Set<Entry<Name, TransactionListener>> getTransactionListeners(){
		return getTransactionListenerByName().entrySet();
	}
	
	public default TransactionListener removeTransactionListerner(Name name){
		return getTransactionListenerByName().remove(name);
	}
	
	
	public default void onOpen(ParseContext parseContext){
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onOpen(parseContext));
	}
	
	public default void onBegin(ParseContext parseContext , Parser parser){
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onBegin(parseContext,parser));
	}
	
	public default void onCommit(
			ParseContext parseContext , Parser parser , TokenList committedTokens){
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onCommit(parseContext,parser,committedTokens));
	}
	public default void onRollback(
			ParseContext parseContext , Parser parser , TokenList rollbackedTokens){
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onRollback(parseContext,parser,rollbackedTokens));
	}
	public default void onClose(ParseContext parseContext){
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onClose(parseContext));
	}
}