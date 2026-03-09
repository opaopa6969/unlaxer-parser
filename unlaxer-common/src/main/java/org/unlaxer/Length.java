package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class Length extends IntegerValue<Length>{

  public Length(int value) {
    super(value);
  }
  
  public Length(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public Length create(int i) {
    return new Length(i);
  }

  @Override
  public Length create(IntegerValue<?> i) {
    return new Length(i);
  }

}