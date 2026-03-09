package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;
import org.unlaxer.Tag;
import org.unlaxer.parser.Parser;

public abstract class RecursiveTagWrapper extends TagWrapper {

	private static final long serialVersionUID = -2669786224048693859L;

	public RecursiveTagWrapper(Name name, Parser child) {
		super(name, child);
		action(child);
	}

	public RecursiveTagWrapper(Parser child) {
		super(child);
		action(child);
	}
	
	@Override
	public abstract Tag getTag();
	
	
	private void action(Parser child) {
		
		RecursiveMode recursiveMode = getRecursiveMode();
		
		switch (getAction()) {
		case add:
			child.addTag(true , recursiveMode , getTag());
			
			break;
		case remove:
			child.removeTag(true , recursiveMode , getTag());
			break;

		default:
			break;
		}
	}
	
	public abstract RecursiveMode getRecursiveMode();
}