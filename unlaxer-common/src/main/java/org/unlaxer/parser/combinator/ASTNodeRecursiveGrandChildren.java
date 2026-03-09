package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;
import org.unlaxer.Tag;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public class ASTNodeRecursiveGrandChildren extends RecursiveTagWrapper{

	private static final long serialVersionUID = 1084436278534244751L;

	public ASTNodeRecursiveGrandChildren(Name name, Parser child) {
		super(name, child);
	}

	public ASTNodeRecursiveGrandChildren(Parser child) {
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

	@Override
	public RecursiveMode getRecursiveMode() {
		return RecursiveMode.childrenOnly;
	}
}