package org.unlaxer.parser.elementary;

import org.unlaxer.Name;
import org.unlaxer.parser.StaticParser;

public class WildCardStringParser extends SingleStringParser implements StaticParser {

	private static final long serialVersionUID = -5091702465454778408L;

	public WildCardStringParser() {
		super();
	}

	public WildCardStringParser(Name name) {
		super(name);
	}

	@Override
	public boolean isMatch(String target) {
		return true;
	}

}