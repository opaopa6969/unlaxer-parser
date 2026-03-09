package org.unlaxer;

import org.unlaxer.Cursor.EndExclusiveCursor;

public class RangeWithCursor extends Range{
  
  public final EndExclusiveCursor cursor;

  public RangeWithCursor(EndExclusiveCursor cursor) {
    super();
    this.cursor = cursor;
  }

  public RangeWithCursor(int startIndexInclusive, int endIndexExclusive , EndExclusiveCursor cusCursor) {
    super(startIndexInclusive, endIndexExclusive);
    this.cursor = cusCursor;
  }

  public RangeWithCursor(int startIndexInclusive , EndExclusiveCursor cursor) {
    super(startIndexInclusive);
    this.cursor = cursor;
  }
}