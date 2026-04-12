package org.unlaxer;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.unlaxer.util.function.TriFunction;

public class StringSource implements Source {

  private final Source root;
  private final Source parent; // subSource の時だけ非null
  private final String sourceString;
  private final int[] codePoints;
  private final PositionResolver positionResolver;
  private final Depth depth;
  private final SourceKind sourceKind;
  private final CodePointOffset offsetFromParent;
  private final CodePointOffset offsetFromRoot;
  private final CursorRange cursorRange;

  public static StringSource create(String source, SourceKind sourceKind) {
    if (sourceKind == SourceKind.subSource) {
      throw new IllegalArgumentException();
    }
    return new StringSource(source, sourceKind, null, new CodePointOffset(0));
  }

  public static StringSource createRootSource(String source) {
    return new StringSource(source, SourceKind.root, null, new CodePointOffset(0));
  }

  public static StringSource createSubSource(String source, Source rootSource, CodePointOffset codePointOffset) {
    Objects.requireNonNull(rootSource,
        "rootSource must not be null; use createDetachedSource for origin-less sources");
    return new StringSource(rootSource, source, codePointOffset);
  }

  public static StringSource createDetachedSource(String source, Source root) {
    return new StringSource(source, SourceKind.detached, root, new CodePointOffset(0));
  }

  public static StringSource createDetachedSource(String source) {
    return new StringSource(source, SourceKind.detached, null, new CodePointOffset(0));
  }



  private StringSource(String source, SourceKind sourceKind, CodePointOffset offsetFromParent) {
    this(source, sourceKind, null, offsetFromParent);
  }

  /**
   * root / detached 用のコンストラクタ
   *
   * - parent は持たない（tree に入れない）
   * - subSource ではないので resolver は常に自前（0起点）
   */
  private StringSource(String source, SourceKind sourceKind, Source root, CodePointOffset offsetFromParent) {
    super();
    Objects.requireNonNull(source, "source require non null");

    this.sourceString = source;
    this.sourceKind = sourceKind;

    // root参照（detachedでも「元rootを参照したい」はあり得る）
    this.root = (root == null ? this : root);

    // ✅ root/detached は parent を持たない（subSourceだけが parent を持つ）
    this.parent = null;
    this.depth = new Depth(0);

    this.offsetFromParent = offsetFromParent;
    this.offsetFromRoot = CodePointOffset.ZERO;
    this.codePoints = source.codePoints().toArray();

    // ✅ subSource 以外は独立 resolver（=positionInRoot は 0起点）
    this.positionResolver = PositionResolver.createPositionResolver(codePoints);

    // root/detached の cursorRange は自分座標（0起点）でOK
    this.cursorRange = CursorRange.fromRootOffset(
        CodePointOffset.ZERO,
        new CodePointLength(codePoints.length),
        sourceKind,
        positionResolver
    );
  }

  /**
   * subSource 用コンストラクタ（Source を渡す版）
   * parent を保持し、offsetFromRoot を合成して cursorRange を root 座標で作る
   */
  private StringSource(Source parent, Source source, CodePointOffset offsetFromParent) {
    super();

    this.sourceString = source.toString();
    this.root = parent.root();
    if (!this.root.isRoot()) {
      throw new IllegalArgumentException();
    }

    this.parent = parent;
    this.offsetFromParent = offsetFromParent;
    this.depth = parent.depth().newWithIncrements();
    this.sourceKind = SourceKind.subSource;

    this.codePoints = source.codePoints().toArray();

    // ✅ subSource は root resolver を使う（root座標共有）
    this.positionResolver = this.root;

    // ✅ root座標系の offset を合成して cursorRange を作る
    this.offsetFromRoot = parent.offsetFromRoot().newWithPlus(offsetFromParent);
    this.cursorRange = CursorRange.fromRootOffset(
        this.offsetFromRoot,
        new CodePointLength(codePoints.length),
        sourceKind,
        positionResolver
    );
  }

