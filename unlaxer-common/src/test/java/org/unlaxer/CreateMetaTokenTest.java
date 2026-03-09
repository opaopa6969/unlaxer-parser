package org.unlaxer;

import org.junit.Test;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.posix.AlphabetParser;
import org.unlaxer.parser.posix.DigitParser;

public class CreateMetaTokenTest extends ParserTestBase{
	
	@Test
	public void test(){
		
		setLevel(OutputLevel.simple);
		Chain chain = new Chain(
			new DigitParser(),
			new AlphabetParser()
		);
		for(boolean createMetaToken : new boolean[]{true,false}){
			System.out.println("createMetaToken:" + createMetaToken);

			TestResult testAllMatch = testAllMatch(chain, "7a" , createMetaToken);
			Token token = testAllMatch.parsed.getRootToken();
			TokenPrinter.output(token, System.out);
			System.out.println();
		}
		
	}

}
