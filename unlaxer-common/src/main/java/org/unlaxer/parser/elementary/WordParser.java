package org.unlaxer.parser.elementary;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.CursorRange;
import org.unlaxer.Name;
import org.unlaxer.Source;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.util.Slicer;


public class WordParser extends AbstractTokenParser implements TerminalSymbol{
	
	private static final long serialVersionUID = 77970028727135376L;
	public final Source word;
	public final boolean ignoreCase;
	
	public WordParser(String word) {
		this(null , word, false);
	}
	
	public WordParser(Name name,String word) {
		this(name, word, false);
	}
	
	public WordParser(String word, boolean ignoreCase) {
		this(null,word,ignoreCase);
	}

	public WordParser(Name name , String word, boolean ignoreCase) {
		this(name , StringSource.createDetachedSource(word) , ignoreCase);
	}
	
  public WordParser(Source word) {
    this(null , word, false);
  }
  
  public WordParser(Name name,Source word) {
    this(name, word, false);
  }
  
  public WordParser(Source word, boolean ignoreCase) {
    this(null,word,ignoreCase);
  }

	
  public WordParser(Name name , Source word, boolean ignoreCase) {
    super(name);
    this.word = word;
    this.ignoreCase = ignoreCase;
  }


	@Override
	public Token getToken(ParseContext parseContext, TokenKind tokenKind,boolean invertMatch) {
	  
		
		CodePointLength length = word.codePointLength();
		
		
		if(length.isZero()) {
			return new Token(tokenKind ,
			  parseContext.peek(parseContext.getConsumedPosition(), new CodePointLength(0))
				, this
			);
		}
		
		Source peeked = parseContext.peek(tokenKind , length);
		
		return 
			equals(word,peeked) ^ invertMatch ?
			new Token(tokenKind , peeked, this):
			Token.empty(tokenKind ,parseContext.getCursor(TokenKind.consumed), this);
	}
	
	boolean equals(Source targetString , Source baseString){
		return ignoreCase ? 
				targetString.equalsIgnoreCase(baseString):
				targetString.equals(baseString);
	}
	
	public WordParser slice(Consumer<Slicer> slicerEffector){
		Slicer slicer = new Slicer(word);
		slicerEffector.accept(slicer);
		return new WordParser(slicer.get(),ignoreCase);
	}

	
	public WordParser slice(BeginSpecifier beginSpecifier , EndSpecifier endSpecifier){
		return slice(beginSpecifier, endSpecifier,false);
	}
	
	public WordParser slice(
			BeginSpecifier beginSpecifier ,
			EndSpecifier endSpecifier,
			boolean reverse){
		
		return new WordParser(
				new Slicer(word).begin(beginSpecifier).end(endSpecifier).reverse(reverse) ,
				ignoreCase);
	}
	
	public WordParser slice(
			RangeSpecifier rangeSpecifier){
		return slice(rangeSpecifier, false);
	}
	
	public WordParser slice(
			RangeSpecifier rangeSpecifier,
			boolean reverse){
		
		return new WordParser(
				new Slicer(word).range(rangeSpecifier).reverse(reverse) ,
				ignoreCase);
	}
	
	public WordParser effect(WordEffector wordEffector){
		return new WordParser(wordEffector.apply(word) , ignoreCase);
	}
	
	public interface BeginSpecifier extends Function<Source, CodePointIndex>{};
	public interface EndSpecifier extends Function<Source, CodePointIndex>{};
	public interface RangeSpecifier extends Function<Source,CursorRange>{}; 
	public interface WordEffector extends UnaryOperator<Source>{}
	
	public String toString() {
		return "wordParser("+word+")";
	}; 
	
  @Override
  public Optional<String> expectedDisplayText() {
    return Optional.of("'" + escape(word.sourceAsString()) + "'");
  }

  private String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("'", "\\'");
  }
	
	
}
