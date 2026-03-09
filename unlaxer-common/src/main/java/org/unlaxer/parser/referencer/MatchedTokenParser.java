package org.unlaxer.parser.referencer;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Source;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenPredicators;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.AbstractParser;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.elementary.WordParser.RangeSpecifier;
import org.unlaxer.parser.elementary.WordParser.WordEffector;
import org.unlaxer.util.Slicer;

public class MatchedTokenParser extends AbstractParser{//extends ConstructedSingleChildParserA

	private static final long serialVersionUID = 9212874360894516134L;
	
	Parser targetParser;
	
	RangeSpecifier rangeSpecifier;
	WordEffector wordEffector;
	boolean reverse;
	Consumer<Slicer> slicerEffector;
	// must be tokenPredicator evaluates when all parser constructed.
	Supplier<Predicate<Token>> tokenPredicator;

	public MatchedTokenParser(Parser targetParser) {
		super(Parsers.of(targetParser));
		this.targetParser = targetParser;
		rangeSpecifier = null;
		reverse = false;
		wordEffector = null;
		slicerEffector = null;
		tokenPredicator = ()->tokenPredicator(targetParser);
	}
	
	public MatchedTokenParser(
			Parser targetParser,
			RangeSpecifier rangeSpecifier,
			boolean reverse) {
		super(Parsers.of(targetParser));
		this.targetParser = targetParser;
		this.reverse = reverse;
		this.rangeSpecifier = rangeSpecifier;
		this.wordEffector = null;
		this.slicerEffector = null;
		tokenPredicator = ()->tokenPredicator(targetParser);
	}
	
	public MatchedTokenParser(
			Parser targetParser,
			WordEffector wordEffector
			) {
		super(Parsers.of(targetParser));
		this.targetParser = targetParser;
		this.reverse = false;
		this.rangeSpecifier = null;
		this.wordEffector = wordEffector;
		this.slicerEffector = null;
		tokenPredicator = ()->tokenPredicator(targetParser);
	}

	public MatchedTokenParser(
			Parser targetParser,
			Consumer<Slicer> slicerEffector
			) {
		super(Parsers.of(targetParser));
		this.targetParser = targetParser;
		this.reverse = false;
		this.rangeSpecifier = null;
		this.wordEffector = null;
		this.slicerEffector = slicerEffector;
		tokenPredicator = ()->tokenPredicator(targetParser);
	}
	
	Predicate<Token> tokenPredicator(Parser parser){
		Parser targetParser = (parser instanceof ReferenceParser)?
			((ReferenceParser)parser).getMatchedParser()
				.orElseThrow(()->new IllegalArgumentException("specified matched parser not found yet.")):
			parser;
		return TokenPredicators.parsers(targetParser.getClass());
	}
	
	public enum ScopeVariable{
		matchedToken,
		;
		public Name get(){
			return Name.of(this);
		}
	}
	
	public MatchedTokenParser(Predicate<Token> tokenPredicator) {
		rangeSpecifier = null;
		reverse = false;
		wordEffector = null;
		slicerEffector = null;
		this.tokenPredicator = ()->tokenPredicator;
	}
	
	public MatchedTokenParser(
			Predicate<Token> tokenPredicator,
			RangeSpecifier rangeSpecifier,
			boolean reverse) {
		this.reverse = reverse;
		this.rangeSpecifier = rangeSpecifier;
		this.wordEffector = null;
		this.slicerEffector = null;
		this.tokenPredicator = ()->tokenPredicator;
	}
	
	public MatchedTokenParser(
			Predicate<Token> tokenPredicator,
			WordEffector wordEffector
			) {
		this.reverse = false;
		this.rangeSpecifier = null;
		this.wordEffector = wordEffector;
		this.slicerEffector = null;
		this.tokenPredicator = ()->tokenPredicator;
	}

	public MatchedTokenParser(
			Predicate<Token> tokenPredicator,
			Consumer<Slicer> slicerEffector
			) {
		this.reverse = false;
		this.rangeSpecifier = null;
		this.wordEffector = null;
		this.slicerEffector = slicerEffector;
		this.tokenPredicator = ()->tokenPredicator;
	}
	
	
	


	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		if(targetParser instanceof ReferenceParser){
			targetParser = ((ReferenceParser)targetParser).getMatchedParser()
				.orElseThrow(()->new IllegalArgumentException("specified matched parser not found yet."));
		}
		
		List<Token> matchedTokens = parseContext.getList(this, ScopeVariable.matchedToken.get(),Token.class);
		
		if(matchedTokens.isEmpty()){
			matchedTokens = parseContext.getMatchedTokens(tokenPredicator.get());
			parseContext.put(this, ScopeVariable.matchedToken.get() , matchedTokens);
		}
		
		for (Token token : matchedTokens) {
			
			Source source = token.getSource();
			if(source.isEmpty()) {
			  continue;
			}
      WordParser wordParser = new WordParser(source);
			
			if(rangeSpecifier != null){
				
				wordParser = wordParser.slice(rangeSpecifier,reverse);
			}else if(wordEffector != null){
				
				wordParser = wordParser.effect(wordEffector);
			}else if(slicerEffector != null){
				
				wordParser = wordParser.slice(slicerEffector);
			}
			
			Parsed parsed = wordParser.parse(parseContext,tokenKind,invertMatch);
			if(parsed.isSucceeded()) {
				return parsed;
			}
		}
		return Parsed.FAILED;
	}

	/* previous implementation. pasting for reference.
	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		if(targetParser instanceof ReferenceParser){
			targetParser = ((ReferenceParser)targetParser).getMatchedParser()
				.orElseThrow(()->new IllegalArgumentException("specified matched parser not found yet."));
		}
		
		Optional<Token> matchedToken = parseContext.get(this, ScopeVariable.matchedToken.get(),Token.class);
		
		if(false == matchedToken.isPresent()){
			matchedToken = parseContext.getMatchedToken((parser)->parser.equals(targetParser));
			matchedToken.ifPresent(token->parseContext.put(this, ScopeVariable.matchedToken.get() , token));
		}
		Optional<WordParser> wordParser = matchedToken
			.flatMap(Token::getToken)
			.map(WordParser::new);
		
		if(rangeSpecifier != null){
			
			wordParser = wordParser.map(original->original.slice(rangeSpecifier,reverse));
		}else if(wordEffector != null){
			
			wordParser = wordParser.map(original->original.effect(wordEffector));
		}else if(slicerEffector != null){
			
			wordParser = wordParser.map(original->original.slice(slicerEffector));
		}
		
		return wordParser
				.map(parser->parser.parse(parseContext,tokenKind,invertMatch))
				.orElse(Parsed.FAILED);
	}
	*/


	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.none;
	}

	@Override
	public Parser createParser() {
		return this;
	}
	
	public MatchedTokenParser sliceWithWord(RangeSpecifier rangeSpecifier){
		return slice(rangeSpecifier,false);
	}
	
	public MatchedTokenParser slice(
			RangeSpecifier rangeSpecifier,
			boolean reverse){
		
		return new MatchedTokenParser(targetParser , rangeSpecifier ,reverse);
	}
	
	public MatchedTokenParser effect(WordEffector wordEffector){
		return new MatchedTokenParser(targetParser,wordEffector);
	}
	
	public MatchedTokenParser slice(Consumer<Slicer> slicerEffector){
		return new MatchedTokenParser(targetParser,slicerEffector);
	}

	@Override
	public void prepareChildren(Parsers childrenContainer) {
	}

}
