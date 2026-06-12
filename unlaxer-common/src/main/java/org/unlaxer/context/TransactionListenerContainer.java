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
	
	// parser 自身が TransactionListener を実装する場合は、登録済み listener と
	// 同じペイロードで自己通知する (issue #30 指摘3 / #34)。発火元はここに
	// 集約されているため、Chain 以外の parser でも・collect 済みの正しい
	// トークンでも通知が一致する。
	public default void onBegin(ParseContext parseContext , Parser parser){
		if (parser instanceof TransactionListener self) {
			self.onBegin(parseContext, parser);
		}
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onBegin(parseContext,parser));
	}

	public default void onCommit(
			ParseContext parseContext , Parser parser , TokenList committedTokens){
		if (parser instanceof TransactionListener self) {
			self.onCommit(parseContext, parser, committedTokens);
		}
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onCommit(parseContext,parser,committedTokens));
	}
	public default void onRollback(
			ParseContext parseContext , Parser parser , TokenList rollbackedTokens){
		if (parser instanceof TransactionListener self) {
			self.onRollback(parseContext, parser, rollbackedTokens);
		}
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onRollback(parseContext,parser,rollbackedTokens));
	}
	public default void onClose(ParseContext parseContext){
		getTransactionListenerByName().values().stream()
			.forEach(listener->listener.onClose(parseContext));
	}
}