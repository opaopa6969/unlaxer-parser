package org.unlaxer;

import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.Source.SourceKind;

public class EndExclusiveCursorImpl extends AbstractCursorImpl<EndExclusiveCursor> implements EndExclusiveCursor{
  
  public EndExclusiveCursorImpl(SourceKind sourceKind, PositionResolver positionResolver,
      CodePointIndex position , CodePointOffset offsetFromRoot) {
    super(CursorKind.endExclusive, sourceKind, positionResolver, position , offsetFromRoot);
  }
  
  public EndExclusiveCursorImpl(PositionResolver positionResolver) {
    super(CursorKind.endExclusive,SourceKind.root,positionResolver);
  }
  
  public EndExclusiveCursorImpl(SourceKind sourceKind , PositionResolver positionResolver) {
    super(CursorKind.endExclusive,sourceKind,positionResolver);
  }
  
  public EndExclusiveCursorImpl(EndExclusiveCursor cursor) {
    super(cursor);
  }
  
  public EndExclusiveCursorImpl(StartInclusiveCursor cursor) {
    super(cursor);
  }

  @Override
  EndExclusiveCursor thisObject() {
    return this;
  }

  @Override
  public EndExclusiveCursor copy() {
    return new EndExclusiveCursorImpl(sourceKind , positionResolver , position , offsetFromRoot);
  }
  
  @Override
  public EndExclusiveCursor newWithAddPosition(CodePointOffset adding) {
    return copy().addPosition(adding);
  }
  
}