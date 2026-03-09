package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.context.ParseFailureDiagnostics;
import org.unlaxer.parser.ErrorMessageParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.posix.DigitParser;

import net.arnx.jsonic.JSON;

public class ErrorMessageParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		ParserTestBase.setLevel(OutputLevel.simple);
		
		String message = "you must specify digit after plus token";
		Chain parser = new Chain(
			new DigitParser(),
			new PlusParser(),
			new Choice(
				new DigitParser(),
				new ErrorMessageParser(message)
			)
		);
		
		for(boolean createMeta : new boolean[]{true, false }){
			
			TestResult result = testAllMatch(parser, "1+" , createMeta);
			
			assertTrue(result.parsed.isSucceeded());
			
			Token token = result.parsed.getRootToken();
			TokenPrinter.output(token, System.out);
			
			List<RangedContent<String>> errorMessages = 
				ErrorMessageParser.getRangedContents(token,ErrorMessageParser.class);
			
			
			System.out.println(JSON.encode(errorMessages));
			
			assertEquals(1, errorMessages.size());
			assertEquals(message, errorMessages.get(0).getContent());
			assertEquals(2, errorMessages.get(0).getRange().startIndexInclusive.positionInRoot().value());
			assertEquals(2, errorMessages.get(0).getRange().endIndexExclusive.positionInRoot().value());
		}
		
		TestResult result = testPartialMatch(parser, "1+s","1+");
		assertTrue(result.parsed.isSucceeded());
		
		Token token = result.parsed.getRootToken();
		TokenPrinter.output(token, System.out);
		
		List<ErrorMessage> errorMessages = TokenPrinter.getErrorMessages(token);
		System.out.println(JSON.encode(errorMessages));


	}

	@Test
	public void expectedHintModeReportsCustomHintOnFailure() {

		String customHint = "expected: digit after '+'";
		Chain parser = new Chain(
			new DigitParser(),
			new PlusParser(),
			new Choice(
				new DigitParser(),
				ErrorMessageParser.expected(customHint)
			)
		);

		TestResult result = testUnMatch(parser, "1+");
		assertTrue(result.parsed.isFailed());

		ParseFailureDiagnostics diagnostics = result.parseContext.getParseFailureDiagnostics();
		boolean hasCustomHint = diagnostics.getExpectedHintCandidates().stream()
			.map(ParseFailureDiagnostics.ExpectedHintCandidate::getDisplayHint)
			.anyMatch(customHint::equals);

		assertTrue(hasCustomHint);
	}
}
