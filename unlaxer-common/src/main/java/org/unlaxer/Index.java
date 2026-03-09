package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class Index extends IntegerValue<Index>{

  public Index(int value) {
    super(value);
  }
  public Index(IntegerValue<?> value) {
    super(value);
  }
  
  @Override
  public Index create(int i) {
    return new Index(i);
  }

  @Override
  public Index create(IntegerValue<?> i) {
    return new Index(i);
  }
}