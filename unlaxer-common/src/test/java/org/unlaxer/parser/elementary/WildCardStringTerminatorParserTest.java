package org.unlaxer.parser.elementary;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.combinator.Chain;

public class WildCardStringTerminatorParserTest extends ParserTestBase{

  @Test
  public void test() {
    
    setLevel(OutputLevel.detail);
    
    {
    	Chain chain = new Chain(
    			new StartOfLineParser(),
    			new WordParser("---END_OF_PART--/"),
    			new LineTerminatorParser()
		);
    	WildCardStringTerminatorParser wildCardStringTerminatorParser = 
    			new WildCardStringTerminatorParser(true,chain);
    	
    	TestResult testAllMatch = testPartialMatch(wildCardStringTerminatorParser, "u\n---END_OF_PART--/\nsushi","u\n");
    	Token rootToken = testAllMatch.parsed.getRootToken();
    	System.out.println(TokenPrinter.get(rootToken)); 
		String token = rootToken.getSource().sourceAsString();
    	System.out.println(token);
    }
    
    {
      
      WordParser abc = new WordParser("abc");
      WildCardStringTerminatorParser wildCardStringTerminatorParser = new WildCardStringTerminatorParser("\n");
      LineTerminatorParser lineTerminatorParser = new LineTerminatorParser();
      
      Chain wildCardAbc = new Chain(wildCardStringTerminatorParser , lineTerminatorParser , abc);
      Chain wildCardLT= new Chain(wildCardStringTerminatorParser , lineTerminatorParser);
      
      testAllMatch(wildCardLT, "nikuniku\n");
      testAllMatch(wildCardAbc, "nikuniku\nabc");
    }
    
    {
      
      WordParser abc = new WordParser("abc");
      WildCardStringTerminatorParser wildCardStringTerminatorParser = new WildCardStringTerminatorParser(new LineTerminatorParser());
      LineTerminatorParser lineTerminatorParser = new LineTerminatorParser();

      Chain wildCard= new Chain(wildCardStringTerminatorParser);
      Chain wildCardAbc = new Chain(wildCardStringTerminatorParser , lineTerminatorParser , abc);
      Chain wildCardLT= new Chain(wildCardStringTerminatorParser , lineTerminatorParser);
      
      testAllMatch(wildCard, "nikuniku");
      testAllMatch(wildCardLT, "nikuniku\n");
      testAllMatch(wildCardAbc, "nikuniku\nabc");
    }
  }
  

}
