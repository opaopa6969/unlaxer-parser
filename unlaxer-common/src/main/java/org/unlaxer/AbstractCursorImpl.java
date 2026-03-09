package org.unlaxer;

import java.io.Serializable;

import org.unlaxer.Source.SourceKind;

public abstract class AbstractCursorImpl<T extends Cursor<T>> implements Serializable,Cursor<T> {
	
	private static final long serialVersionUID = -4419856259856233251L;
	
	CodePointIndex position;
	final CursorKind cursorKind;
	final SourceKind sourceKind;
	final PositionResolver positionResolver;
	final CodePointOffset offsetFromRoot;
	
	AbstractCursorImpl(CursorKind cursorKind , SourceKind sourceKind , PositionResolver positionResolver) {
		this(cursorKind,sourceKind,positionResolver,new CodePointIndex(0) , new CodePointOffset(0));
	}
	
  AbstractCursorImpl(CursorKind cursorKind , SourceKind sourceKind , 
	     PositionResolver positionResolver , CodePointIndex position , CodePointOffset offsetFromRoot) {
	    super();
	    this.cursorKind = cursorKind;
	    this.sourceKind = sourceKind;
	    this.positionResolver = positionResolver;
	    this.position = position;
	    this.offsetFromRoot = offsetFromRoot;
  }
	
	public AbstractCursorImpl(Cursor<?> cursor) {
		position = cursor.position();
		cursorKind = cursor.cursorKind();
		sourceKind = cursor.sourceKind();
		positionResolver = cursor.positionResolver();
		offsetFromRoot = cursor.offsetFromRoot();
	}
	
  abstract T thisObject();
  
  @Override
  public LineNumber lineNumber() {
    return positionResolver.lineNumberFrom(positionInRoot());
  }
  
  
  @Override
  public CodePointIndex position() {
    return positionInSub();
  }
  
  @Override
  public CodePointIndex positionInSub() {
    return sourceKind.isRoot() ?
        position:
        position.newWithMinus(offsetFromRoot());
  }
  
  @Override
  public CodePointIndex positionInRoot() {
    return position;
  }

  
  @Override
  public T setPosition(CodePointIndex position) {
    this.position = position;
    return thisObject();
  }
  @Override
  public T addPosition(CodePointOffset adding) {
    this.position = position.newWithAdd(adding);
    return thisObject();
  }
  @Override
  public CodePointIndexInLine positionInLine() {
    return positionResolver.codePointIndexInLineFrom(positionInRoot());
  }
  
  @Override
  public T incrementPosition() {
    position = position.newWithIncrements();
    return thisObject();
  }
  
  @Override
  public CursorKind cursorKind() {
    return cursorKind;
  }
  
  @Override
  public PositionResolver positionResolver() {
    return positionResolver;
  }

  @Override
  public SourceKind sourceKind() {
    return sourceKind;
  }
  
  
  
  @Override
  public CodePointOffset offsetFromRoot() {
    return offsetFromRoot;
  }

  @Override
  public String toString() {
    return "[L:" + lineNumber() + ",X:" + positionInLine()+",P:"+position()+"]";
  }
}