package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class LineNumber extends IntegerValue<LineNumber>{

  public LineNumber(int value) {
    super(value);
  }
  
  public LineNumber(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public LineNumber create(int i) {
    return new LineNumber(i);
  }

  @Override
  public LineNumber create(IntegerValue<?> i) {
    return new LineNumber(i);
  }
}