package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.parser.Parser;

public class Optional extends ChildOccursWithTerminator {

	private static final long serialVersionUID = 9178853471703766611L;
	
  public Optional(Parser inner) {
    super(()->inner);
  }

	public Optional(Class<? extends Parser> inner) {
		super(inner);
	}

	public Optional(Name name, Class<? extends Parser> inner) {
		super(name, inner);
	}
	
	 public Optional(Name name, Parser inner) {
	    super(name, ()->inner);
	  }

	
	public Optional(ASTNodeKind astNodeKind ,  Class<? extends Parser> inner) {
		super(inner);
		addTag(astNodeKind.tag());
	}

	public Optional(Name name, ASTNodeKind astNodeKind ,  Class<? extends Parser> inner) {
		super(name, inner);
		addTag(astNodeKind.tag());
	}
	
	 public Optional(Name name , Class<? extends Parser> inner , Class<? extends Parser> terminator) {
	    super(name , ()->Parser.get(inner) , ()->Parser.get(terminator));
	  }
	  
	  public Optional(Name name , Parser inner , Parser terminator) {
	    super(name , ()->inner , ()->terminator);
	  }



	@Override
	public int min() {
		return 0;
	}

	@Override
	public int max() {
		return 1;
	}
}
