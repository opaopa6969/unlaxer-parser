package org.unlaxer.parser.elementary;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.ZeroOrMore;

public class WildCardStringTerninatorParser extends ZeroOrMore implements StaticParser {

	private static final long serialVersionUID = -3386398191774012367L;

	static final Parser wildCardStringParser = new WildCardStringParser();

	public WildCardStringTerninatorParser(boolean isTerminatorIsMatchOnly, String... excludes) {
		super(wildCardStringParser, createTerminator(isTerminatorIsMatchOnly, excludes));
	}

	public WildCardStringTerninatorParser(String... excludes) {
		this(true, excludes);
	}

	public WildCardStringTerninatorParser(boolean isTerminatorIsMatchOnly, Parser terminator) {
		super(wildCardStringParser, isTerminatorIsMatchOnly ? new MatchOnly(terminator) : terminator);
	}

	public WildCardStringTerninatorParser(Parser terminator) {
		this(true, terminator);
	}

	public WildCardStringTerninatorParser(Name name, boolean isTerminatorIsMatchOnly, String... excludes) {
		super(name, wildCardStringParser, createTerminator(isTerminatorIsMatchOnly, excludes));
	}

	public WildCardStringTerninatorParser(Name name, String... excludes) {
		super(name, wildCardStringParser, createTerminator(true, excludes));
	}

	static Parser createTerminator(boolean isTerminatorIsMatchOnly, String[] terminators) {

		Stream<Parser> parserStream = Stream.of(terminators).map(WordParser::new);

		if (isTerminatorIsMatchOnly) {
			parserStream = parserStream.map(MatchOnly::new);
		}

		List<Parser> parsers = parserStream.collect(Collectors.toList());

		Choice choice = new Choice(Parsers.of(parsers));

		return choice;
	}

	@Override
	public Parsed parse(ParseContext parseContext) {
		parseContext.getCurrent().setResetMatchedWithConsumed(false);
		return super.parse(parseContext);
	}
}