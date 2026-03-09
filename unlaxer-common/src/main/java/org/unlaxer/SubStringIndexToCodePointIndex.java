package org.unlaxer;

public interface SubStringIndexToCodePointIndex /*extends Function<CodePointIndex,StringIndex>*/{
  CodePointIndex subCodePointIndexFrom(StringIndex subStringIndex);
}