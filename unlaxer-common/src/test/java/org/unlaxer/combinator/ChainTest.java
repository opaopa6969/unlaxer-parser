package org.unlaxer.combinator;

import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.AlphabetNumericParser;
import org.unlaxer.parser.posix.AlphabetParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.parser.referencer.MatchedTokenParser;
import org.unlaxer.parser.referencer.OldMatchedTokenParser;
import org.unlaxer.parser.referencer.ReferenceParser;


public class ChainTest extends ParserTestBase{

	@Test
	public void testDigitsAndOperatorAndDigits() {
		Chain chain = new Chain(
			new OneOrMore(DigitParser.class),
			new Choice(
				PlusParser.class,
				MinusParser.class,
				MultipleParser.class,
				DivisionParser.class
			),
			new OneOrMore(DigitParser.class)
		);
			
		testPartialMatch(chain, "1+1", "1+1");
		testPartialMatch(chain, "1+1/3", "1+1");
		testPartialMatch(chain, "10*3/3", "10*3");
		testPartialMatch(chain, "104*37/3", "104*37");
		
		testUnMatch(chain, "" );
		testUnMatch(chain, "1" );
		testUnMatch(chain, "1+" );
		testUnMatch(chain, "1+a" );
		testUnMatch(chain, "1/a" );
		testUnMatch(chain, "+10+10" );
	}
	
	@Test
	public void testDigitsAndZeroOrMoreOperatorAndDigits() {
		
		Parser clauseParser = createDigitsAndOperatorsParser();
			
		testAllMatch(clauseParser, "9");
		testAllMatch(clauseParser, "1+1");
		testAllMatch(clauseParser, "1+1/3");
		testAllMatch(clauseParser, "10*3/3");
		testAllMatch(clauseParser, "104*37/3");
		
		testUnMatch(clauseParser, "" );
		testPartialMatch(clauseParser, "1+" ,"1");
		testPartialMatch(clauseParser, "1+a" ,"1");
		testPartialMatch(clauseParser, "1/a" ,"1");
		testUnMatch(clauseParser, "+10+10" );
	}

	private Parser createDigitsAndOperatorsParser() {
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

	
	@Test
	public void testTerminator(){
		
		setLevel(OutputLevel.simple);
		
		Chain terminatored = new Chain(Name.of(Baz.all),//
			new MatchOnly(Name.of(Baz.lookahead),
				new Chain(Name.of(Baz.declareStopWord),
					new OneOrMore(Name.of(Baz.stopWord),
						AlphabetParser.class
					),
					new WordParser(";")
				)
			),
			new Chain(Name.of(Baz.clause),
				new Chain(Name.of(Baz.header),
					new MatchedTokenParser(
						ReferenceParser.of(Name.of(Baz.stopWord))
					),
					new WordParser(";")
				),
				new OneOrMore(Name.of(Baz.contents),
					AlphabetNumericParser.class//
				).newWithTerminator(
					new MatchOnly(//
						new MatchedTokenParser(
							ReferenceParser.of(Name.of(Baz.stopWord))
						)
					)
				)
			)
		);
		testPartialMatch(terminatored, "end;abcdefgendxx","end;abcdefg",true);

	}
	
	@Test
	public void testTerminatorOld(){
		
		setLevel(OutputLevel.simple);
		
		Chain terminatored = new Chain(Name.of(Baz.all),//
			new MatchOnly(Name.of(Baz.lookahead),
				new Chain(Name.of(Baz.declareStopWord),
					new OneOrMore(Name.of(Baz.stopWord),
						AlphabetParser.class
					),
					new WordParser(";")
				)
			),
			new Chain(Name.of(Baz.clause),
				new Chain(Name.of(Baz.header),
					new OldMatchedTokenParser(
						ReferenceParser.of(Name.of(Baz.stopWord))
					),
					new WordParser(";")
				),
				new OneOrMore(Name.of(Baz.contents),
					AlphabetNumericParser.class//
				).newWithTerminator(
					new MatchOnly(//
						new OldMatchedTokenParser(
							ReferenceParser.of(Name.of(Baz.stopWord))
						)
					)
				)
			)
		);
		testPartialMatch(terminatored, "end;abcdefgendxx","end;abcdefg",true);

	}
	
	@Test
	public void testChainAndZeroOrMore() {
		
		setLevel(OutputLevel.mostDetail);

		Chain chain = new Chain(
				new WordParser("a"),
				new ZeroOrMore(new WordParser("b"))
		);
		testAllMatch(chain, "ab");
		testAllMatch(chain, "a");
		testAllMatch(chain, "abbb");
		testUnMatch(chain, "");
		testUnMatch(chain, "b");
		
	}

}
