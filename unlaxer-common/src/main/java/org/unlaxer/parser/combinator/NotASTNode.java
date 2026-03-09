package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Tag;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public class NotASTNode extends TagWrapper{

	private static final long serialVersionUID = 5794276041580747044L;

	public NotASTNode(Name name, Parser child) {
		super(name, child);
	}

	public NotASTNode(Parser child) {
		super(child);
	}

	@Override
	public Parser getThisParser() {
		return this;
	}

	@Override
	public Tag getTag() {
		return NodeKind.notNode.getTag();
	}

	@Override
	public TagWrapperAction getAction() {
		return TagWrapperAction.add;
	}
}