  /**
   * subSource 用コンストラクタ（String を渡す版）
   */
  public StringSource(Source parent, String source, CodePointOffset codePointOffset) {
    super();
    Objects.requireNonNull(source, "source require non null");

    this.sourceString = source;
    this.parent = parent;
    this.root = parent.root();

    this.depth = parent.depth().newWithIncrements();
    this.sourceKind = SourceKind.subSource;

    this.offsetFromParent = codePointOffset;
    this.codePoints = source.codePoints().toArray();

    // ✅ subSource は root resolver を使う（root座標共有）
    this.positionResolver = this.root;

    // ✅ root座標系の offset を合成して cursorRange を作る
    this.offsetFromRoot = parent.offsetFromRoot().newWithPlus(offsetFromParent);
    this.cursorRange = CursorRange.fromRootOffset(
        this.offsetFromRoot,
        new CodePointLength(codePoints.length),
        sourceKind,
        positionResolver
    );
  }

  public LineNumber lineNumberFrom(CodePointIndex codePointIndex) {
    return positionResolver.lineNumberFrom(codePointIndex);
  }

  public StringIndex stringIndexFrom(CodePointIndex codePointIndex) {
    return positionResolver.stringIndexInRootFrom(codePointIndex);
  }

  public CodePointIndex codePointIndexFrom(StringIndex stringIndex) {
    return positionResolver.rootCodePointIndexFrom(stringIndex);
  }

  public CursorRange rootCursorRange() {
    return positionResolver.rootCursorRange();
  }

  public Stream<Source> lines(Source root) {
    return positionResolver.lines(root);
  }

  public Size lineSize() {
    return positionResolver.lineSize();
  }

  public StringIndex subStringIndexFrom(CodePointIndex subCodePointIndex) {
    return positionResolver.subStringIndexFrom(subCodePointIndex);
  }

  public CodePointIndex subCodePointIndexFrom(StringIndex subStringIndex) {
    return positionResolver.subCodePointIndexFrom(subStringIndex);
  }

  static Function<String, Source> stringToStringInterface = string -> StringSource.createRootSource(string);

  static TriFunction<Source, String, CodePointOffset, Source> parentSourceAndStringToSource =
      (parent, sourceAsString, codePointOffset) ->
          // replace系は detached を返す（座標共有しない）
          new StringSource(
              parent,
              StringSource.createDetachedSource(sourceAsString),
              codePointOffset
          );

  static Function<Source, String> stringInterfaceToStgring = StringSource::toString;

  @Override
  public TriFunction<Source, String, CodePointOffset, Source> parentSourceAndStringToSource() {
    return parentSourceAndStringToSource;
  }

  @Override
  public Function<Source, String> sourceToStgring() {
    return stringInterfaceToStgring;
  }

  @Override
  public StringLength stringLength() {
    return new StringLength(sourceString.length());
  }

  @Override
  public CodePointLength codePointLength() {
    return new CodePointLength(codePoints.length);
  }

  @Override
  public boolean isEmpty() {
    return sourceString.isEmpty();
  }

  @Override
  public char charAt(int index) {
    return sourceString.charAt(index);
  }

  @Override
  public byte[] getBytes(String charsetName) throws UnsupportedEncodingException {
    return sourceString.getBytes(charsetName);
  }

  @Override
  public byte[] getBytes(Charset charset) {
    return sourceString.getBytes(charset);
  }

  @Override
  public byte[] getBytes() {
    return sourceString.getBytes();
  }

  @Override
  public boolean equalsIgnoreCase(String anotherString) {
    return sourceString.equalsIgnoreCase(anotherString);
  }

  @Override
  public int compareTo(String anotherString) {
    return sourceString.compareTo(anotherString);
  }

  @Override
  public boolean startsWith(String prefix) {
    return sourceString.startsWith(prefix);
  }

  @Override
  public boolean endsWith(String suffix) {
    return sourceString.endsWith(suffix);
  }

  @Override
  public int indexOf(int ch) {
    return sourceString.indexOf(ch);
  }

