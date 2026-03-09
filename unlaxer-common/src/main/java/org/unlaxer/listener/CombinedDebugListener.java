package org.unlaxer.listener;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.util.MultipleIOException;

public class CombinedDebugListener implements 
	ParserListener , TransactionListener ,  LogOutputCountListener , Closeable  
	{

	public final DebugParserListener debugParserListener;
	public final DebugTransactionListener debugTransactionListener;
	int count;
	
	Set<Integer> targets;

	
	public CombinedDebugListener(DebugParserListener debugParserListener,
			DebugTransactionListener debugTransactionListener,
			Set<Integer> targets) {
		super();
		this.debugParserListener = debugParserListener;
		this.debugTransactionListener = debugTransactionListener;
		count=0;
		this.targets = targets;
	}

	@Override
	public void close() throws IOException {
		Optional<MultipleIOException> process = MultipleIOException.process(
			Arrays.asList(debugParserListener,debugTransactionListener),
			closable->{
				try {
					closable.close();
				} catch (Exception e) {
				}
			}
		);
		if(process.isPresent()){throw process.get();}
	}

	@Override
	public void onOutput(int count) {
	}

	@Override
	public void setLevel(OutputLevel level) {
	}

	@Override
	public void onStart(Parser parser, ParseContext parseContext, 
			TokenKind tokenKind, boolean invertMatch) {
		debugParserListener.onStart(parser, parseContext, tokenKind, invertMatch);
		count++;
		if(doTrigger()){
			//set Break point here or this method declares!
			onStartBreakPoint();
			onUpdateParseBreakPoint();
			onUpdateCombinedBreakPoint();
		}		
	}

	@Override
	public void onEnd(Parser parser, Parsed parsed, ParseContext parseContext,
			TokenKind tokenKind, boolean invertMatch) {
		debugParserListener.onEnd(parser, parsed, parseContext, tokenKind, invertMatch);
		count++;
		if(doTrigger()){
			//set Break point here or this method declares!
			onEndBreakPoint();
			onUpdateParseBreakPoint();
			onUpdateCombinedBreakPoint();
		}			
	}

	@Override
	public void onOpen(ParseContext parseContext) {
		debugTransactionListener.onOpen(parseContext);
		count++;
		if(doTrigger()){
			//set Break point here or this method declares!
			onOpenBreakPoint();
			onUpdateTransactionBreakPoint();
			onUpdateCombinedBreakPoint();
		}
	}

	@Override
	public void onBegin(ParseContext parseContext, Parser parser) {
		debugTransactionListener.onBegin(parseContext, parser);
		count++;
		if(doTrigger()){
			//set Break point here or this method declares!
			onBeginBreakPoint();
			onUpdateTransactionBreakPoint();
			onUpdateCombinedBreakPoint();
		}
	}

	@Override
	public void onCommit(ParseContext parseContext, Parser parser, TokenList committedTokens) {
		debugTransactionListener.onCommit(parseContext, parser, committedTokens);
		count++;
		if(doTrigger()){
			//set Break point here or this method declares!
			onCommitBreakPoint();
			onUpdateTransactionBreakPoint();
			onUpdateCombinedBreakPoint();
		}
	}

	@Override
	public void onRollback(ParseContext parseContext, Parser parser, TokenList rollbackedTokens) {
		debugTransactionListener.onRollback(parseContext, parser, rollbackedTokens);
		count++;
		if(doTrigger()){
			//set Break point here or this method declares!
			onRollbackBreakPoint();
			onUpdateTransactionBreakPoint();
			onUpdateCombinedBreakPoint();
		}
	}

	@Override
	public void onClose(ParseContext parseContext) {
		debugTransactionListener.onClose(parseContext);
		count++;
		if(doTrigger()){
			//set Break point here or this method declares!
			onCloseBreakPoint();
			onUpdateTransactionBreakPoint();
			onUpdateCombinedBreakPoint();
		}
	}
	
	public boolean doTrigger(){
		return targets.contains(count);
	}
	
	/**
	 * set break point on this method if you needs 
	 */
	@BreakPointMethod
	public void onUpdateCombinedBreakPoint(){}

}