package org.unlaxer.parser.combinator;

import java.util.List;
import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.RecursiveMode;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.SpaceDelimitor;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public abstract class WhiteSpaceDelimitedLazyChain extends LazyChain {

	private static final long serialVersionUID = -324234946352474224L;

	public WhiteSpaceDelimitedLazyChain() {
		super();
	}

	public WhiteSpaceDelimitedLazyChain(Name name) {
		super(name);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
		return super.parse(parseContext, tokenKind, invertMatch);
	}
	
	@Override
	public Optional<RecursiveMode> getNotAstNodeSpecifier() {
		return Optional.empty();
	}

	static final SpaceDelimitor spaceDelimitor = new SpaceDelimitor();
	static {
		spaceDelimitor.addTag(NodeKind.notNode.getTag());
	}

	@Override
	public void prepareChildren(Parsers childrenContainer) {
		
		if(childrenContainer.isEmpty()){
			List<Parser> lazyParsers = getLazyParsers();
//			if(lazyParsers == null) {
//				initialize();
//				lazyParsers = getLazyParsers();
//			}
			childrenContainer.add(spaceDelimitor);
			for (Parser parser : lazyParsers) {
				childrenContainer.add(parser);
				childrenContainer.add(spaceDelimitor);
			}
		}
	}
}