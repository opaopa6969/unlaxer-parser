package org.unlaxer;

import org.unlaxer.base.MaxIntegerValue._MaxIntegerValue;
import org.unlaxer.base.MinIntegerValue._MinIntegerValue;
import org.unlaxer.base.IntegerValue;

@_MinIntegerValue(0)
@_MaxIntegerValue(0x10FFFF)
public class CodePoint extends IntegerValue<CodePoint>{

  public CodePoint(int value) {
    super(value);
  }
  
  public CodePoint(IntegerValue<?> value) {
    super(value);
  }

  
  public String toString() {
    return Character.toString(value());
  }
  
  public char[] toChars() {
    return Character.toChars(value());
  }

  @Override
  public CodePoint create(int i) {
    return new CodePoint(i);
  }

  @Override
  public CodePoint create(IntegerValue<?> i) {
    return new CodePoint(i);
  }

}