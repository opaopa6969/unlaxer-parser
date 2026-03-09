package org.unlaxer.parser;


import java.util.Optional;

import org.unlaxer.CursorRange;
import org.unlaxer.ErrorMessage;
import org.unlaxer.Name;
import org.unlaxer.RangedContent;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.combinator.ContainerParser;

public class ErrorMessageParser extends ContainerParser<String> implements TerminalSymbol{
	
	private static final long serialVersionUID = -1442039244922724686L;
	
	String message;
	boolean expectedHintOnly;
	

	public ErrorMessageParser(String message) {
		this(message, false);
	}

	public ErrorMessageParser(String message, boolean expectedHintOnly) {
		super();
		this.message = message;
		this.expectedHintOnly = expectedHintOnly;
	}

	public ErrorMessageParser(String message , Parsers children) {
		this(message, false);
	}

	public ErrorMessageParser(String message , Name name) {
		this(message, name, false);
	}

	public ErrorMessageParser(String message, Name name, boolean expectedHintOnly) {
		super(name);
		this.message = message;
		this.expectedHintOnly = expectedHintOnly;
	}

	public static ErrorMessageParser expected(String message) {
		return new ErrorMessageParser(message, true);
	}

	public static ErrorMessageParser expected(String message, Name name) {
		return new ErrorMessageParser(message, name, true);
	}

	@Override
	public String get() {
		return message;
	}
	
	@Override
	public RangedContent<String> get(CursorRange position) {
		return new ErrorMessage(position, message);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		if (expectedHintOnly) {
			parseContext.startParse(this, parseContext, tokenKind, invertMatch);
			parseContext.endParse(this, Parsed.FAILED, parseContext, tokenKind, invertMatch);
			return Parsed.FAILED;
		}
		return super.parse(parseContext, tokenKind, invertMatch);
	}

	@Override
	public Optional<String> expectedDisplayText() {
		if (expectedHintOnly == false) {
			return Optional.empty();
		}
		if (message == null || message.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(message);
	}

	@Override
	public Parser createParser() {
		return this;
	}
}
