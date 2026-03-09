package org.unlaxer;

public interface SubCodePointIndexToLineNumber /*extends Function<CodePointIndex,StringIndex>*/{
  LineNumber subLineNumberFrom(CodePointIndex subCodePointIndex);
}