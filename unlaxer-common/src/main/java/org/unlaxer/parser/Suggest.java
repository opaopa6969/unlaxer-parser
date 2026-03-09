package org.unlaxer.parser;

import java.util.Set;
import java.util.TreeSet;

public class Suggest{
	
	public final Set<String> words = new TreeSet<String>();
	public final Parser suggestedBy;
	public Suggest(Parser suggestedBy) {
		super();
		this.suggestedBy = suggestedBy;
	}
	public Set<String> getWords() {
		return words;
	}
	public Parser getSuggestedBy() {
		return suggestedBy;
	}

}