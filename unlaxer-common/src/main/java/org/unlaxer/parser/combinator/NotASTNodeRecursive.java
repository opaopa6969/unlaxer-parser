package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;
import org.unlaxer.Tag;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public class NotASTNodeRecursive extends RecursiveTagWrapper{

	private static final long serialVersionUID = 4308048928115236052L;

	public NotASTNodeRecursive(Name name, Parser child) {
		super(name, child);
	}

	public NotASTNodeRecursive(Parser child) {
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

	@Override
	public RecursiveMode getRecursiveMode() {
		return RecursiveMode.containsRoot;
	}
}