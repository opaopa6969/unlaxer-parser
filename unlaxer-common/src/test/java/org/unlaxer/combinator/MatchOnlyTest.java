package org.unlaxer.combinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.TransactionDebugSpecifier;
import org.unlaxer.listener.DebugTransactionListener;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.Not;
import org.unlaxer.parser.posix.DigitParser;


public class MatchOnlyTest {

	@Test
	public void testSingle() {
		
		Chain chain = new Chain(
			new DigitParser(),
			new MatchOnly(new  MinusParser())
		);
		
		StringSource stringSource = StringSource.createRootSource("1-");
		ParseContext parseContext = new ParseContext(stringSource);
		
		Parsed parsed = chain.parse(parseContext);
		assertTrue(parsed.isSucceeded());
		assertEquals(2,parsed.getOriginalTokens().size());
		assertTrue(parseContext.allMatched());
		assertFalse(parseContext.allConsumed());
		assertEquals(2, parseContext.getSource().codePointLength().value());
		assertEquals(1, parseContext.getPosition(TokenKind.consumed).value());
	}
	
	@Test
	public void testMultiple() {
		Chain chain = new Chain(
			new DigitParser(),
			new MatchOnly(//
				new Chain(//
					new  MinusParser(),new  MinusParser()
				)
			)
		);
		
		{
			StringSource stringSource = StringSource.createRootSource("2-");
			ParseContext parseContext = new ParseContext(stringSource);
			
			Parsed parse = chain.parse(parseContext);
			assertFalse(parse.isSucceeded());
		}
		

		{

			StringSource stringSource = StringSource.createRootSource("3--");
			ParseContext parseContext = 
				new ParseContext(
					stringSource,
					new TransactionDebugSpecifier(
						new DebugTransactionListener()
					)
				);
			
			Parsed parsed = chain.parse(parseContext);
			Token rootToken = parsed.getRootToken(true);
			parsed.getConsumed();
			
			assertTrue(parsed.isSucceeded());
			assertEquals(2,parsed.getOriginalTokens().size());
			assertEquals(1,rootToken.getSource().codePointLength().value());
			assertEquals(1,parsed.getConsumed().getSource().codePointLength().value());
			assertTrue(parseContext.allMatched());
			assertFalse(parseContext.allConsumed());
			assertEquals(3, parseContext.getSource().codePointLength().value());
			assertEquals(1, parseContext.getConsumedPosition().value());
		}
	}
	
	@Test
	public void testWithNot() {
		Chain chain = new Chain(
				new DigitParser(),
				new Not(
					new MatchOnly(//
						new Chain(//
							new  MinusParser(),new  MinusParser()
							)
					)
				)
			);
		{
			StringSource stringSource = StringSource.createRootSource("1");
			ParseContext parseContext = new ParseContext(stringSource);
			Parsed parsed = chain.parse(parseContext);
			assertTrue(parsed.isSucceeded());
		}
		{
			StringSource stringSource = StringSource.createRootSource("1-");
			ParseContext parseContext = new ParseContext(stringSource);
			Parsed parsed = chain.parse(parseContext);
			assertTrue(parsed.isSucceeded());
		}
		{
			StringSource stringSource = StringSource.createRootSource("1++");
			ParseContext parseContext = new ParseContext(stringSource);
			Parsed parsed = chain.parse(parseContext);
			assertTrue(parsed.isSucceeded());
		}
		{
			StringSource stringSource = StringSource.createRootSource("1--");
			ParseContext parseContext = new ParseContext(stringSource);
			Parsed parsed = chain.parse(parseContext);
			assertTrue(parsed.isFailed());
		}
		
		
	}

}
