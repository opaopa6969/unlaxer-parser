package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.Tag;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public abstract class TagWrapper extends SingleChildCollectingParser {

	private static final long serialVersionUID = -3962308778072382255L;

	public TagWrapper(Name name, Parser child) {
		super(name, child);
		action(child);
	}

	public TagWrapper(Parser child) {
		super(child);
		action(child);
	}
	
	public abstract Tag getTag();
	
	public enum TagWrapperAction{
		
		add,remove
		
	}
	
	private void action(Parser child) {
		
		switch (getAction()) {
		case add:
			child.addTag(getTag());
			
			break;
		case remove:
			child.removeTag(getTag());
			break;

		default:
			break;
		}
	}
	
	public abstract TagWrapperAction getAction();

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		
		parseContext.startParse(this, parseContext, tokenKind, invertMatch);
		Parsed parsed = getChild().parse(parseContext,tokenKind , invertMatch);
		parseContext.endParse(this , parsed, parseContext, tokenKind, invertMatch);
		return parsed;
	}

}
