package sample;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.Optional;

import sample.Usage002_01_SimpleXmlTag.CloseTag;
import sample.Usage002_01_SimpleXmlTag.EmptyElementTag;
import sample.Usage002_01_SimpleXmlTag.OpenTag;

public class Usage002_02_SimpleXmlTagOpenAndClose extends ParserTestBase {
	
	@Test
	public void testOpenCloseTag(){
		
		setLevel(OutputLevel.detail);
		
		Parser parser = new NoMixedContentsXmlElements();
		
		testAllMatch(parser, "<test/>");
		testAllMatch(parser, "<test></test>");
		testAllMatch(parser, "<test><word></word></test>");
		testAllMatch(parser, "<test><word><contents></contents></word></test>");
		testAllMatch(parser, "<test><word/></test>");
		testAllMatch(parser, "<test><word><contents/></word></test>");
		
		// this is unmatch under normal. but this definition is not supported reference
		testAllMatch(parser, "<test></test2>");
		
		testUnMatch(parser, "<test><word></test>");
		// not supported mixed contents
		testUnMatch(parser, "<test><word/><contetns></contents/></test>");
		
	}
	

	static class NoMixedContentsXmlElements extends LazyChoice{

		private static final long serialVersionUID = -4625102353325797710L;

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new OpenCloseTags(),
				new EmptyElementTag()
			);
		}
	}
	
	static class OpenCloseTags extends LazyChain{
		
		private static final long serialVersionUID = -459175328755441487L;

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new OpenTag(),
				new Optional(
					NoMixedContentsXmlElements.class
				),
				new CloseTag()
			);
		}
	}
}