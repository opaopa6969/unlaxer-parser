package org.unlaxer;

public interface SubCodePointIndexToStringIndex /*extends Function<CodePointIndex,StringIndex>*/{
  StringIndex subStringIndexFrom(CodePointIndex subCodePointIndex);
}