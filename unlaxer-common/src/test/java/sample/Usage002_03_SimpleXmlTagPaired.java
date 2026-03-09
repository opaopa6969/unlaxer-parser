package sample;

import java.util.function.Supplier;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.elementary.SpaceDelimitor;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.referencer.MatchedTokenParser;

import sample.Usage002_01_SimpleXmlTag.EmptyElementTag;
import sample.Usage002_01_SimpleXmlTag.TagIdentifier;

public class Usage002_03_SimpleXmlTagPaired extends ParserTestBase {
	
	@Test
	public void testOpenCloseTag(){
		
		setLevel(OutputLevel.detail);
		
		Parser parser = new NoMixedContentsXmlElementsPaired();

		testAllMatch(parser, "<test/>");
		testAllMatch(parser, "<test></test>");
		testAllMatch(parser, "<test><word></word></test>");
		testAllMatch(parser, "<test><word><contents></contents></word></test>");
		testAllMatch(parser, "<test><word/></test>");
		testAllMatch(parser, "<test><word><contents/></word></test>");
		
		testUnMatch(parser, "<test></test2>");
		
		testUnMatch(parser, "<test><word></test>");
		// not supported mixed contents
		testUnMatch(parser, "<test><word/><contetns></contents/></test>");
	}
	

	static class NoMixedContentsXmlElementsPaired extends LazyChoice{

		private static final long serialVersionUID = -1114527678479914911L;

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new OpenCloseTagPaired(),
				new EmptyElementTag()
			);
		}
	}
	
	static class OpenTagWithReference extends LazyChain{

		private static final long serialVersionUID = -5782427867767775819L;
		
		MatchedTokenParser matchedElementParser;
		
		public Supplier<MatchedTokenParser> getElementNameParser(){
			return ()->matchedElementParser;
		}
		

		@Override
		public Parsers getLazyParsers() {

			Parser elementName = new TagIdentifier();
			matchedElementParser = new MatchedTokenParser(elementName);
			
			return new Parsers(
				new WordParser("<"),
				new SpaceDelimitor(),
				elementName,
				new SpaceDelimitor(),
				new WordParser(">")
			);
		}
	}

	
	static class OpenCloseTagPaired extends LazyChain{
		
		private static final long serialVersionUID = 73057027414137953L;

		@Override
		public Parsers getLazyParsers() {

			OpenTagWithReference openTagWithReference = new OpenTagWithReference();
			
			return new Parsers(
				openTagWithReference,
				new Optional(
					NoMixedContentsXmlElementsPaired.class
				),
				new CloseTagWithReference(
					openTagWithReference.getElementNameParser()
				)
			);
		}
	}
	
	static class CloseTagWithReference extends LazyChain{

		Supplier<MatchedTokenParser> matchedElementParser;
		

		public CloseTagWithReference(Supplier<MatchedTokenParser> matchedElementParser) {
			super();
			this.matchedElementParser = matchedElementParser;
		}

		private static final long serialVersionUID = -6808910910564400230L;

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new WordParser("<"),
				new SpaceDelimitor(),
				new WordParser("/"),
				new SpaceDelimitor(),
				matchedElementParser.get(),
				new SpaceDelimitor(),
				new WordParser(">")

			);
		}
	}

}