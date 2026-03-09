package org.unlaxer.context;

import java.util.List;
import java.util.function.Predicate;

import org.unlaxer.Name;
import org.unlaxer.listener.ExplicitBreakPointHolder;
import org.unlaxer.listener.ParserListener.ParseParameters;

public class BreakPointSpecifier implements ParseContextEffector{
	
	List<Predicate<ParseParameters>> predicates;		
	public BreakPointSpecifier(List<Predicate<ParseParameters>> predicates) {
		this.predicates = predicates;
	}
	
	@Override
	public void effect(ParseContext parseContext) {
		
		ExplicitBreakPointHolder explicitBreakPointHolder =
				new ExplicitBreakPointHolder(predicates);
		parseContext.getParserListenerByName().put(
				Name.of(ExplicitBreakPointHolder.class), //
				explicitBreakPointHolder);
	}
}