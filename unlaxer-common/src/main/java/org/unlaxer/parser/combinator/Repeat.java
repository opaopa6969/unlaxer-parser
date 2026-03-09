package org.unlaxer.parser.combinator;

import org.unlaxer.Name;
import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.parser.Parser;

public class Repeat extends ChildOccursWithTerminator {
	
	private static final long serialVersionUID = -5296440022640156880L;
	
	public final int minInclusive , maxInclusive;

	public Repeat(Class<? extends Parser> inner , int minInclusive , int maxInclusive) {
		super(inner);
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
	}
	
//	public Repeat(Name name , Parser inner , int minInclusive , int maxInclusive) {
//		super(name , inner);
//		this.minInclusive = minInclusive;
//		this.maxInclusive = maxInclusive;
//	}

	
	private Repeat(Name name , Class<? extends Parser> inner , int minInclusive , int maxInclusive , Class<? extends Parser> terminator) {
		super(name , inner,terminator);
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
	}

	private Repeat(Name name , Parser inner , int minInclusive , int maxInclusive,Parser terminator) {
		super(name , inner , terminator);
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
	}
	
	public Repeat(ASTNodeKind astNodeKind ,Class<? extends Parser> inner , int minInclusive , int maxInclusive) {
		super(inner);
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
		addTag(astNodeKind.tag());
	}

//	public Repeat(ASTNodeKind astNodeKind ,Parser inner , int minInclusive , int maxInclusive) {
//		super(inner);
//		this.minInclusive = minInclusive;
//		this.maxInclusive = maxInclusive;
//		addTag(astNodeKind.tag());
//	}

	public Repeat(Name name , ASTNodeKind astNodeKind ,Class<? extends Parser> inner , int minInclusive , int maxInclusive) {
		super(name , inner);
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
		addTag(astNodeKind.tag());
	}

//	public Repeat(Name name , ASTNodeKind astNodeKind ,Parser inner , int minInclusive , int maxInclusive) {
//		super(name , inner);
//		this.minInclusive = minInclusive;
//		this.maxInclusive = maxInclusive;
//		addTag(astNodeKind.tag());
//	}
	
	private Repeat(Name name , ASTNodeKind astNodeKind ,Class<? extends Parser> inner , int minInclusive , int maxInclusive , 
			Class<? extends Parser> terminator) {
		super(name , inner,terminator);
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
		addTag(astNodeKind.tag());
	}
	
//	private Repeat(Name name , ASTNodeKind astNodeKind ,Parser inner , int minInclusive , int maxInclusive,Parser terminator) {
//		super(name , inner,terminator);
//		this.minInclusive = minInclusive;
//		this.maxInclusive = maxInclusive;
//		addTag(astNodeKind.tag());
//	}

	
	public Repeat newWithTerminator(Parser terminator){
		return new Repeat(getName() , getChild(), minInclusive , maxInclusive , terminator);
	}


	@Override
	public int min() {
		return minInclusive;
	}

	@Override
	public int max() {
		return maxInclusive;
	}
}
