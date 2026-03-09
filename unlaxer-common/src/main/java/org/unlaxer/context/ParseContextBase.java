package org.unlaxer.context;

import java.util.Deque;
import java.util.Map;

import org.unlaxer.Source;
import org.unlaxer.TransactionElement;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.NonOrdered;

public interface ParseContextBase{
	
	public Deque<TransactionElement> getTokenStack();
	
	public ParseContext get();
	
	public boolean doCreateMetaToken();
	
	public Map<ChoiceInterface, Parser> getChosenParserByChoice();
	
	public Map<NonOrdered, Parsers> getOrderedParsersByNonOrdered();
	
	public Source getSource();
}