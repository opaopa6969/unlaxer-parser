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
    // Empty detached/root sources are created for every empty token; the resolver is
    // immutable after construction, so all of them share one instance.
    if (codePoints.length == 0) {
      return EmptyResolverHolder.EMPTY;
    }
    return new PositionResolverImpl(codePoints);
  }

  /** Holder so the interface has no static initialization order with its implementation. */
  final class EmptyResolverHolder {
    static final PositionResolver EMPTY = new PositionResolverImpl(new int[0]);

    private EmptyResolverHolder() {}
  }

}