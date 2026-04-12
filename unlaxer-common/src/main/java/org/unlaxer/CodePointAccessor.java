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

  default CodePoint codePointAt(CodePointIndex index) {
    return new CodePoint(sourceAsString().codePointAt(toStringIndex(index).value()));
  }

  default CodePoint codePointBefore(CodePointIndex index) {
    return new CodePoint(sourceAsString().codePointBefore(toStringIndex(index).value()));
  }

  default Count codePointCount(CodePointIndex beginIndex, CodePointIndex endIndex) {
    return new Count(sourceAsString().codePointCount(
        toStringIndex(beginIndex).value(), toStringIndex(endIndex).value()));
  }

  default CodePointIndex offsetByCodePoints(CodePointIndex index, CodePointOffset codePointOffset) {
    return toCodePointIndex(new StringIndex(sourceAsString().offsetByCodePoints(
        toStringIndex(index).value(), codePointOffset.value())));
  }

  default void getChars(CodePointIndex srcBegin, CodePointIndex srcEnd, char dst[], StringIndex dstBegin) {
    sourceAsString().getChars(
        toStringIndex(srcBegin).value(), toStringIndex(srcEnd).value(), dst, dstBegin.value());
  }

  default boolean equalsIgnoreCase(CodePointAccessor anotherString) {
    return sourceAsString().equalsIgnoreCase(anotherString.sourceAsString());
  }

  default int compareTo(CodePointAccessor  anotherString) {
    return compareTo(anotherString.source());
  }

  default boolean regionMatches(CodePointIndex toffset, String other, CodePointIndex ooffset, Length len) {
    return sourceAsString().regionMatches(
        toStringIndex(toffset).value(), other, toStringIndex(ooffset).value(), len.value());
  }

  default boolean regionMatches(CodePointIndex toffset, CodePointAccessor other, CodePointIndex ooffset, Length len) {
    return sourceAsString().regionMatches(
        toStringIndex(toffset).value(), other.sourceAsString(), toStringIndex(ooffset).value(), len.value());
  }

  default boolean regionMatches(boolean ignoreCase, CodePointIndex toffset, String other, CodePointIndex ooffset, Length len) {
    return sourceAsString().regionMatches(
        ignoreCase, toStringIndex(toffset).value(), other, toStringIndex(ooffset).value(), len.value());
  }

  default boolean regionMatches(boolean ignoreCase, CodePointIndex toffset, CodePointAccessor other, CodePointIndex ooffset, Length len) {
    return sourceAsString().regionMatches(
        ignoreCase, toStringIndex(toffset).value(), other.sourceAsString(), toStringIndex(ooffset).value(), len.value());
  }

  default boolean startsWith(String prefix, CodePointIndex toffset) {
    return sourceAsString().startsWith(prefix, toStringIndex(toffset).value());
  }

  default boolean startsWith(CodePointAccessor prefix, CodePointIndex toffset) {
    return sourceAsString().startsWith(prefix.sourceAsString(), toStringIndex(toffset).value());
  }

  default boolean startsWith(CodePointAccessor prefix) {
    return startsWith(prefix.source());
  }


  default boolean endsWith(CodePointAccessor suffix) {
    return endsWith(suffix.source());
  }

  default CodePointIndexWithNegativeValue indexOf(CodePoint codePoint, CodePointIndex fromIndex) {
    int idx = sourceAsString().indexOf(codePoint.value(), toStringIndex(fromIndex).value());
    return new CodePointIndexWithNegativeValue(
        toCodePointIndexWithNegativeValue(new StringIndexWithNegativeValue(idx)));
  }


  default CodePointIndexWithNegativeValue lastIndexOf(CodePoint codePoint) {
    return new CodePointIndexWithNegativeValue(
        toCodePointIndex(new StringIndex(lastIndexOf(codePoint.value()))));
  }

  default CodePointIndexWithNegativeValue lastIndexOf(CodePoint codePoint, CodePointIndex fromIndex) {
    int idx = sourceAsString().lastIndexOf(codePoint.value(), toStringIndex(fromIndex).value());
    return new CodePointIndexWithNegativeValue(
        toCodePointIndexWithNegativeValue(new StringIndexWithNegativeValue(idx)));
  }

  default CodePointIndexWithNegativeValue indexOf(CodePointAccessor str) {
    return new CodePointIndexWithNegativeValue(
        toCodePointIndex(new StringIndex(indexOf(str.source()))));
  }

  default CodePointIndex indexOf(CodePointAccessor str, CodePointIndex fromIndex) {
    int idx = sourceAsString().indexOf(str.sourceAsString(), toStringIndex(fromIndex).value());
    return new CodePointIndex(
        toCodePointIndexWithNegativeValue(new StringIndexWithNegativeValue(idx)));
  }

  default CodePointIndex lastIndexOf(CodePointAccessor str) {
    return new CodePointIndex(
        toCodePointIndexWithNegativeValue(new StringIndexWithNegativeValue(lastIndexOf(str.source()))));
  }

  default CodePointIndex lastIndexOf(CodePointAccessor str, CodePointIndex fromIndex) {
    int idx = sourceAsString().lastIndexOf(str.sourceAsString(), toStringIndex(fromIndex).value());
    return new CodePointIndex(
        toCodePointIndexWithNegativeValue(new StringIndexWithNegativeValue(idx)));
  }


  public default CodePointIndex endIndexExclusive() {
    return new CodePointIndex(codePointLength());
  }

  public LineNumber lineNumber(CodePointIndex Position);

  default CursorRange cursorRange(CodePointIndex startIndexInclusive, CodePointLength length,
      SourceKind sourceKind, PositionResolver positionResolver) {

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
