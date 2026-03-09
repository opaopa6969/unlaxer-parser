package org.unlaxer;

import org.unlaxer.base.IntegerValue;

public class StringIndexWithNegativeValue extends IntegerValue<StringIndexWithNegativeValue>{

  public StringIndexWithNegativeValue(int value) {
    super(value);
  }
  
  public StringIndexWithNegativeValue(IntegerValue<?> value) {
    super(value);
  }
  
  public StringIndex toStringIndex() {
    return new StringIndex(value());
  }
  
  @Override
  public StringIndexWithNegativeValue create(int i) {
    return new StringIndexWithNegativeValue(i);
  }

  @Override
  public StringIndexWithNegativeValue create(IntegerValue<?> i) {
    return new StringIndexWithNegativeValue(i);
  }

  
}