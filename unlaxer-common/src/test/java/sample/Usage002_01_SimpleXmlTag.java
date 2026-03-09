package sample;

import java.util.Optional;
import java.util.function.Supplier;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyOneOrMore;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.elementary.SpaceDelimitor;
import org.unlaxer.parser.elementary.WildCardStringParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.PunctuationParser;
import org.unlaxer.util.cache.SupplierBoundCache;

public class Usage002_01_SimpleXmlTag extends ParserTestBase {
	
	@Test
	public void testOpenTag(){
		
		setLevel(OutputLevel.detail);
		
		Parser parser = new OpenTag();
		
		testAllMatch(parser, "<test>");
		testAllMatch(parser, "< test>");
		testAllMatch(parser, "< test >");
		testUnMatch(parser, "< te st >"); // until support attributes 
		testUnMatch(parser, "<test />"); // until support empty-elements 
		
	}
	
	@Test
	public void testCloseTag(){
		
		setLevel(OutputLevel.detail);
		
		Parser parser = new CloseTag();
		
		testAllMatch(parser, "</test>");
		testAllMatch(parser, "</ test>");
		testAllMatch(parser, "< /test >");
		testUnMatch(parser, "</ te st >"); // until support attributes 
		testUnMatch(parser, "</test />");  
	}
	
	@Test
	public void testEmptyElementTag(){
		
		setLevel(OutputLevel.detail);
		
		Parser parser = new EmptyElementTag();
		
		testAllMatch(parser, "<test/>");
		testAllMatch(parser, "<test />");
		testAllMatch(parser, "<test / >");
		testAllMatch(parser, "<test/ >");
		testUnMatch(parser, "<test>");
		testUnMatch(parser, "< test>");
		testUnMatch(parser, "<test >");
		testUnMatch(parser, "</ te st >"); // until support attributes 
		testUnMatch(parser, "</test />"); 
	}

	static class OpenTag extends LazyChain{

		private static final long serialVersionUID = -2182936361245898255L;

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new WordParser("<"),
				new SpaceDelimitor(),
				new TagIdentifier(),
				new SpaceDelimitor(),
				new WordParser(">")
			);
		}
	}
	
	static class EmptyElementTag extends LazyChain{


		private static final long serialVersionUID = 791155244371219245L;
		
	  @Override
	  public Parsed parse(ParseContext parseContext , TokenKind tokenKind ,boolean invertMatch) {
	    return super.parse(parseContext, tokenKind, invertMatch);
	  }

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new WordParser("<"),
				new SpaceDelimitor(),
				new TagIdentifier(),
				new SpaceDelimitor(),
				new WordParser("/"),
				new SpaceDelimitor(),
				new WordParser(">")
			);
		}
	}

	
	static class CloseTag extends LazyChain{

		private static final long serialVersionUID = 6450422344705494726L;
		
	  @Override
	  public Parsed parse(ParseContext parseContext , TokenKind tokenKind ,boolean invertMatch) {
	    return super.parse(parseContext, tokenKind, invertMatch);
	  }

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new WordParser("<"),
				new SpaceDelimitor(),
				new WordParser("/"),
				new SpaceDelimitor(),
				new TagIdentifier(),
				new SpaceDelimitor(),
				new WordParser(">")
			);
		}
	}

	
	static class TagIdentifier extends LazyOneOrMore{

		private static final long serialVersionUID = -5994401376092924600L;
		
    @Override
    public Parsed parse(ParseContext parseContext , TokenKind tokenKind ,boolean invertMatch) {
      return super.parse(parseContext, tokenKind, invertMatch);
    }

		@Override
		public Supplier<Parser> getLazyParser() {
			return new SupplierBoundCache<>(WildCardStringParser::new);
		}

		@Override
		public Optional<Parser> getLazyTerminatorParser() {
			return Optional.of(
				new MatchOnly(
//					new MappedSingleCharacterParser(" <>/!#$%&'(){}\"")
					new PunctuationParser().newWith(" ") //adding space
				)
			);
		}
		
	}
	
	static class Contents extends LazyChain{

		private static final long serialVersionUID = -7796147663108046605L;
		
    @Override
    public Parsed parse(ParseContext parseContext , TokenKind tokenKind ,boolean invertMatch) {
      return super.parse(parseContext, tokenKind, invertMatch);
    }

		@Override
		public Parsers getLazyParsers() {
			return new Parsers(
				new PlusParser(),
				new MinusParser(),
				new MultipleParser(),
				new DivisionParser()
			);
		}
	}
	
}