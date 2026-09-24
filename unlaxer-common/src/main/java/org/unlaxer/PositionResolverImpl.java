package org.unlaxer;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.unlaxer.Cursor.EndExclusiveCursor;
import org.unlaxer.Cursor.StartInclusiveCursor;
import org.unlaxer.Source.SourceKind;

public class PositionResolverImpl implements PositionResolver {
  
  final NavigableMap<CodePointIndex, LineNumber> lineNumberByIndex = new TreeMap<>();
  /*
   * These three used to be HashMaps with one boxed entry per code point, built eagerly for the
   * root source and live for the whole parse: a 20 KB input retained 63k HashMap nodes plus 63k
   * CodePointIndex/StringIndex/CodePointIndexInLine instances (3.4 MB) to hold what is, for
   * BMP text, the identity mapping. They are int arrays now, with -1 for "no entry"; the boxed
   * values are built only for the callers that ask. (perf #276)
   */
  final int[] stringIndexByCodePointIndex;
  final int[] codePointIndexInLineByCodePointIndex;
  final int[] codePointIndexByStringIndex;
  final List<CursorRange> cursorRanges = new ArrayList<>();
  final CursorRange cursorRange;
//  final RootPositionResolver rootPositionResolver;
  
//  public static RootPositionResolver createRootPositionResolver(int[] codePoints){
//    return new PositionResolverImpl(codePoints, null, new CodePointOffset(0));
//  }
//  
//  public static SubPositionResolver createSubPositionReslover(
//      int[] codePoints,
//      RootPositionResolver rootPositionResolver,
//      CodePointOffset offsetFromRoot){
//    return new PositionResolverImpl(codePoints, null, new CodePointOffset(0));
//  }

  
  PositionResolverImpl(int[] codePoints){
//      RootPositionResolver rootPositionResolver,
//      CodePointOffset offsetFromRoot) {
//    boolean isRoot = rootPositionResolver == null ;
    int codePointCount = codePoints.length;
    int stringLength = 0;
    for (int i = 0; i < codePointCount; i++) {
      stringLength += Character.isBmpCodePoint(codePoints[i]) ? 1 : 2;
    }
    stringIndexByCodePointIndex = newIndex(codePointCount + 1);
    codePointIndexInLineByCodePointIndex = newIndex(codePointCount + 1);
    codePointIndexByStringIndex = newIndex(stringLength + 2);
//    this.rootPositionResolver = isRoot ? this : rootPositionResolver;
    
    LineNumber lineNumber = new LineNumber(0);
    CodePointIndex startIndex = new CodePointIndex(0);
    CodePointIndex previousStartIndex;
    lineNumberByIndex.put(startIndex, lineNumber);
    
    int stringIndex = 0;
    int codePointIndex = 0;
    int codePointOffsetInline = 0;
    
    for (int i = 0; i < codePointCount; i++) {
      codePointIndex = i;
      stringIndexByCodePointIndex[codePointIndex] = stringIndex;
      codePointIndexByStringIndex[stringIndex] = codePointIndex;
      codePointIndexInLineByCodePointIndex[codePointIndex] = codePointOffsetInline;
    
      int codePointAt = codePoints[i];
      
      int adding = Character.isBmpCodePoint(codePointAt) ? 1:2;
      stringIndex = stringIndex + adding;
      
      if(codePointAt == SymbolMap.lf.codes[0]) {
        
        previousStartIndex = startIndex;
        startIndex = new CodePointIndex(i+1);
        cursorRanges.add(
            CursorRange.of(
              previousStartIndex,
              startIndex,
              CodePointOffset.ZERO,
              SourceKind.subSource,
              this
            )
        );
        lineNumber = lineNumber.newWithIncrements();
        lineNumberByIndex.put(startIndex, lineNumber);
        codePointOffsetInline = 0;
        continue;
        
      }else if(codePointAt == SymbolMap.cr.codes[0]) {
        
        if(codePointCount-1!=i && codePoints[i+1] ==SymbolMap.lf.codes[0]) {
          i++;
          previousStartIndex = startIndex;
          startIndex = new CodePointIndex(i+1);
          cursorRanges.add(
              CursorRange.of(
                previousStartIndex,
                startIndex,
                CodePointOffset.ZERO,
                SourceKind.subSource,
                this
              )
          );
          lineNumber = lineNumber.newWithIncrements();
          lineNumberByIndex.put(startIndex, lineNumber);
          
          stringIndex = stringIndex + 1;
          stringIndexByCodePointIndex[codePointIndex + 1] = stringIndex;
          codePointIndexByStringIndex[stringIndex] = codePointIndex + 1;
        }else {
          previousStartIndex = startIndex;
          startIndex = new CodePointIndex(i+1);
          cursorRanges.add(
              CursorRange.of(
                previousStartIndex,
                startIndex,
                CodePointOffset.ZERO,
                SourceKind.subSource,
                this
              )
          );
          lineNumber = lineNumber.newWithIncrements();
          lineNumberByIndex.put(startIndex, lineNumber);
        }
        codePointOffsetInline = 0;
        continue;
      }
      codePointOffsetInline = codePointOffsetInline + 1;
    }

    StartInclusiveCursor start = new StartInclusiveCursorImpl(SourceKind.root,this);//.addPosition(offsetFromRoot);
    
    CodePointIndex position = new CodePointIndex(codePointCount);//.newWithAdd(offsetFromRoot);
    
    EndExclusiveCursor end = new EndExclusiveCursorImpl(SourceKind.root,this)
        .setPosition(position);
    cursorRange = new CursorRange(start, end);
    codePointIndexInLineByCodePointIndex[codePointCount] = 0;
    lineNumberByIndex.put(position, lineNumber);
    if(cursorRanges.size()>0) {
      CursorRange last = cursorRanges.get(cursorRanges.size()-1);
      if(last.lessThan(new CodePointIndex(codePointIndex)) && startIndex.lessThan(position)) {
        
        cursorRanges.add(
            CursorRange.of(
              startIndex,
              position,
              CodePointOffset.ZERO,
              SourceKind.subSource,
              this
            )
        );
      }
    }
  }
  
