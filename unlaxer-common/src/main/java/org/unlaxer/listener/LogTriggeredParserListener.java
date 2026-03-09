package org.unlaxer.listener;

import java.util.HashSet;
import java.util.Set;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class LogTriggeredParserListener implements ParserListener ,  LogOutputCountListener{
	
	final Set<Integer> targets;
	
	boolean triggered = false;
	OutputLevel level;

	
	public LogTriggeredParserListener(Set<Integer> targets) {
		super();
		this.targets = targets;
		
		level = OutputLevel.simple;
	}
	
	public LogTriggeredParserListener(int... targets) {
		super();
		this.targets = new HashSet<>();
		for (int target : targets) {
			this.targets.add(target);
		}
		level = OutputLevel.simple;
	}

	@Override
	public void onStart(Parser parser, ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		if(level.isNone()){
			return ;
		}

		if(false == triggered){
			return;
		}
		//set Break point here or this method declares!
		onStartBreakPoint();
	}

	@Override
	public void onEnd(Parser parser, Parsed parsed, ParseContext parseContext, TokenKind tokenKind,
			boolean invertMatch) {
		
		if(level.isNone()){
			return ;
		}

		if(false == triggered){
			return;
		}
		//set Break point here or this method declares!
		onEndBreakPoint();
		triggered = false;
	}

	@Override
	public void onOutput(int count) {
		triggered = triggered || targets.contains(count);
	}

	@Override
	public void setLevel(OutputLevel level) {
		this.level = level;
	}
}