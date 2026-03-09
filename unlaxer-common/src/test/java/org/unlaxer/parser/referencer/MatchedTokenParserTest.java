package org.unlaxer.parser.referencer;

import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.ParserTestBase;
import org.unlaxer.Tag;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenPrinter;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedChain;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.ParenthesesParser;
import org.unlaxer.parser.elementary.WildCardStringParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.AlphabetParser;

public class MatchedTokenParserTest extends ParserTestBase{

	@Test
	public void test() {
		setLevel(OutputLevel.simple);
		
		Name elementsName = Name.of("inner");
		OneOrMore inner = new OneOrMore(elementsName , AlphabetParser.class);
		Chain parser = new Chain(
			new ParenthesesParser(//
				inner//
			),
			new MatchedTokenParser(inner)
		);
		
		for(boolean createMeta : new boolean[]{true}){
			
			testAllMatch(parser, "(abc)abc",createMeta);
			testAllMatch(parser, "(abz)abz",createMeta);
			testUnMatch(parser, "(abz)abu",createMeta);
		}
		
	}
	
	@Test
	public void testOccursWithoutTerminator() {
		setLevel(OutputLevel.simple);
		
		OneOrMore inner = new OneOrMore(AlphabetParser.class);
		Chain parser = new Chain(
			inner,//
			new WordParser(":"),//separetor
			new MatchedTokenParser(inner)
		);
		
		testAllMatch(parser, "abc:abc",true);
		testAllMatch(parser, "abz:abz",true);
		testUnMatch(parser, "abz:abu",true);
	}

	@Test
	public void testOccursWithTerminator() {
		setLevel(OutputLevel.simple);
		
		OneOrMore inner = new OneOrMore(WildCardStringParser.class)
			.newWithTerminator(new MatchOnly(new WordParser(":")));
		
		Chain parser = new Chain(
			inner,//
			new WordParser(":"),//separetor
			new MatchedTokenParser(inner)
		);
		
		testAllMatch(parser, "abc:abc",true);
		testAllMatch(parser, "abz:abz",true);
		testUnMatch(parser, "abz:abu",true);
	}
	
	@Test
	public void testMultiSource() {
		
		Tag typeTag = new Tag("type");
		
		String text = 
			"var $name string;\n"+
			"var $age number;\n"+
					
		    "print $name;\n"+
		    "print $age;\n"
		    ;
		
		
		setLevel(OutputLevel.simple);
		
		
		Parser varKeywordParser = new WordParser("var");
		Parser varNameParser = new Chain(
			new WordParser("$"),
			new OneOrMore(AlphabetParser.class)
		);
		
		Parser typeParser = new Choice(
			new WordParser("string").addTag(typeTag),
			new WordParser("number").addTag(typeTag)
		);
		
		var varParser = new WhiteSpaceDelimitedChain(
				varKeywordParser,
				varNameParser,
				typeParser,
				new WordParser(";")
		);
		
		Parser varsParser = new ZeroOrMore(varParser);
		
		
		WordParser printKeywordParser = new WordParser("print");
		
		WhiteSpaceDelimitedChain printParser = new WhiteSpaceDelimitedChain(
			printKeywordParser,
			new MatchedTokenParser(varNameParser),
			new WordParser(";")
		);
		
		Parser printsParser = new OneOrMore(printParser);
		
		var parser = new WhiteSpaceDelimitedChain(varsParser , printsParser);
		
		TestResult testAllMatch = testAllMatch(parser, text);
		Token rootToken = testAllMatch.parsed.getRootToken();
		String string = TokenPrinter.get(rootToken);
		System.out.println(string);
		
	}
}
