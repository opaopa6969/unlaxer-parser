package org.unlaxer.parser.combinator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.PropagatableSource;

public abstract class AbstractPropagatableSource extends ConstructedSingleChildParser 
	implements PropagatableSource{
	
	private static final long serialVersionUID = 2018378349322401970L;
	
	public AbstractPropagatableSource(Name name, Parser child) {
		super(name, child);
	}

	public AbstractPropagatableSource(Parser child) {
		super(child);
	}

	/**
	 * The merged invert value of the latest {@link #parse} call on this instance, in any context or
	 * thread. Parsing passes the value to the child as an argument; no parser of this library reads
	 * the field. Only {@link org.unlaxer.parser.PropagatableDestination#getInvertMatchFromParent()},
	 * the TokenPrinter "(inverted)" marker, does, so that marker reports the last call rather than
	 * the printed token: another context on a shared graph, or a later call in the same parse, may
	 * have overwritten it. Tokens and parse results do not depend on it (#1007).
	 */
	public Optional<Boolean> computedInvertMatch = Optional.empty();
	
	@Override
	public final Parsed parse(ParseContext parseContext, TokenKind tokenKind,boolean invertMatch) {
		
		boolean merge = computeInvertMatch(invertMatch, getThisInvertedSourceValue());
		setComputedInvertMatch(merge);
		return parseDelegated(parseContext, tokenKind, merge);
		
	}
	
	public abstract Parsed parseDelegated(ParseContext parseContext, TokenKind tokenKind,boolean invertMatch);
	
	public boolean getInvertMatchToChild(){
		return computedInvertMatch.orElseThrow(IllegalStateException::new);
	}
	
	public void setComputedInvertMatch(boolean computedInvertMatch){
		this.computedInvertMatch = Optional.of(computedInvertMatch);
	}
	
}