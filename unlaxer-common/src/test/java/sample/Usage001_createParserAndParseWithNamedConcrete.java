package sample;

import java.util.Optional;
import java.util.function.Supplier;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.LazyOneOrMore;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.util.cache.SupplierBoundCache;

public class Usage001_createParserAndParseWithNamedConcrete extends Usage001_createParserAndParse{
	
	@Test
	public void testSimpleExpression(){
		
		Parser parser = new SimpleExpression();
		
		//create parseContext with createMeta specifier
		ParseContext parseContext = 
				new ParseContext(
					StringSource.createRootSource("1+2+3")
				);
		
		parse(parser, parseContext);
	}


	static class SimpleExpression extends LazyChain{

		private static final long serialVersionUID = 7826530496200688072L;
		

		@Override
		public Parsers getLazyParsers() {

			return new Parsers(
				new NumberParser(),
				new ZeroOrMore(
					OperatorAndOperandParser.class
				)
			);
		}
	}
	
	static class NumberParser extends LazyOneOrMore{

		private static final long serialVersionUID = 3803900894716516920L;

		@Override
		public Supplier<Parser> getLazyParser() {
			return new SupplierBoundCache<>(DigitParser::new);
		}

		@Override
		public Optional<Parser> getLazyTerminatorParser() {
			return Optional.empty();
		}
	}
	
	static class OperatorParser extends LazyChoice{

		private static final long serialVersionUID = 950963055579566582L;

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
	
	static class OperatorAndOperandParser extends LazyChain{

		private static final long serialVersionUID = -4010004059839314592L;

		@Override
		public Parsers getLazyParsers() {
			return new Parsers(
				new OperatorParser(),
				new NumberParser()
			);
		}
	}
}