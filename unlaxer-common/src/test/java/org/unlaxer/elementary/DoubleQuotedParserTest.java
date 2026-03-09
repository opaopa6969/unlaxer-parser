package org.unlaxer.elementary;

import java.util.Optional;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.parser.elementary.DoubleQuotedParser;
import org.unlaxer.parser.elementary.QuotedParser;

public class DoubleQuotedParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		DoubleQuotedParser doubleQuotedParser = new DoubleQuotedParser();
		
		testAllMatch(doubleQuotedParser,"\"abc\"");
		testAllMatch(doubleQuotedParser,"\"ab\\c\"");
		TestResult testAllMatch = testAllMatch(doubleQuotedParser,"\"ab\\\"c\"" , true);
		System.out.println(TokenPrinter.get(testAllMatch.parsed.getRootToken()));
		Optional<Token> findFirst = 
			testAllMatch.parsed.getRootToken().flatten().stream()
				.peek(token->System.out.println(TokenPrinter.get(token)))
				.filter(token->token.parser.getName().equals(QuotedParser.contentsName))
				.findFirst();
		
		System.out.println(findFirst.get());
	}
	
	static String d(String contents) {
		
		return "\"" + contents + "\"";
	}
	
	static String s(String contents) {
		
		return "'" + contents + "'";
	}

}