  @Override
  public int lastIndexOf(int ch) {
    return sourceString.lastIndexOf(ch);
  }

  @Override
  public int indexOf(String str) {
    return sourceString.indexOf(str);
  }

  @Override
  public int lastIndexOf(String str) {
    return sourceString.lastIndexOf(str);
  }

  @Override
  public int indexOf(int ch, int fromIndex) {
    return sourceString.indexOf(ch, fromIndex);
  }

  @Override
  public int lastIndexOf(int ch, int fromIndex) {
    return sourceString.lastIndexOf(ch, fromIndex);
  }

  @Override
  public int lastIndexOf(String str, int fromIndex) {
    return sourceString.lastIndexOf(str, fromIndex);
  }

  @Override
  public int indexOf(String str, int fromIndex) {
    return sourceString.indexOf(str, fromIndex);
  }

  @Override
  public CharSequence subSequence(int beginIndex, int endIndex) {
    return sourceString.subSequence(beginIndex, endIndex);
  }

  @Override
  public String concat(String str) {
    return sourceString.concat(str);
  }

  @Override
  public String replace(char oldChar, char newChar) {
    return sourceString.replace(oldChar, newChar);
  }

  @Override
  public boolean matches(String regex) {
    return sourceString.matches(regex);
  }

  @Override
  public boolean contains(CharSequence s) {
    return sourceString.contains(s);
  }

  @Override
  public String replaceAll(String regex, String replacement) {
    return sourceString.replaceAll(regex, replacement);
  }

  @Override
  public String replace(CharSequence target, CharSequence replacement) {
    return sourceString.replace(target, replacement);
  }

  @Override
  public String[] split(String regex, int limit) {
    return sourceString.split(regex, limit);
  }

  @Override
  public String[] split(String regex) {
    return sourceString.split(regex);
  }

  @Override
  public String toLowerCase(Locale locale) {
    return sourceString.toLowerCase(locale);
  }

  @Override
  public String toLowerCase() {
    return sourceString.toLowerCase();
  }

  @Override
  public String toUpperCase(Locale locale) {
    return sourceString.toUpperCase(locale);
  }

  @Override
  public String toUpperCase() {
    return sourceString.toUpperCase();
  }

  @Override
  public String trim() {
    return sourceString.trim();
  }

  @Override
  public String strip() {
    return sourceString.strip();
  }

  @Override
  public boolean isBlank() {
    return sourceString.isBlank();
  }

  @Override
  public Stream<String> lines() {
    return sourceString.lines();
  }

  @Override
  public IntStream chars() {
    return sourceString.chars();
  }

  @Override
  public IntStream codePoints() {
    return sourceString.codePoints();
  }

  @Override
  public char[] toCharArray() {
    return sourceString.toCharArray();
  }

  @Override
  public String repeat(int count) {
    return sourceString.repeat(count);
  }

  @Override
  public StringIndex toStringIndex(CodePointIndex codePointIndex) {
    StringIndex stringIndexFrom = positionResolver.stringIndexInRootFrom(codePointIndex);
    if (stringIndexFrom == null) {
      stringIndexFrom = positionResolver.stringIndexInRootFrom(codePointIndex.newWithMinus(1)).newWithAdd(1);
    }
    return stringIndexFrom;
  }

  @Override
  public CodePointIndex toCodePointIndex(StringIndex stringIndex) {
    return positionResolver.rootCodePointIndexFrom(stringIndex);
  }

