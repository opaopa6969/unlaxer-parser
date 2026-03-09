package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.parser.Parser;

public class OneOrMore extends ChildOccursWithTerminator {

	private static final long serialVersionUID = 3883160475654738794L;

	public OneOrMore(Class<? extends Parser> inner) {
		super(()->Parser.get(inner));
	}
	
  public OneOrMore(Parser inner) {
	    super(()->inner);
  }

	
	public  OneOrMore(Name name, Class<? extends Parser> inner, Class<? extends Parser> terminator) {
		super(name, ()->Parser.get(inner), ()->Parser.get(terminator));
	}
	
  public  OneOrMore(Name name, Parser inner, Parser terminator) {
	    super(name, ()->inner, ()->terminator);
  }
  
  public  OneOrMore(Name name, Parser inner) {
    super(name, ()->inner);
  }
	public OneOrMore(Name name, Class<? extends Parser> inner) {
		super(name, ()->Parser.get(inner));
	}
	
	public OneOrMore(ASTNodeKind astNodeKind , Class<? extends Parser> inner) {
		super(()->Parser.get(inner));
		addTag(astNodeKind.tag());
	}
	
	public  OneOrMore(Name name, ASTNodeKind astNodeKind ,  Class<? extends Parser> inner, Class<? extends Parser> terminator) {
		super(name, ()->Parser.get(inner), ()->Parser.get(terminator));
		addTag(astNodeKind.tag());
	}

	public OneOrMore(Name name, ASTNodeKind astNodeKind ,  Class<? extends Parser> inner) {
		super(name, ()->Parser.get(inner));
		addTag(astNodeKind.tag());
	}

	public OneOrMore newWithTerminator(Class<? extends Parser> terminator){
		return new OneOrMore(getName() , getChild().getClass(),terminator);
	}
	
	 public OneOrMore newWithTerminator(Parser terminator){
	    return new OneOrMore(getName() , getChild(),terminator);
	  }

	
	@Override
	public int min() {
		return 1;
	}

	@Override
	public int max() {
		return Integer.MAX_VALUE;
	}

}
