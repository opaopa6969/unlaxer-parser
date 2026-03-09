package org.unlaxer;

import org.unlaxer.base.IntegerValue;

public class Size extends IntegerValue<Size>{

  public Size(int value) {
    super(value);
  }
  public Size(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public Size create(int i) {
    return new Size(i);
  }

  @Override
  public Size create(IntegerValue<?> i) {
    return new Size(i);
  }
}