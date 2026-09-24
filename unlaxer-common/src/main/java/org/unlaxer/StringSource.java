package org.unlaxer;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.unlaxer.util.function.TriFunction;

public class StringSource implements Source {

  private final Source root;
  private final Source parent; // subSource の時だけ非null
  /*
   * A sub-source is a view: start/length into a shared code point array (its root's, when the
   * sub-source is an exact slice of it) with the String materialized only when someone asks.
   * A 20 KB input used to retain 515,595 code points' worth of per-token copies - 24x the input -
   * because every commit re-created both the String and the int[]. (perf #276)
   */
  private final int[] codePoints;
  private final int codePointOffsetInArray;
  private final int codePointLength;
  private String sourceString;
  private final PositionResolver positionResolver;
  private final SourceKind sourceKind;
  /*
   * Depth and both offsets are plain ints: a parse creates a sub-source per peek and per commit,
   * and boxing these three into Depth/CodePointOffset instances cost three objects per source
   * that stayed live for as long as the tree. The accessors still return the value objects; only
   * callers that ask now pay for one. (perf #276)
   */
  private final int depthValue;
  private final int offsetFromParentValue;
  private final int offsetFromRootValue;
  /*
   * Built on demand: a parse creates over a million sub-sources for a 20 KB input and most of
   * them (peeks that did not match) never need their cursor range, while the ones that do become
   * a token now get their [0, length) extent from sourceRange() without a CursorRange at all.
   * The range is derived only from final fields, so recomputing it after a race is harmless.
   * (perf #276)
   */
  private CursorRange cursorRange;

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
    this.depthValue = 0;

    this.offsetFromParentValue = offsetFromParent.value();
    this.offsetFromRootValue = 0;
    this.codePoints = codePointsOf(source);
    this.codePointOffsetInArray = 0;
    this.codePointLength = this.codePoints.length;

    // ✅ subSource 以外は独立 resolver（=positionInRoot は 0起点）
    this.positionResolver = PositionResolver.createPositionResolver(codePoints);
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
    this.offsetFromParentValue = offsetFromParent.value();
    this.depthValue = depthOf(parent) + 1;
    this.sourceKind = SourceKind.subSource;

    this.codePoints = codePointsOf(this.sourceString);
    this.codePointOffsetInArray = 0;
    this.codePointLength = this.codePoints.length;

    // ✅ subSource は root resolver を使う（root座標共有）
    this.positionResolver = this.root;

    // ✅ root座標系の offset を合成する（cursorRange は cursorRange() で遅延生成）
    this.offsetFromRootValue = offsetFromRootOf(parent) + this.offsetFromParentValue;
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

    this.depthValue = depthOf(parent) + 1;
    this.sourceKind = SourceKind.subSource;

    this.offsetFromParentValue = codePointOffset.value();
    this.codePoints = codePointsOf(source);
    this.codePointOffsetInArray = 0;
    this.codePointLength = this.codePoints.length;

    // ✅ subSource は root resolver を使う（root座標共有）
    this.positionResolver = this.root;

