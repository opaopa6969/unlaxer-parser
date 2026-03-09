package org.unlaxer.parser.elementary;

import java.util.function.UnaryOperator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;

public class DoubleQuotedParserTest extends ParserTestBase{

	@Test
	public void test() {
		
		DoubleQuotedParser doubleQuotedParser = new DoubleQuotedParser();
		
		testAllMatch(doubleQuotedParser, outputToStdout.apply("\"\""));
		testAllMatch(doubleQuotedParser, outputToStdout.apply("\"abc,123\""));
		testAllMatch(doubleQuotedParser, outputToStdout.apply("\"123\\\"abc\""));
		testAllMatch(doubleQuotedParser, outputToStdout.apply("\"\\\"\""));
		testAllMatch(doubleQuotedParser, outputToStdout.apply("\"\\\"\""));
	}
	
	UnaryOperator<String> outputToStdout = word -> {
	  System.out.println(word);
	  return word;
	};

}
