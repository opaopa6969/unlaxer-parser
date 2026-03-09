package org.unlaxer;

import org.unlaxer.base.IntegerValue;
import org.unlaxer.base.MinIntegerValue._MinIntegerValue;

@_MinIntegerValue(0)
public class StringIndex extends IntegerValue<StringIndex>{

  public StringIndex(int value) {
    super(value);
  }
  
  public StringIndex(IntegerValue<?> value) {
    super(value);
  }
  
  @Override
  public StringIndex create(int i) {
    return new StringIndex(i);
  }

  @Override
  public StringIndex create(IntegerValue<?> i) {
    return new StringIndex(i);
  }
}