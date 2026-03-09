package org.unlaxer.parser.elementary;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.combinator.Chain;

public class EmptyLineParserTest {

	  @Test
	  public void test() {
	    
	    EmptyLineParser emptyLineParser = new EmptyLineParser();
	    
	    {
	      
	      String[] texts = new String[] {
	          "\n",
	          " \n",
	          "   \n",
	          "",
	          " ",
	      };
	      
	      for(String text: texts) {
	        StringSource stringSource = StringSource.createRootSource(text);
	        ParseContext parseContext = new ParseContext(stringSource);
	        Parsed parse = emptyLineParser.parse(parseContext);
	        assertTrue(parse.status.isSucceeded());
	      }
	    }
	    LineTerminatorParser lineTerminatorParser = new LineTerminatorParser();
	    WordParser wordParser = new WordParser("a");
	    Chain chain = new Chain(wordParser , lineTerminatorParser , emptyLineParser);
	    {
	      
	      String[] texts = new String[] {
	          "a\n\n",
	          "a\n   ",
	          "a\n     \n",
	      };
	      
	      for(String text: texts) {
	        StringSource stringSource = StringSource.createRootSource(text);
	        ParseContext parseContext = new ParseContext(stringSource);
	        Parsed parse = chain.parse(parseContext);
	        assertTrue(parse.status.isSucceeded());
	      }
	    }
	    
	    chain = new Chain(emptyLineParser,wordParser);
	    {
	      
	      String[] texts = new String[] {
	          "\na",
	          " \na",
	          "   \na",
	      };
	      
	      for(String text: texts) {
	        StringSource stringSource = StringSource.createRootSource(text);
	        ParseContext parseContext = new ParseContext(stringSource);
	        Parsed parse = chain.parse(parseContext);
	        String consumed = parse.getConsumed().getSource().toString();
	        System.out.println("test:" + text);
	        System.out.println("consumed:"+consumed);
	        assertTrue(parse.status.isSucceeded());
	      }
	    }

	    chain = new Chain(wordParser , lineTerminatorParser , emptyLineParser,wordParser);
	    {
	      
	      String[] texts = new String[] {
	          "a\n\na",
	          "a\n \na",
	          "a\n   \na",
	      };
	      
	      for(String text: texts) {
	        StringSource stringSource = StringSource.createRootSource(text);
	        ParseContext parseContext = new ParseContext(stringSource);
	        Parsed parse = chain.parse(parseContext);
	        assertTrue(parse.status.isSucceeded());
	      }
	    }

	    
	    {
	      
	      String[] texts = new String[] {
	          "\n# test\n",
	          " #",
	          " # test comment\n",
	          "   # test comment \"niku \"\n",
	          "comment\n",
	          "//comment\n",
	          "＃comment\n"
	      };
	      
	      for(String text: texts) {
	        StringSource stringSource = StringSource.createRootSource(text);
	        ParseContext parseContext = new ParseContext(stringSource);
	        Parsed parse = emptyLineParser.parse(parseContext);
	        String consumed = parse.getConsumed().getSource().toString();
	        System.out.println("test:" + text);
	        System.out.println("consumed:"+consumed);
	        assertNotEquals(text,consumed);
	      }
	    }
	  }

	}
