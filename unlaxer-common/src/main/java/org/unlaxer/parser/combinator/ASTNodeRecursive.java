package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;
import org.unlaxer.Tag;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public class ASTNodeRecursive extends RecursiveTagWrapper{

	private static final long serialVersionUID = 43537045136172130L;

	public ASTNodeRecursive(Name name, Parser child) {
		super(name, child);
	}

	public ASTNodeRecursive(Parser child) {
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
		return RecursiveMode.containsRoot;
	}
}