package org.unlaxer.listener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class ExplicitBreakPointHolder implements ParserListener {
	
	public List<Predicate<ParseParameters>> predicates;
	OutputLevel level;

	
	public ExplicitBreakPointHolder() {
		this(OutputLevel.simple);
	}

	public ExplicitBreakPointHolder(List<Predicate<ParseParameters>> predicates) {
		this(OutputLevel.simple , predicates);
	}
	
	@SafeVarargs
	public ExplicitBreakPointHolder(Predicate<ParseParameters>... predicates) {
		this(OutputLevel.simple, predicates);
	}
	
	public ExplicitBreakPointHolder(OutputLevel level) {
		super();
		predicates = new ArrayList<Predicate<ParseParameters>>();
		this.level = level;
	}

	public ExplicitBreakPointHolder(OutputLevel level , List<Predicate<ParseParameters>> predicates) {
		super();
		this.predicates = predicates;
		this.level = level;
	}
	
	@SafeVarargs
	public ExplicitBreakPointHolder(OutputLevel level , Predicate<ParseParameters>... predicates) {
		super();
		this.predicates = Arrays.asList(predicates);
		this.level = level;
	}



	@Override
	public void onStart(Parser parser, ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		if(level.isNone()){
			return ;
		}
		
		ParseParameters parseParameters = new ParseParameters(parser, parseContext, tokenKind, invertMatch);
		for(Predicate<ParseParameters> predicate : predicates){
			if(predicate.test(parseParameters)){
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
		
		ParseParameters parseParameters = new ParseParameters(parser, parseContext, tokenKind, invertMatch);
		for(Predicate<ParseParameters> predicate : predicates){
			if(predicate.test(parseParameters)){
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