    // ✅ root座標系の offset を合成する（cursorRange は cursorRange() で遅延生成）
    this.offsetFromRootValue = offsetFromRootOf(parent) + this.offsetFromParentValue;
  }

  /**
   * View constructor: shares {@code parent}'s code point array instead of copying the slice out
   * of it. The resulting source is byte-for-byte the one the copying constructor would build for
   * {@code parent.subString(offsetFromParent, length)}; only the String is deferred. (perf #276)
   */
  private StringSource(StringSource parent, int offsetFromParent, int length) {
    super();
    /*
     * The copying constructor this replaces materialized the slice eagerly, so an out-of-range
     * request failed here rather than at the first read; keep that.
     */
    if (offsetFromParent < 0 || length < 0 || offsetFromParent + length > parent.codePointLength) {
      throw new IndexOutOfBoundsException(
          "offset " + offsetFromParent + ", count " + length + ", length " + parent.codePointLength);
    }
    this.sourceString = null;
    this.parent = parent;
    this.root = parent.root();
    this.depthValue = parent.depthValue + 1;
    this.sourceKind = SourceKind.subSource;
    this.offsetFromParentValue = offsetFromParent;
    this.codePoints = parent.codePoints;
    this.codePointOffsetInArray = parent.codePointOffsetInArray + offsetFromParent;
    this.codePointLength = length;
    this.positionResolver = this.root;
    this.offsetFromRootValue = parent.offsetFromRootValue + offsetFromParent;
  }

  private static int depthOf(Source source) {
    return source instanceof StringSource stringSource ? stringSource.depthValue : source.depth().value();
  }

  private static int offsetFromRootOf(Source source) {
    return source instanceof StringSource stringSource
        ? stringSource.offsetFromRootValue
        : source.offsetFromRoot().value();
  }

  /**
   * Creates a sub-source that is an exact slice of {@code rootSource}'s code points without
   * materializing the text. Callers must have established that the slice is what the equivalent
   * copying factory would produce; {@link TokenList#toSource} checks that its children are
   * contiguous before using this. (perf #276)
   */
  public static StringSource createSubSourceView(Source rootSource, int codePointOffset, int length) {
    Objects.requireNonNull(rootSource, "rootSource must not be null");
    if (rootSource instanceof StringSource stringSource && stringSource.isRoot()) {
      return new StringSource(stringSource, codePointOffset, length);
    }
    throw new IllegalArgumentException("createSubSourceView requires a root StringSource");
  }

  /** Whether this source is a slice of {@code candidateRoot}'s own code point array. (perf #276) */
  boolean sharesCodePointArrayWith(StringSource candidateRoot) {
    return codePoints == candidateRoot.codePoints;
  }

  int codePointOffsetInArray() {
    return codePointOffsetInArray;
  }

  /*
   * Holder class: Source.EMPTY is built while StringSource itself is still being initialized
   * (initializing StringSource initializes the Source interface, which calls back into this
   * class), so a plain static field of StringSource would still be null at that point.
   */
  private static final class EmptyCodePoints {
    static final int[] ARRAY = new int[0];
  }

  /**
   * Decodes a String into code points without an IntStream pipeline. Sub-sources are created
   * on every commit, so this runs on the hot path; the result is exact-sized.
   */
  static int[] codePointsOf(String source) {
    int length = source.length();
    if (length == 0) {
      return EmptyCodePoints.ARRAY;
    }
    int[] codePoints = new int[source.codePointCount(0, length)];
    for (int i = 0, j = 0; i < length; j++) {
      int codePoint = source.codePointAt(i);
      codePoints[j] = codePoint;
      i += Character.charCount(codePoint);
    }
    return codePoints;
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

  static Function<Source, String> sourceToString = StringSource::toString;

  @Override
  public TriFunction<Source, String, CodePointOffset, Source> parentSourceAndStringToSource() {
    return parentSourceAndStringToSource;
  }

  @Override
  public Function<Source, String> sourceToString() {
    return sourceToString;
  }

  /**
   * @deprecated Use {@link #sourceToString()}.
   */
  @Deprecated(since = "3.0.15", forRemoval = false)
  @Override
  public Function<Source, String> sourceToStgring() {
    return sourceToString();
  }

  @Override
  public StringLength stringLength() {
    String materialized = sourceString;
    if (materialized != null) {
      return new StringLength(materialized.length());
    }
    int chars = 0;
    for (int i = 0; i < codePointLength; i++) {
      chars += Character.charCount(codePoints[codePointOffsetInArray + i]);
    }
    return new StringLength(chars);
  }

  @Override
  public CodePointLength codePointLength() {
    return new CodePointLength(codePointLength);
  }

  @Override
  public boolean isEmpty() {
    return codePointLength == 0;
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
    return sourceAsString().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Source source) {
      return sourceAsString().equals(source.sourceAsString());
    }
    return sourceAsString().equals(obj);
  }

  @Override
  public String toString() {
    return sourceAsString();
  }

  @Override
  public Source source() {
    return this;
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
    // subSource(start, length) already produces a StringSource over codePoints[start, start+length)
    // with parent=this and the same root offset; the previous implementation wrapped that result in
    // a SECOND StringSource at the same offset, doubling the String + int[] copies on every peek
    // (the dominant allocation in deeply nested grammars — #19/#40). The wrap is redundant: the
    // single subSource is byte-for-byte equivalent. (perf #40 follow-up)
    if (startIndexInclusive.value() + length.value() > codePointLength) {
      return subSource(startIndexInclusive, new CodePointLength(0));
    }
    return subSource(startIndexInclusive, length);
  }

  @Override
  public Source subSource(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return new StringSource(this, startIndexInclusive.value(),
        endIndexExclusive.value() - startIndexInclusive.value());
  }

  @Override
  public Source subSource(CodePointIndex startIndexInclusive, CodePointLength length) {
    return new StringSource(this, startIndexInclusive.value(), length.value());
  }

  @Override
  public int codePointValueAt(int index) {
    return index < 0 || index >= codePointLength ? -1 : codePoints[codePointOffsetInArray + index];
  }

  @Override
  public int[] subCodePoints(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return Arrays.copyOfRange(codePoints, codePointOffsetInArray + startIndexInclusive.value(),
        codePointOffsetInArray + endIndexExclusive.value());
  }

  public String subString(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return new String(codePoints, codePointOffsetInArray + startIndexInclusive.value(),
        endIndexExclusive.value() - startIndexInclusive.value());
  }

  public String subString(CodePointIndex startIndexInclusive, CodePointLength length) {
    return new String(codePoints, codePointOffsetInArray + startIndexInclusive.value(), length.value());
  }

  @Override
  public LineNumber lineNumber(CodePointIndex position) {
    return positionResolver.lineNumberFrom(position);
  }

  @Override
  public String sourceAsString() {
    String materialized = sourceString;
    if (materialized == null) {
      // Derived from final fields only, so losing a race just recomputes the same String.
      materialized = new String(codePoints, codePointOffsetInArray, codePointLength);
      sourceString = materialized;
    }
    return materialized;
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
    return new Depth(depthValue);
  }

  @Override
  public CodePointOffset offsetFromParent() {
    // subSource のときは offsetFromParent が意味を持つ
    // root/detached の場合は 0 が自然（親を持たない）
    if (parent == null) {
      return CodePointOffset.ZERO;
    }
    return offsetFromParentValue == 0 ? CodePointOffset.ZERO : new CodePointOffset(offsetFromParentValue);
  }

  @Override
  public CodePointOffset offsetFromRoot() {
    return offsetFromRootValue == 0 ? CodePointOffset.ZERO : new CodePointOffset(offsetFromRootValue);
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

  public static final Source EMPTY = StringSource.createDetachedSource("");

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
    CursorRange built = cursorRange;
    if (built == null) {
      built = CursorRange.fromRootOffset(
          offsetFromRoot(), new CodePointLength(codePointLength), sourceKind, positionResolver);
      cursorRange = built;
    }
    return built;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Every kind of {@code StringSource} builds its cursors at {@code offsetFromRoot} with the
   * same value as the cursors' own {@code offsetFromRoot}, so {@code position()} runs from 0 to
   * the code point length and the range never has to be materialized.
   */
  @Override
  public Range sourceRange() {
    return new Range(0, codePointLength);
  }
}