  @Override
  public int hashCode() {
    return sourceString.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Source source) {
      return sourceString.equals(source.sourceAsString());
    }
    return sourceString.equals(obj);
  }

  @Override
  public String toString() {
    return sourceString;
  }

  @Override
  public Source source() {
    return this;
  }

  @Override
  public int length() {
    return sourceAsString().length();
  }

  @Override
  public StringIndexWithNegativeValue toStringIndex(CodePointIndexWithNegativeValue codePointIndex) {
    if (codePointIndex.isNegative()) {
      return new StringIndexWithNegativeValue(codePointIndex.value());
    }
    return new StringIndexWithNegativeValue(toStringIndex(codePointIndex.toCodePointIndex()));
  }

  @Override
  public CodePointIndexWithNegativeValue toCodePointIndexWithNegativeValue(StringIndexWithNegativeValue stringIndex) {
    if (stringIndex.isNegative()) {
      return new CodePointIndexWithNegativeValue(stringIndex.value());
    }
    return new CodePointIndexWithNegativeValue(toCodePointIndex(stringIndex.toStringIndex()));
  }

  public static String toString(CodePointAccessor codePointAccessor) {
    return codePointAccessor.toString();
  }

  @Override
  public Source peek(CodePointIndex startIndexInclusive, CodePointLength length) {
    CodePointOffset offset = new CodePointOffset(startIndexInclusive);

    if (startIndexInclusive.value() + length.value() > codePoints.length) {
      return new StringSource(this,
          subSource(startIndexInclusive, new CodePointLength(0)),
          offset);
    }
    return new StringSource(this, subSource(startIndexInclusive, length), offset);
  }

  @Override
  public Source subSource(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return new StringSource(this,
        subString(startIndexInclusive, endIndexExclusive),
        new CodePointOffset(startIndexInclusive));
  }

  @Override
  public Source subSource(CodePointIndex startIndexInclusive, CodePointLength codePointLength) {
    return new StringSource(this,
        subString(startIndexInclusive, codePointLength),
        new CodePointOffset(startIndexInclusive));
  }

  @Override
  public int[] subCodePoints(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return Arrays.copyOfRange(codePoints, startIndexInclusive.value(), endIndexExclusive.value());
  }

  public String subString(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return new String(codePoints, startIndexInclusive.value(),
        endIndexExclusive.value() - startIndexInclusive.value());
  }

  public String subString(CodePointIndex startIndexInclusive, CodePointLength length) {
    return new String(codePoints, startIndexInclusive.value(), length.value());
  }

  @Override
  public LineNumber lineNumber(CodePointIndex position) {
    return positionResolver.lineNumberFrom(position);
  }

  @Override
  public String sourceAsString() {
    return sourceString;
  }

  @Override
  public Optional<Source> parent() {
    return Optional.ofNullable(parent);
  }

  @Override
  public Source root() {
    return root;
  }

  @Override
  public Source thisSource() {
    return this;
  }

  @Override
  public Depth depth() {
    return depth;
  }

  @Override
  public CodePointOffset offsetFromParent() {
    // subSource のときは offsetFromParent が意味を持つ
    // root/detached の場合は 0 が自然（親を持たない）
    if (parent == null) {
      return CodePointOffset.ZERO;
    }
    return offsetFromParent;
  }

  @Override
  public CodePointOffset offsetFromRoot() {
    return offsetFromRoot;
  }

  @Override
  public SourceKind sourceKind() {
    return sourceKind;
  }

  @Override
  public Stream<Source> linesAsSource() {
    return positionResolver.lines(this);
  }

  @Override
  public boolean isRoot() {
    return sourceKind == SourceKind.root;
  }

  public static Source EMPTY = StringSource.createDetachedSource("");

  @Override
  public StringIndex stringIndexInRootFrom(CodePointIndex codePointIndex) {
    return positionResolver.stringIndexInRootFrom(codePointIndex);
  }

  @Override
  public CodePointIndexInLine codePointIndexInLineFrom(CodePointIndex rootCodePointIndex) {
    return positionResolver.codePointIndexInLineFrom(rootCodePointIndex);
  }

  @Override
  public CodePointIndex rootCodePointIndexFrom(StringIndex stringIndex) {
    return positionResolver.rootCodePointIndexFrom(stringIndex);
  }

  @Override
  public PositionResolver positionResolver() {
    return positionResolver;
  }

  @Override
  public CursorRange cursorRange() {
    return cursorRange;
  }
}
