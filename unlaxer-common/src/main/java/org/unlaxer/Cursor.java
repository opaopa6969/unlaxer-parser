package org.unlaxer;

import org.unlaxer.Source.SourceKind;

public interface Cursor<T extends Cursor<T>> {
  
  public enum CursorKind{
    startInclusive,
    endExclusive
  }

  PositionResolver positionResolver();
  
//	NameSpecifier nameSpace();
//
//	T setNameSpace(NameSpecifier nameSpace);

	LineNumber lineNumber();
	
//	SubLineNumber getLineNumberOnThisSequence();

//	T setLineNumber(LineNumber lineNumber);
	
//  T incrementLineNumber();

	CodePointIndex position();
	
	CodePointIndex positionInSub();
	
  CodePointIndex positionInRoot();

	
//	SubCodePointIndex getPositionOnThisSequence();

	T setPosition(CodePointIndex position);
	
  T incrementPosition(); 

	T addPosition(CodePointOffset adding);

	CodePointIndexInLine positionInLine();

//	T setPositionInLine(CodePointIndexInLine positionInLine);
	
//	T resolveLineNumber(RootPositionResolver rootPositionResolver);
	
	CodePointOffset offsetFromRoot();
	
	T newWithAddPosition(CodePointOffset adding);
	
	T copy();
	
	CursorKind cursorKind();
	
	SourceKind sourceKind();
	
	public interface EndExclusiveCursor extends Cursor<EndExclusiveCursor>{
	  
	  default CursorKind cursorKind() {
	    return CursorKind.endExclusive;
	  }
	}
	
	public interface StartInclusiveCursor extends Cursor<StartInclusiveCursor>{
    
    default CursorKind cursorKind() {
      return CursorKind.startInclusive;
    }
  }
}