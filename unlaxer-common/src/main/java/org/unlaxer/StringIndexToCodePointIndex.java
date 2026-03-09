package org.unlaxer;

public interface StringIndexToCodePointIndex /*extends Function<CodePointIndex,StringIndex>*/{
  CodePointIndex rootCodePointIndexFrom(StringIndex stringIndex);
}