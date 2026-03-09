package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class Depth extends IntegerValue<Depth>{

  public Depth(int value) {
    super(value);
  }
  public Depth(IntegerValue<?> value) {
    super(value);
  }
  
  @Override
  public Depth create(int i) {
    return new Depth(i);
  }

  @Override
  public Depth create(IntegerValue<?> i) {
    return new Depth(i);
  }
  
  public boolean isRoot() {
    return value() == 0;
  }
}