  @Override
  public Size lineSize() {
    return new Size(cursorRanges.size());
  }
  
  @Override
  public Stream<Source> lines(Source root){
    return cursorRanges.stream()
      .map(root::subSource);
  }

  private static int[] newIndex(int size) {
    int[] index = new int[size];
    java.util.Arrays.fill(index, ABSENT);
    return index;
  }

  private static final int ABSENT = -1;

  private static int at(int[] index, int key) {
    return key < 0 || key >= index.length ? ABSENT : index[key];
  }

  @Override
  public StringIndex stringIndexInRootFrom(CodePointIndex codePointIndex) {
    
//    if(rootPositionResolver == this) {
//      return stringIndexByCodePointIndex.get(codePointIndexInSubSource);
//    }
//    return rootPositionResolver.stringIndexInRootFrom(codePointIndexInSubSource.newWithPlus(offsetFromRoot));
    int value = at(stringIndexByCodePointIndex, codePointIndex.value());
    return value == ABSENT ? null : new StringIndex(value);
  }

  @Override
  public LineNumber lineNumberFrom(CodePointIndex codePointIndex) {
//    return rootPositionResolver.lineNumberFrom(codePointIndex.newWithPlus(offsetFromRoot));
    return lineNumberByIndex.floorEntry(codePointIndex).getValue();
  }

//  @Override
//  public CodePointIndex codePointIndexFrom(StringIndex stringIndex) {
//    return rootPositionResolver.codePointIndexFrom(stringIndex);
//  }

  @Override
  public CursorRange rootCursorRange() {
    return cursorRange;
  }

  @Override
  public StringIndex subStringIndexFrom(CodePointIndex subCodePointIndex) {
    int value = at(stringIndexByCodePointIndex, subCodePointIndex.value());
    return value == ABSENT ? null : new StringIndex(value);
  }

  @Override
  public CodePointIndex subCodePointIndexFrom(StringIndex subStringIndex) {
    int value = at(codePointIndexByStringIndex, subStringIndex.value());
    return value == ABSENT ? null : new CodePointIndex(value);
  }

  @Override
  public CodePointIndexInLine codePointIndexInLineFrom(CodePointIndex codePointIndex) {
    int value = at(codePointIndexInLineByCodePointIndex, codePointIndex.value());
    return value == ABSENT ? null : new CodePointIndexInLine(value);
  }

  @Override
  public CodePointIndex rootCodePointIndexFrom(StringIndex stringIndex) {
    int value = at(codePointIndexByStringIndex, stringIndex.value());
    return value == ABSENT ? null : new CodePointIndex(value);
  }

}