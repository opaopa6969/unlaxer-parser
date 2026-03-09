package org.unlaxer;

import org.unlaxer.base.IntegerValue;

public class Offset extends IntegerValue<Offset>{

  public Offset(int value) {
    super(value);
  }
  
  public Offset(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public Offset create(int i) {
    return new Offset(i);
  }

  @Override
  public Offset create(IntegerValue<?> i) {
    return new Offset(i);
  }
  
}