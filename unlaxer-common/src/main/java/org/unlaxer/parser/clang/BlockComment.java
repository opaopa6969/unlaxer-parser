package org.unlaxer.parser.clang;

import org.unlaxer.Name;
import org.unlaxer.parser.ChainParsers;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.WildCardStringTerninatorParser;
import org.unlaxer.parser.elementary.WordParser;

public class BlockComment extends LazyChain{
  
  // parser for /* */ comment

	private static final long serialVersionUID = 2143565367402566927L;

	public BlockComment() {
		super();
	}
	public BlockComment(Name name) {
		super(name);
	}

	@Override
	public Parsers getLazyParsers() {
		return new ChainParsers(
			new WordParser("/*"),
			new WildCardStringTerninatorParser("*/"),
			new WordParser("*/")
		);
	}
}