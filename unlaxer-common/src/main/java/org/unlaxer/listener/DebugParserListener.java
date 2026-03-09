package org.unlaxer.listener;

import java.io.Closeable;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

import org.unlaxer.Parsed;
import org.unlaxer.ParsedPrinter;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParserContextPrinter;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ParserPrinter;

public class DebugParserListener implements ParserListener , LogOutputCountListener , Closeable{
	
	PrintStream print;
	OutputLevel level;
	
	int count;
	LogOutputCountListener listener;
	
	Set<Integer> targets;
	
	public DebugParserListener() {
		this(System.out , OutputLevel.simple );
	}
	
	public DebugParserListener(PrintStream out, OutputLevel outputLevel) {
		this(out, outputLevel, LogOutputCountListener.BlackHole , new HashSet<>());
	}
	
	public DebugParserListener(//
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
	public void onStart(Parser parser, ParseContext parseContext, 
			TokenKind tokenKind, boolean invertMatch) {
		if(level.isNone()){
			return ;
		}
		print.format("START   : %s \t| %s\n", getDisplay(parseContext) , getDisplay(parser));
		onOutput(++count);
		if(doTrigger()){
			//set Break point here or this method declares!
			onStartBreakPoint();
			onUpdateParseBreakPoint();
		}		
	}

	@Override
	public void onEnd(Parser parser, Parsed parsed, ParseContext parseContext, 
			TokenKind tokenKind,boolean invertMatch) {
		if(level.isNone()){
			return ;
		}
		print.format("END     : %s \t| %s| %s\n", 
				getDisplay(parseContext) , getDisplay(parser),getDisplay(parsed));
		onOutput(++count);
		if(doTrigger()){
			//set Break point here or this method declares!
			onEndBreakPoint();
			onUpdateParseBreakPoint();
		}		
	}

	String getDisplay(ParseContext parseContext){
		return ParserContextPrinter.get(parseContext, level);
	}
	
	String getDisplay(Parser parser){
		return ParserPrinter.get(parser, level);
	}
	
	String getDisplay(Parsed parsed){
		return ParsedPrinter.get(parsed, level);
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