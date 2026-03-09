package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Range;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

public class MappedSingleCharacterParserHolder extends ConstructedSingleChildParser {

	private static final long serialVersionUID = 3627630011698847017L;

	public MappedSingleCharacterParserHolder(Name name, MappedSingleCharacterParser child) {
		super(name, child);
	}

	public MappedSingleCharacterParserHolder(MappedSingleCharacterParser child) {
		super(child);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return getChild().parse(parseContext);
	}
	
	
	public MappedSingleCharacterParser newWithout(String matches) {
		return newWithout(matches.toCharArray());
	}

	public MappedSingleCharacterParser newWithout(char... matches) {
		return ((MappedSingleCharacterParser)getChild()).newWithout(matches);
	}

	public MappedSingleCharacterParser newWithout(Name name, char... matches) {
		return ((MappedSingleCharacterParser)getChild()).newWithout(name ,matches);
	}

	public MappedSingleCharacterParser newWithout(Range... matches) {
		return ((MappedSingleCharacterParser)getChild()).newWithout(matches);
	}

	public MappedSingleCharacterParser newWithout(Name name, Range... matches) {
		return ((MappedSingleCharacterParser)getChild()).newWithout(name ,matches);
	}

	
}