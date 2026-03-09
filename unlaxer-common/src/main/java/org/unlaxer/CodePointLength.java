package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class CodePointLength extends IntegerValue<CodePointLength>{
  
  public CodePointLength(int value) {
    super(value);
  }
  
  public CodePointLength(IntegerValue<?> value) {
    super(value);
  }

  
  @Override
  public CodePointLength create(int i) {
    return new CodePointLength(i);
  }

  @Override
  public CodePointLength create(IntegerValue<?> i) {
    return new CodePointLength(i);
  }
  
  public CodePointOffset toOffset() {
    return new CodePointOffset(value());
  }
}