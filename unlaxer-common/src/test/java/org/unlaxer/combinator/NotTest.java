package org.unlaxer.combinator;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.InvertMatchPropagationStopper;
import org.unlaxer.parser.combinator.NotPropagatableSource;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.posix.AlphabetParser;
import org.unlaxer.parser.posix.DigitParser;


public class NotTest extends ParserTestBase{

	@Test
	public void testSingleCharactor() {
		
		NotPropagatableSource not = new NotPropagatableSource(new DigitParser());
		testPartialMatch(not, "a", "a");
		testPartialMatch(not, "ab", "a");
		testUnMatch(not, "" );
		testUnMatch(not, "1" );
		testUnMatch(not, "12" );
	}
	
	@Test
	public void testCombinator() {
		NotPropagatableSource not = new NotPropagatableSource(
			new Chain(
				 new DigitParser(),					
				 new MinusParser()					
			)
		);
		testPartialMatch(not, "a1", "a1");
		testPartialMatch(not, "-b", "-b");
		testUnMatch(not, "1a" );
		testUnMatch(not, "99" );
		testUnMatch(not, "a-" );
	}

	@Test
	public void testComplex() {
		Chain chain = new Chain(
			new AlphabetParser(),
			new ZeroOrMore(
				new Choice(
					AlphabetParser.class,
					DigitParser.class
				) 
			)
			,new NotPropagatableSource(
				MinusParser.SINGLETON	
			)
		);
		testPartialMatch(chain, "az69+", "az69+");
		testPartialMatch(chain, "az69az9_", "az69az9_");
		testUnMatch(chain, "az69az-" );
	}
	
	@Test
	public void testRepresentaion_not(){
		NotPropagatableSource not = new NotPropagatableSource(new AlphabetParser());
		
		TestResult result = testAllMatch(not, "@");
		Token token = result.parsed.getOriginalTokens().get(0);
		String representation = TokenPrinter.get(token, 0, OutputLevel.simple , false);
		
		System.out.println(representation);
		assertEquals("'@' : org.unlaxer.parser.posix.AlphabetParser(inverted)",representation);
	}
		
	@Test
	public void testRepresentaion_not_not(){
		
		NotPropagatableSource not = new NotPropagatableSource(
				new NotPropagatableSource(new AlphabetParser()));
		
		TestResult result = testAllMatch(not, "a" ,false);
		Token token = result.parsed.getOriginalTokens().get(0);
		String representation = TokenPrinter.get(token, 0, OutputLevel.simple , false);
		
		System.out.println(representation);
		assertEquals("'a' : org.unlaxer.parser.posix.AlphabetParser",representation);
	}
	
	@Test
	public void testRepresentaion_not_propagationStop_not(){
		NotPropagatableSource not =
			new NotPropagatableSource(
				new InvertMatchPropagationStopper(
					new NotPropagatableSource(new AlphabetParser())
				)
			);
		
		TestResult result = testAllMatch(not, "@",false);
		Token token = result.parsed.getOriginalTokens().get(0);
		String representation = TokenPrinter.get(token, 0, OutputLevel.simple , false);
		
		System.out.println(representation);
		assertEquals("'@' : org.unlaxer.parser.posix.AlphabetParser(inverted)",representation);
	}
}
