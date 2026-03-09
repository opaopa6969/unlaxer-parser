package org.unlaxer.context;

import java.util.List;
import java.util.function.Predicate;

import org.unlaxer.Name;
import org.unlaxer.listener.ExplicitBreakPointHolderWithParser;
import org.unlaxer.parser.Parser;

public class BreakPointSpecifierWithParser implements ParseContextEffector{
	
	List<Predicate<Parser>> predicates;		
	public BreakPointSpecifierWithParser(List<Predicate<Parser>> predicates) {
		this.predicates = predicates;
	}
	
	@Override
	public void effect(ParseContext parseContext) {
		
		ExplicitBreakPointHolderWithParser explicitBreakPointHolderWithParser =
				new ExplicitBreakPointHolderWithParser(predicates);
		parseContext.getParserListenerByName().put(
				Name.of(ExplicitBreakPointHolderWithParser.class), //
				explicitBreakPointHolderWithParser);
	}
}