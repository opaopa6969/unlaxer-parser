package org.unlaxer.listener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;


public class ExplicitBreakPointHolderWithParser implements ParserListener {
	
	public List<Predicate<Parser>> predicates;
	
	OutputLevel level;

	public ExplicitBreakPointHolderWithParser() {
		super();
		predicates = new ArrayList<Predicate<Parser>>();
		level = OutputLevel.simple;
	}

	public ExplicitBreakPointHolderWithParser(List<Predicate<Parser>> predicates) {
		super();
		this.predicates = predicates;
		level = OutputLevel.simple;
	}
	
	@SafeVarargs
	public ExplicitBreakPointHolderWithParser(Predicate<Parser>... predicates) {
		super();
		this.predicates = Arrays.asList(predicates);
		level = OutputLevel.simple;
	}


	@Override
	public void onStart(Parser parser, ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		if(level.isNone()){
			return ;
		}

		for(Predicate<Parser> predicate : predicates){
			if(predicate.test(parser)){
				//set Break point here or this method declares!
				onStartBreakPoint();
			}
		}
	}

	@Override
	public void onEnd(Parser parser, Parsed parsed, ParseContext parseContext, TokenKind tokenKind,
			boolean invertMatch) {
		
		if(level.isNone()){
			return ;
		}
		
		for(Predicate<Parser> predicate : predicates){
			if(predicate.test(parser)){
				//set Break point here or this method declares!
				onEndBreakPoint();
			}
		}
	}
	
	@Override
	public void setLevel(OutputLevel level) {
		this.level = level;
	}

}
