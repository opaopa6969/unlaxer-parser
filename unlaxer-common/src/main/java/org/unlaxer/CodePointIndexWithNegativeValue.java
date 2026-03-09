package org.unlaxer;

import org.unlaxer.base.IntegerValue;

public class CodePointIndexWithNegativeValue extends IntegerValue<CodePointIndexWithNegativeValue>{

  public CodePointIndexWithNegativeValue(int value) {
    super(value);
  }
  
  public CodePointIndexWithNegativeValue(IntegerValue<?> value) {
    super(value);
  }
  
  /**
   * @return CodePointIndex
   * @throws if code less than 0 then throw IllegalArgumentException
   */
  public CodePointIndex toCodePointIndex() {
    return new CodePointIndex(value());
  }
  
  @Override
  public CodePointIndexWithNegativeValue create(int i) {
    return new CodePointIndexWithNegativeValue(i);
  }

  @Override
  public CodePointIndexWithNegativeValue create(IntegerValue<?> i) {
    return new CodePointIndexWithNegativeValue(i);
  }

}