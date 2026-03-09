package org.unlaxer;

public interface CodePointIndexToLineNumber /*extends Function<CodePointIndex,StringIndex>*/{
  LineNumber lineNumberFrom(CodePointIndex rootCodePointIndex);
}