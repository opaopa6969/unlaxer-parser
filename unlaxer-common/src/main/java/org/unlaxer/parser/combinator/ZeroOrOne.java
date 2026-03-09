package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.parser.Parser;

public class ZeroOrOne extends Optional{

	private static final long serialVersionUID = 7544142678234641766L;

	public ZeroOrOne(Name name, Class<? extends Parser> inner) {
		super(name, inner);
	}

	public ZeroOrOne(Class<? extends Parser> inner) {
		super(inner);
	}
	
	public ZeroOrOne(Name name, ASTNodeKind astNodeKind ,  Class<? extends Parser> inner) {
		super(name, inner);
		addTag(astNodeKind.tag());
	}

	public ZeroOrOne(ASTNodeKind astNodeKind , Class<? extends Parser> inner) {
		super(inner);
		addTag(astNodeKind.tag());
	}
	
	 public ZeroOrOne(Name name, Parser inner) {
	    super(name, inner);
	  }

	  public ZeroOrOne(Parser inner) {
	    super(null,inner);
	  }
	  
	  public ZeroOrOne(Name name, ASTNodeKind astNodeKind ,  Parser inner) {
	    super(name, inner);
	    addTag(astNodeKind.tag());
	  }

	  public ZeroOrOne(ASTNodeKind astNodeKind , Parser inner) {
	    super(null,inner);
	    addTag(astNodeKind.tag());
	  }
	  
	  public ZeroOrOne(Name name , Class<? extends Parser> inner , Class<? extends Parser> terminator) {
      super(name , Parser.get(inner) , Parser.get(terminator));
    }
    
    public ZeroOrOne(Name name , Parser inner , Parser terminator) {
      super(name , inner , terminator);
    }




}