package org.unlaxer.parser.combinator;

import org.unlaxer.context.ParseContext;
import org.unlaxer.context.Transaction.AdditionalPreCommitAction;
import org.unlaxer.parser.Parser;

public class ChoiceCommitAction implements AdditionalPreCommitAction {
	
	Parser chosen;
	
	public ChoiceCommitAction(Parser chosen) {
		super();
		this.chosen = chosen;
	}
	
	@Override
	public void effect(Parser parser, ParseContext parseContext) {
		
		if (chosen != null && parser instanceof ChoiceInterface) {
			parseContext.chosenParserByChoice.put((ChoiceInterface) parser, chosen);
		}
	}
}