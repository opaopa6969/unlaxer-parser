package org.unlaxer.parser.referencer;

import java.util.Optional;
import java.util.function.Consumer;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.ConstructedSingleChildParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.elementary.WordParser.RangeSpecifier;
import org.unlaxer.parser.elementary.WordParser.WordEffector;
import org.unlaxer.util.Slicer;

public class OldMatchedTokenParser extends ConstructedSingleChildParser{

	private static final long serialVersionUID = 9212874360894516134L;
	
	Parser targetParser;
	
	RangeSpecifier rangeSpecifier;
	WordEffector wordEffector;
	boolean reverse;
	Consumer<Slicer> slicerEffector;

	public OldMatchedTokenParser(Parser targetParser) {
		super(targetParser);
		this.targetParser = targetParser;
		rangeSpecifier = null;
		reverse = false;
		wordEffector = null;
		slicerEffector = null;
	}
	
	public OldMatchedTokenParser(
			Parser targetParser,
			RangeSpecifier rangeSpecifier,
			boolean reverse) {
		super(targetParser);
		this.targetParser = targetParser;
		this.reverse = reverse;
		this.rangeSpecifier = rangeSpecifier;
		this.wordEffector = null;
		this.slicerEffector = null;
	}
	
	public OldMatchedTokenParser(
			Parser targetParser,
			WordEffector wordEffector
			) {
		super(targetParser);
		this.targetParser = targetParser;
		this.reverse = false;
		this.rangeSpecifier = null;
		this.wordEffector = wordEffector;
		this.slicerEffector = null;
		
	}

	public OldMatchedTokenParser(
			Parser targetParser,
			Consumer<Slicer> slicerEffector
			) {
		super(targetParser);
		this.targetParser = targetParser;
		this.reverse = false;
		this.rangeSpecifier = null;
		this.wordEffector = null;
		this.slicerEffector = slicerEffector;
		
	}
	
	public enum ScopeVariable{
		matchedToken,
		;
		public Name get(){
			return Name.of(this);
		}
	}



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
			.map(Token::getSource)
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

	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.none;
	}

	@Override
	public Parser createParser() {
		return this;
	}
	
	public OldMatchedTokenParser sliceWithWord(RangeSpecifier rangeSpecifier){
		return slice(rangeSpecifier,false);
	}
	
	public OldMatchedTokenParser slice(
			RangeSpecifier rangeSpecifier,
			boolean reverse){
		
		return new OldMatchedTokenParser(targetParser , rangeSpecifier ,reverse);
	}
	
	public OldMatchedTokenParser effect(WordEffector wordEffector){
		return new OldMatchedTokenParser(targetParser,wordEffector);
	}
	
	public OldMatchedTokenParser slice(Consumer<Slicer> slicerEffector){
		return new OldMatchedTokenParser(targetParser,slicerEffector);
	}

}
