package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.Tag;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public class ASTNode extends TagWrapper{

	private static final long serialVersionUID = 4123827171598056965L;

	public ASTNode(Name name, Parser child) {
		super(name, child);
	}

	public ASTNode(Parser child) {
		super(child);
	}

	@Override
	public Parser getThisParser() {
		return getChild();
	}

	@Override
	public Tag getTag() {
		return NodeKind.node.getTag();
	}

	@Override
	public TagWrapperAction getAction() {
		return TagWrapperAction.add;
	}

}