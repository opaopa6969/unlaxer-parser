package org.unlaxer;

import org.unlaxer.base.IntegerValue;

public class CodePointIndexInLine extends IntegerValue<CodePointIndexInLine>{

  public CodePointIndexInLine(int value) {
    super(value);
  }
  
  public CodePointIndexInLine(IntegerValue<?> value) {
    super(value);
  }

  @Override
  public CodePointIndexInLine create(int i) {
    return new CodePointIndexInLine(i);
  }

  @Override
  public CodePointIndexInLine create(IntegerValue<?> i) {
    return new CodePointIndexInLine(i);
  }
  
  public CodePointOffset toCodePointOffset() {
    return new CodePointOffset(value());
  }
}