package org.unlaxer.parser.combinator;

import java.util.Optional;
import java.util.function.Supplier;

import org.unlaxer.Name;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;


public abstract class ChildOccursWithTerminator extends ConstructedOccurs	{

	private static final long serialVersionUID = -4411440278839259161L;

	
	Optional<Supplier<Parser>> terminator;

	public ChildOccursWithTerminator(Supplier<Parser> inner) {
		this(inner,null);
	}
	
	public ChildOccursWithTerminator(Class<? extends Parser> inner) {
		this(()->Parser.get(inner),null);
	}
	
	ChildOccursWithTerminator(Supplier<Parser> inner,Supplier<Parser> terminator) {
		this(null,inner,terminator);
	}
	
	public ChildOccursWithTerminator(Name name , Supplier<Parser> inner) {
		this(name , inner, null);
	}
	
	public ChildOccursWithTerminator(Name name , Class<? extends Parser> inner) {
		this(name , ()->Parser.get(inner), null);
	}
	
	public ChildOccursWithTerminator(Name name , Class<? extends Parser> inner , Class<? extends Parser> terminator) {
		this(name , ()->Parser.get(inner) , ()->Parser.get(terminator));
	}
	
	public ChildOccursWithTerminator(Name name , Parser inner , Parser terminator) {
		this(name , ()->inner , ()->terminator);
	}

	
	ChildOccursWithTerminator(Name name , Supplier<Parser> inner,Supplier<Parser> terminator) {
		super(name , terminator == null ? 
				new Parsers(inner):
				new Parsers(inner,terminator));
		this.terminator = Optional.ofNullable(terminator);
	}
	
	@Override
	public Parser createParser() {
		return this;
	}
	
	@Override
	public ChildOccurs getChildOccurs() {
		return ChildOccurs.multi;
	}
	
	@Override
	public Optional<Parser> getTerminator(){
		return terminator.map(Supplier::get);
				
	}
}
