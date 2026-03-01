package org.unlaxer.parser;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.unlaxer.CodePointIndex;
import org.unlaxer.Source;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TransactionElement;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.elementary.AbstractTokenParser;

public abstract class SuggestableParser extends AbstractTokenParser implements TerminalSymbol {
	
	private static final long serialVersionUID = -7966896868712698646L;
	
	public final boolean ignoreCase;
	public final List<Source> targetStrings;
	
	public SuggestableParser(boolean ignoreCase, String... targetStrings) {
		super();
		this.ignoreCase = ignoreCase;
		this.targetStrings = Stream.of(targetStrings)
		    .map(StringSource::createDetachedSource)
		    .collect(Collectors.toList());
	}

	@Override
	public Token getToken(ParseContext parseContext,TokenKind tokenKind ,boolean invertMatch) {
		
		for (Source targetString : targetStrings) {
			Source peeked = parseContext.peek(tokenKind , targetString.codePointLength());
			if(equals(targetString.toString(),peeked.toString())){
				return new Token(tokenKind ,  peeked, this);
			}
		}
//		addSuggests(parseContext);
		
		return Token.empty(tokenKind , 
		    parseContext.getCursor(TokenKind.consumed),this);
	}
	
	//FIXME!
	@SuppressWarnings("unused")
	private void addSuggests(ParseContext parseContext) {
		TransactionElement current = parseContext.getCurrent();
		CodePointIndex position = current.getPosition(TokenKind.consumed);
		for (Source targetString : targetStrings) {
		  Source peeked = parseContext.peek(TokenKind.consumed ,targetString.codePointLength());
			if(peeked.isPresent()){
				continue;
			}
//			String peekWithMax = parseContext.peekWithMax(targetString.length()-1);
//			if(targetString.startsWith(peekWithMax)){
//				parseContext.addSuggests(this , position, targetString);
//			}
		}
	}
	
	public Optional<Suggest> getSuggests(String test){
		
		Suggest suggests = new Suggest(this);
		
		//TODO camel case matching, ignore case
		for(int endIndex= test.length() ; endIndex > 0 ; endIndex--){
			String currentTest = test.substring(0, endIndex);
			for(Source targetString :targetStrings){
				if(targetString.startsWith(currentTest)){
					suggests.words.add(targetString.toString());
				}
			}
			if(suggests.words.size() >0){
				break;
			}
		}
		return suggests.words.size() ==0 ? 
				Optional.empty():
				Optional.of(suggests);
	}
	
	public abstract String getSuggestString(String matchedString);

	@Override
	public Optional<String> expectedDisplayText() {
		if(targetStrings.isEmpty()){
			return Optional.empty();
		}
		return Optional.of(quote(targetStrings.get(0).toString()));
	}

	@Override
	public List<String> expectedDisplayTexts() {
		return targetStrings.stream()
		    .map(Source::toString)
		    .map(this::quote)
		    .collect(Collectors.toList());
	}

	boolean equals(String targetString , String baseString){
		return ignoreCase ? 
				targetString.equalsIgnoreCase(baseString):
				targetString.equals(baseString);
	}

	String quote(String token){
		return "'".concat(token).concat("'");
	}
}
