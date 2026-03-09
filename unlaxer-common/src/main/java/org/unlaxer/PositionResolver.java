package org.unlaxer;

import java.util.stream.Stream;

public interface PositionResolver {
  
  StringIndex stringIndexInRootFrom(CodePointIndex CodePointIndex);
  CodePointIndexInLine codePointIndexInLineFrom(CodePointIndex rootCodePointIndex);
  LineNumber lineNumberFrom(CodePointIndex rootCodePointIndex);
  CodePointIndex rootCodePointIndexFrom(StringIndex stringIndex);
  
  
  StringIndex subStringIndexFrom(CodePointIndex subCodePointIndex);
  CodePointIndex subCodePointIndexFrom(StringIndex subStringIndex);
  
//  CodePointOffset offsetFromRoot();
  
  /**
   * @return cursorRange for rootSource.
   */
  CursorRange rootCursorRange();
  
//  /**
//   * @return cursorRange for subSource. start position is 0
//   */
//  CursorRange subCursorRange();
  
  

  Stream<Source> lines(Source root);

  Size lineSize();
  
  public static PositionResolver createPositionResolver(int[] codePoints){
    return new PositionResolverImpl(codePoints);
  }

}