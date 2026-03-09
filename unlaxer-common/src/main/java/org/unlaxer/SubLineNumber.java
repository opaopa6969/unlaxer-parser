package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class SubLineNumber extends IntegerValue<SubLineNumber>{

  public SubLineNumber(int value) {
    super(value);
  }
  
  public SubLineNumber(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public SubLineNumber create(int i) {
    return new SubLineNumber(i);
  }

  @Override
  public SubLineNumber create(IntegerValue<?> i) {
    return new SubLineNumber(i);
  }
}