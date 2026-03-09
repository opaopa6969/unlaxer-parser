package org.unlaxer;

import org.unlaxer.base.IntegerValue;
import org.unlaxer.base.MinIntegerValue._MinIntegerValue;

@_MinIntegerValue(0)
public class StringLength extends IntegerValue<StringLength>{
  
  public StringLength(int value) {
    super(value);
  }
  
  public StringLength(IntegerValue<?> value) {
    super(value);
  }
  
  @Override
  public StringLength create(int i) {
    return new StringLength(i);
  }

  @Override
  public StringLength create(IntegerValue<?> i) {
    return new StringLength(i);
  }

  
}