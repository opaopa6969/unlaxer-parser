package org.unlaxer.parser.combinator;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.stream.Collectors;

import org.unlaxer.CursorRange;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.RangedContent;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;

public abstract class ContainerParser<T> extends NoneChildParser {

	private static final long serialVersionUID = 358784075416463053L;
	
	public ContainerParser() {
		super();
	}

	public ContainerParser(Name name) {
		super(name);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		Token token = Token.empty(
				tokenKind, 
				parseContext.getCursor(TokenKind.consumed),
				this
		);
		parseContext.getCurrent().addToken(token);
		return new Parsed(token);
	}
	
	public abstract T get();
	
	public abstract RangedContent<T> get(CursorRange position);
	
	@SuppressWarnings("unchecked")
	public static <T> List<RangedContent<T>> getRangedContents(Token rootToken ){
		return getRangedContents(
				rootToken, 
				(Class<ContainerParser<T>>) MethodHandles.lookup().lookupClass());
	}

	public static <T> List<RangedContent<T>> getRangedContents(Token rootToken ,
		Class<? extends ContainerParser<T>> targetContainerParserClass){
		
		return rootToken.flatten().stream()
			.filter(token-> targetContainerParserClass.isInstance(token.parser))
			.map(token->targetContainerParserClass.cast(token.parser).get(token.getSource().cursorRange()))
			.collect(Collectors.toList());
	}
	
}