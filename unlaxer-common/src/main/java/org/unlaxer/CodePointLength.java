package org.unlaxer;

import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
public class CodePointLength extends IntegerValue<CodePointLength>{
  
  public static final CodePointLength ZERO = new CodePointLength(0);

  public static CodePointLength of(int value) {
    return new CodePointLength(value);
  }

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