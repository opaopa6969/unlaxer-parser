package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class Count extends IntegerValue<Count>{

  public Count(int value) {
    super(value);
  }
  
  public Count(IntegerValue<?> value) {
    super(value);
  }
  
  @Override
  public Count create(int i) {
    return new Count(i);
  }

  @Override
  public Count create(IntegerValue<?> i) {
    return new Count(i);
  }

}