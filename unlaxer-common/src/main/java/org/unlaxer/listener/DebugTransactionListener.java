package org.unlaxer.listener;

import java.io.Closeable;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.TokenPrinter;
import org.unlaxer.TransactionElement;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParserContextPrinter;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ParserPrinter;

public class DebugTransactionListener implements TransactionListener , LogOutputCountListener , Closeable{
	
	PrintStream print;
	OutputLevel level;
	
	int count;
	LogOutputCountListener listener;
	
	Set<Integer> targets;
	
	public DebugTransactionListener() {
		this(System.out , OutputLevel.simple );
	}
	
	public DebugTransactionListener(PrintStream out, OutputLevel outputLevel) {
		this(out, outputLevel, LogOutputCountListener.BlackHole , new HashSet<>());
	}
	
	public DebugTransactionListener(//
			PrintStream print , //
			OutputLevel level , //
			LogOutputCountListener listener , //
			Set<Integer> breakPointTargets) {
		super();
		this.print = print;
		this.level = level;
		this.listener = listener;
		count = 0;
		targets = breakPointTargets;
	}
	
	@Override
	public void onBegin(ParseContext parseContext, Parser parser) {
		if(level.isNone()){
			return ;
		}
		print.format("BEGIN   : %s \t| %s\n", getDisplay(parseContext) , getDisplay(parser));
		onOutput(++count);
		if(doTrigger()){
			//set Break point here or this method declares!
			onUpdateTransactionBreakPoint();
			onBeginBreakPoint();
		}
	}

	@Override
	public void onCommit(ParseContext parseContext, Parser parser, TokenList committedTokens) {
		if(level.isNone()){
			return ;
		}
		print.format("COMMIT  : %s \t| %s | %s\n", 
				getDisplay(parseContext) , getDisplay(parser),getDisplay("committed",committedTokens));
		onOutput(++count);
		if(doTrigger()){
			//set Break point here or this method declares!
			onUpdateTransactionBreakPoint();
			onCommitBreakPoint();
		}
	}

	@Override
	public void onRollback(ParseContext parseContext, Parser parser , TokenList rollbackedTokens) {
		if(level.isNone()){
			return ;
		}
		print.format("ROLLBACK: %s \t| %s | %s\n", 
				getDisplay(parseContext) , getDisplay(parser),getDisplay("rollbacked", rollbackedTokens));
		onOutput(++count);
		if(doTrigger()){
			//set Break point here or this method declares!
			onUpdateTransactionBreakPoint();
			onRollbackBreakPoint();
		}
	}

	@Override
	public void onOpen(ParseContext parseContext) {
		if(level.isNone()){
			return ;
		}
		print.format("OPEN    : '%s'\n", 
				parseContext.source.peek(new CodePointIndex(0), parseContext.source.codePointLength()).toString());
		onOutput(++count);
		if(doTrigger()){
			//set Break point here or this method declares!
			onUpdateTransactionBreakPoint();
			onOpenBreakPoint();
		}
	}

	@Override
	public void onClose(ParseContext parseContext) {
		if(level.isNone()){
			return ;
		}
		print.format("CLOSE   : '%s' consumed:%s \n\n", 
			parseContext.source.peek(new CodePointIndex(0), parseContext.source.codePointLength()).toString(),
			getConsumed(parseContext)
		);
		onOutput(++count);
		if(doTrigger()){
			//set Break point here or this method declares!
			onUpdateTransactionBreakPoint();
			onCloseBreakPoint();
		}
	}
	
	private Object getConsumed(ParseContext parseContext) {
		TransactionElement transactionElement = parseContext.getCurrent();
		CodePointIndex consumed = transactionElement.getPosition(TokenKind.consumed);
		CodePointLength remain = parseContext.source.codePointLength().newWithMinus(consumed);
		return parseContext.allConsumed() ? "allConsumed" : 
			String.format("%d(%d remain)", consumed.value() , remain.value());
	}

	String getDisplay(ParseContext parseContext){
		return ParserContextPrinter.get(parseContext, level);
	}
	
	String getDisplay(Parser parser){
		return ParserPrinter.get(parser, level);
	}
	
	String getDisplay(String header , TokenList tokens){
		return TokenPrinter.get(header, tokens);
	}
	
	@Override
	public void close() {
		print.close();
	}

	@Override
	public void setLevel(OutputLevel level) {
		this.level = level;
	}

	@Override
	public void onOutput(int count) {
		listener.onOutput(count);
	}
	
	public boolean doTrigger(){
		return targets.contains(count);
	}
}