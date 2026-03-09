package org.unlaxer;

import java.util.Optional;

import org.unlaxer.Source.SourceKind;

public interface CodePointAccessor extends Comparable<CodePointAccessor>, StringBase {
  
  
  StringIndex toStringIndex(CodePointIndex codePointIndex);
  StringIndexWithNegativeValue toStringIndex(CodePointIndexWithNegativeValue codePointIndex);
  CodePointIndex toCodePointIndex(StringIndex stringIndex);
  CodePointIndexWithNegativeValue toCodePointIndexWithNegativeValue(StringIndexWithNegativeValue stringIndex);
  /**
   * Returns the length of this string.
   * The length is equal to the number of <a href="Character.html#unicode">Unicode
   * code units</a> in the string.
   *
   * @return  the length of the sequence of characters represented by this
   *          object.
   */
  StringLength stringLength();
  
  CodePointLength codePointLength();
  
  String sourceAsString();
  
  default Optional<String> nonEmptyString(){
    if(isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(sourceAsString());
  }
  
  Source source();

  StringIndexAccessor stringIndexAccessor();
  
//  StringBase stringBase();

  default CodePoint codePointAt(CodePointIndex index) {
    return new CodePoint(stringIndexAccessor().codePointAt(toStringIndex(index).value()));
  }
  
  default CodePoint codePointBefore(CodePointIndex index) {
    return new CodePoint(stringIndexAccessor().codePointBefore(toStringIndex(index).value()));
  }
  
  default Count codePointCount(CodePointIndex beginIndex, CodePointIndex endIndex) {
    return stringIndexAccessor().codePointCount(toStringIndex(beginIndex), toStringIndex(endIndex));
  }
  
  default CodePointIndex offsetByCodePoints(CodePointIndex index, CodePointOffset codePointOffset) {
    return toCodePointIndex(stringIndexAccessor().offsetByCodePoints(
        toStringIndex(index), codePointOffset));
  }
  
  default void getChars(CodePointIndex srcBegin, CodePointIndex srcEnd, char dst[], StringIndex dstBegin) {
    stringIndexAccessor().getChars(
        toStringIndex(srcBegin), toStringIndex(srcEnd), dst, dstBegin);
  }
  
  default void getBytes(CodePointIndex srcBegin, CodePointIndex srcEnd, byte dst[], StringIndex dstBegin) {
    stringIndexAccessor().getBytes(
        toStringIndex(srcBegin), toStringIndex(srcEnd), dst, dstBegin);
  }
  
  default boolean equalsIgnoreCase(CodePointAccessor anotherString) {
    return sourceAsString().equalsIgnoreCase(anotherString.sourceAsString());
  }

  default int compareTo(CodePointAccessor  anotherString) {
    return compareTo(anotherString.source());
  }

  default int compareToIgnoreCase(CodePointAccessor str) {
    return compareToIgnoreCase(str.toString());
  }
  
  default boolean regionMatches(CodePointIndex toffset, String other, CodePointIndex ooffset, Length len) {
    return stringIndexAccessor().regionMatches(toStringIndex(toffset).value(), other , toStringIndex(ooffset).value(), len.value());
  }
  
  default boolean regionMatches(CodePointIndex toffset, CodePointAccessor other, CodePointIndex ooffset, Length len) {
    return stringIndexAccessor().regionMatches(toStringIndex(toffset).value(), other.sourceAsString(), toStringIndex(ooffset).value(), len.value());
  }
  
  default boolean regionMatches(boolean ignoreCase, CodePointIndex toffset, String other, CodePointIndex ooffset, Length len) {
    return stringIndexAccessor().regionMatches(ignoreCase, toStringIndex(toffset), other, toStringIndex(ooffset), len);
  }
  
  default boolean regionMatches(boolean ignoreCase, CodePointIndex toffset, CodePointAccessor other, CodePointIndex ooffset, Length len) {
    return stringIndexAccessor().regionMatches(ignoreCase, toStringIndex(toffset), other.source(), toStringIndex(ooffset), len);
  }

  default boolean startsWith(String prefix, CodePointIndex toffset) {
    return stringIndexAccessor().startsWith(prefix, toStringIndex(toffset));
  }
  
  default boolean startsWith(CodePointAccessor prefix, CodePointIndex toffset) {
    return stringIndexAccessor().startsWith(prefix.sourceAsString(), toffset.value());
  }
  
  default boolean startsWith(CodePointAccessor prefix) {
    return startsWith(prefix.source());
  }

  
  default boolean endsWith(CodePointAccessor suffix) {
    return endsWith(suffix.source());
  }

//  default CodePointIndexWithNegativeValue indexOf(CodePoint codePoint) {
//    return new CodePointIndexWithNegativeValue(
//        toCodePointIndex(new StringIndex(
//            indexOf(codePoint.value()))));
//  }
  
  default CodePointIndexWithNegativeValue indexOf(CodePoint codePoint, CodePointIndex fromIndex) {
    return new CodePointIndexWithNegativeValue(
        toCodePointIndex(stringIndexAccessor().indexOf(codePoint,toStringIndex(fromIndex)).toStringIndex()));
  }

  
  default CodePointIndexWithNegativeValue lastIndexOf(CodePoint codePoint) {
    return new CodePointIndexWithNegativeValue(
        toCodePointIndex(new StringIndex(lastIndexOf(codePoint.value()))));
  }
  
  default CodePointIndexWithNegativeValue lastIndexOf(CodePoint codePoint, CodePointIndex fromIndex) {
    
    return new CodePointIndexWithNegativeValue(
        toCodePointIndex(stringIndexAccessor().lastIndexOf(codePoint , toStringIndex(fromIndex)).toStringIndex()));
  }
  
  default CodePointIndexWithNegativeValue indexOf(CodePointAccessor str) {
    return new CodePointIndexWithNegativeValue(
        toCodePointIndex(new StringIndex(indexOf(str.source()))));
  }
    
  default CodePointIndex indexOf(CodePointAccessor str, CodePointIndex fromIndex) {
    return new CodePointIndex(
        toCodePointIndexWithNegativeValue(stringIndexAccessor().indexOf(str,toStringIndex(fromIndex))));
  }

  default CodePointIndex lastIndexOf(CodePointAccessor str) {
    return new CodePointIndex(
        toCodePointIndexWithNegativeValue(new StringIndexWithNegativeValue(lastIndexOf(str.source()))));
  }

  default CodePointIndex lastIndexOf(CodePointAccessor str, CodePointIndex fromIndex) {
    return new CodePointIndex(
        toCodePointIndexWithNegativeValue(new StringIndexWithNegativeValue(stringIndexAccessor().lastIndexOf(str.sourceAsString() , 
            toStringIndex(fromIndex).value()))));
  }
  
  
  public default CodePointIndex endIndexExclusive() {
    return new CodePointIndex(codePointLength());
  }
  
  public LineNumber lineNumber(CodePointIndex Position);
  
  default CursorRange cursorRange(CodePointIndex startIndexInclusive, CodePointLength length,
      SourceKind sourceKind, PositionResolver positionResolver) {

    //こういう処理が入っていたけどとりあえず無視してみる。
//  if(startIndexInclusive.value() + length.value() > codePoints.length){
//  CodePointIndex index = new CodePointIndex(startIndexInclusive.value());
//  CursorRange cursorRange = new CursorRange(new CursorImpl()
//      .setPosition(index)
//      .setLineNumber(lineNUmber(index))
//  );
//  return new StringSource(this , cursorRange , null);
//}

    
    CodePointIndex endIndexExclusive = new CodePointIndex(startIndexInclusive.newWithPlus(length));
    return cursorRange(startIndexInclusive, endIndexExclusive, sourceKind , positionResolver);
  }
  
  default CursorRange cursorRange(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive , 
      SourceKind sourceKind, PositionResolver positionResolver) {
      CursorRange cursorRange = new CursorRange(
          new StartInclusiveCursorImpl(sourceKind , positionResolver)
            .setPosition(startIndexInclusive),
          new EndExclusiveCursorImpl(sourceKind , positionResolver)
            .setPosition(endIndexExclusive)
      );
      return cursorRange;
  }
  
  int[] subCodePoints(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive);
  
  
  /**
   * Returns the index within this string of the first occurrence of
   * the specified character. If a character with value
   * {@code ch} occurs in the character sequence represented by
   * this {@code String} object, then the index (in Unicode
   * code units) of the first such occurrence is returned. For
   * values of {@code ch} in the range from 0 to 0xFFFF
   * (inclusive), this is the smallest value <i>k</i> such that:
   * <blockquote><pre>
   * this.charAt(<i>k</i>) == ch
   * </pre></blockquote>
   * is true. For other values of {@code ch}, it is the
   * smallest value <i>k</i> such that:
   * <blockquote><pre>
   * this.codePointAt(<i>k</i>) == ch
   * </pre></blockquote>
   * is true. In either case, if no such character occurs in this
   * string, then {@code -1} is returned.
   *
   * @param   ch   a character (Unicode code point).
   * @return  the index of the first occurrence of the character in the
   *          character sequence represented by this object, or
   *          {@code -1} if the character does not occur.
   */
  default Optional<CodePointIndex> codePointIndexOf(int ch) {
    int indexOf = indexOf(ch);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointIndexOf(CodePoint ch) {
    return codePointIndexOf(ch.value());
  }
  
  /**
   * Returns the index within this string of the first occurrence of the
   * specified character, starting the search at the specified index.
   * <p>
   * If a character with value {@code ch} occurs in the
   * character sequence represented by this {@code String}
   * object at an index no smaller than {@code fromIndex}, then
   * the index of the first such occurrence is returned. For values
   * of {@code ch} in the range from 0 to 0xFFFF (inclusive),
   * this is the smallest value <i>k</i> such that:
   * <blockquote><pre>
   * (this.charAt(<i>k</i>) == ch) {@code &&} (<i>k</i> &gt;= fromIndex)
   * </pre></blockquote>
   * is true. For other values of {@code ch}, it is the
   * smallest value <i>k</i> such that:
   * <blockquote><pre>
   * (this.codePointAt(<i>k</i>) == ch) {@code &&} (<i>k</i> &gt;= fromIndex)
   * </pre></blockquote>
   * is true. In either case, if no such character occurs in this
   * string at or after position {@code fromIndex}, then
   * {@code -1} is returned.
   *
   * <p>
   * There is no restriction on the value of {@code fromIndex}. If it
   * is negative, it has the same effect as if it were zero: this entire
   * string may be searched. If it is greater than the length of this
   * string, it has the same effect as if it were equal to the length of
   * this string: {@code -1} is returned.
   *
   * <p>All indices are specified in {@code char} values
   * (Unicode code units).
   *
   * @param   ch          a character (Unicode code point).
   * @param   fromIndex   the index to start the search from.
   * @return  the index of the first occurrence of the character in the
   *          character sequence represented by this object that is greater
   *          than or equal to {@code fromIndex}, or {@code -1}
   *          if the character does not occur.
   */
  default Optional<CodePointIndex> codePointIndexOf(int ch, int fromIndex) {
    int indexOf = indexOf(ch,fromIndex);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointIndexOf(CodePoint ch, CodePointIndex fromIndex) {
    return codePointIndexOf(ch.value(), fromIndex.value());
  }

  
  /**
   * Returns the index within this string of the last occurrence of
   * the specified character. For values of {@code ch} in the
   * range from 0 to 0xFFFF (inclusive), the index (in Unicode code
   * units) returned is the largest value <i>k</i> such that:
   * <blockquote><pre>
   * this.charAt(<i>k</i>) == ch
   * </pre></blockquote>
   * is true. For other values of {@code ch}, it is the
   * largest value <i>k</i> such that:
   * <blockquote><pre>
   * this.codePointAt(<i>k</i>) == ch
   * </pre></blockquote>
   * is true.  In either case, if no such character occurs in this
   * string, then {@code -1} is returned.  The
   * {@code String} is searched backwards starting at the last
   * character.
   *
   * @param   ch   a character (Unicode code point).
   * @return  the index of the last occurrence of the character in the
   *          character sequence represented by this object, or
   *          {@code -1} if the character does not occur.
   */
  default Optional<CodePointIndex> codePointLastIndexOf(int ch) {
    int indexOf = lastIndexOf(ch);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointLastIndexOf(CodePoint ch) {
    return codePointLastIndexOf(ch.value());
  }
  
  /**
   * Returns the index within this string of the last occurrence of
   * the specified character, searching backward starting at the
   * specified index. For values of {@code ch} in the range
   * from 0 to 0xFFFF (inclusive), the index returned is the largest
   * value <i>k</i> such that:
   * <blockquote><pre>
   * (this.charAt(<i>k</i>) == ch) {@code &&} (<i>k</i> &lt;= fromIndex)
   * </pre></blockquote>
   * is true. For other values of {@code ch}, it is the
   * largest value <i>k</i> such that:
   * <blockquote><pre>
   * (this.codePointAt(<i>k</i>) == ch) {@code &&} (<i>k</i> &lt;= fromIndex)
   * </pre></blockquote>
   * is true. In either case, if no such character occurs in this
   * string at or before position {@code fromIndex}, then
   * {@code -1} is returned.
   *
   * <p>All indices are specified in {@code char} values
   * (Unicode code units).
   *
   * @param   ch          a character (Unicode code point).
   * @param   fromIndex   the index to start the search from. There is no
   *          restriction on the value of {@code fromIndex}. If it is
   *          greater than or equal to the length of this string, it has
   *          the same effect as if it were equal to one less than the
   *          length of this string: this entire string may be searched.
   *          If it is negative, it has the same effect as if it were -1:
   *          -1 is returned.
   * @return  the index of the last occurrence of the character in the
   *          character sequence represented by this object that is less
   *          than or equal to {@code fromIndex}, or {@code -1}
   *          if the character does not occur before that point.
   */
  default Optional<CodePointIndex> codePointLastIndexOf(int ch, int fromIndex) {
    int indexOf = lastIndexOf(ch,fromIndex);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointLastIndexOf(CodePoint ch, CodePointIndex fromIndex) {
    return codePointLastIndexOf(ch.value(), fromIndex.value());
  }

  
  /**
   * Returns the index within this string of the first occurrence of the
   * specified substring.
   *
   * <p>The returned index is the smallest value {@code k} for which:
   * <pre>{@code
   * this.startsWith(str, k)
   * }</pre>
   * If no such value of {@code k} exists, then {@code -1} is returned.
   *
   * @param   str   the substring to search for.
   * @return  the index of the first occurrence of the specified substring,
   *          or {@code -1} if there is no such occurrence.
   */
  default Optional<CodePointIndex> codePointIndexOf(String str) {
    int indexOf = indexOf(str);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointIndexOf(Source str) {
    return codePointIndexOf(str.sourceAsString());
  }

  /**
   * Returns the index within this string of the last occurrence of the
   * specified substring.  The last occurrence of the empty string ""
   * is considered to occur at the index value {@code this.length()}.
   *
   * <p>The returned index is the largest value {@code k} for which:
   * <pre>{@code
   * this.startsWith(str, k)
   * }</pre>
   * If no such value of {@code k} exists, then {@code -1} is returned.
   *
   * @param   str   the substring to search for.
   * @return  the index of the last occurrence of the specified substring,
   *          or {@code -1} if there is no such occurrence.
   */
  default Optional<CodePointIndex> codePointLastIndexOf(String str) {
    int indexOf = lastIndexOf(str);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointLastIndexOf(Source str) {
    return codePointLastIndexOf(str.sourceAsString());
  }
  
  /**
   * Returns the index within this string of the last occurrence of the
   * specified substring, searching backward starting at the specified index.
   *
   * <p>The returned index is the largest value {@code k} for which:
   * <pre>{@code
   *     k <= Math.min(fromIndex, this.length()) &&
   *                   this.startsWith(str, k)
   * }</pre>
   * If no such value of {@code k} exists, then {@code -1} is returned.
   *
   * @param   str         the substring to search for.
   * @param   fromIndex   the index to start the search from.
   * @return  the index of the last occurrence of the specified substring,
   *          searching backward from the specified index,
   *          or {@code -1} if there is no such occurrence.
   */
  default Optional<CodePointIndex> codePointLastIndexOf(String str, int fromIndex) {
    int indexOf = lastIndexOf(str,fromIndex);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointLastIndexOf(String str, CodePointIndex fromIndex) {
    return codePointLastIndexOf(str,fromIndex.value());
  }
  
  default Optional<CodePointIndex> codePointLastIndexOf(Source str, CodePointIndex fromIndex) {
    return codePointLastIndexOf(str.sourceAsString(),fromIndex.value());
  }

  
  /**
   * Returns the index within this string of the first occurrence of the
   * specified substring, starting at the specified index.
   *
   * <p>The returned index is the smallest value {@code k} for which:
   * <pre>{@code
   *     k >= Math.min(fromIndex, this.length()) &&
   *                   this.startsWith(str, k)
   * }</pre>
   * If no such value of {@code k} exists, then {@code -1} is returned.
   *
   * @param   str         the substring to search for.
   * @param   fromIndex   the index from which to start the search.
   * @return  the index of the first occurrence of the specified substring,
   *          starting at the specified index,
   *          or {@code -1} if there is no such occurrence.
   */
  default Optional<CodePointIndex> codePointIndexOf(String str, int fromIndex) {
    int indexOf = indexOf(str,fromIndex);
    if(indexOf < 0) {
      return Optional.empty();
    }
    return Optional.of(toCodePointIndex(new StringIndex(indexOf)));
  }
  
  default Optional<CodePointIndex> codePointIndexOf(String str, CodePointIndex fromIndex) {
    return codePointIndexOf(str,fromIndex.value());
  }
  
  default Optional<CodePointIndex> codePointIndexOf(Source str, CodePointIndex fromIndex) {
    return codePointIndexOf(str.sourceAsString(),fromIndex.value());
  }

}