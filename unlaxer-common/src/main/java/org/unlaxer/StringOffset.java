package org.unlaxer;

import org.unlaxer.base.IntegerValue;

public class StringOffset extends IntegerValue<StringOffset>{

  public StringOffset(int value) {
    super(value);
  }
  
  public StringOffset(IntegerValue<?> value) {
    super(value);
  }
  
  @Override
  public StringOffset create(int i) {
    return new StringOffset(i);
  }

  @Override
  public StringOffset create(IntegerValue<?> i) {
    return new StringOffset(i);
  }

}