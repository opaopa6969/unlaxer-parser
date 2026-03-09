package sample;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.posix.DigitParser;

public class Usage001_createParserAndParse extends UsageBase{
	
	@Test
	public void testParseWithOutCreateMetaToken(){
		
		Parser parser = createDigitsAndOperatorsParser();
		
		//create parseContext with default behavior. no createMeta specifier
		ParseContext parseContext = 
				new ParseContext(StringSource.createRootSource("1+2+3"));
		
		parse(parser, parseContext);
	}

	@Test
	public void testParseWithCreateMetaToken(){
		
		Parser parser = createDigitsAndOperatorsParser();
		
		//create parseContext with createMeta specifier
		ParseContext parseContext = 
			new ParseContext(
				StringSource.createRootSource("1+2+3"),
				CreateMetaTokenSpecifier.createMetaOn
			);
		
		parse(parser, parseContext);
	}
	
	
	
	Parser createDigitsAndOperatorsParser() {
		
		//<Clause> ::= [0-9]+([-+*/][0-9]+)*
		Chain clauseParser = new Chain(
			new OneOrMore(DigitParser.class),
			new ZeroOrMore(
				new Chain(
					new Choice(
						PlusParser.class,
						MinusParser.class,
						MultipleParser.class,
						DivisionParser.class
					),
					new OneOrMore(DigitParser.class)
				)
			)
		);
		return clauseParser;
	}
}
