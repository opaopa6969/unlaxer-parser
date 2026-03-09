package org.unlaxer;

import org.junit.Test;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.posix.DigitParser;

public class ParserTestBaseTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.mostDetail);
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

}
