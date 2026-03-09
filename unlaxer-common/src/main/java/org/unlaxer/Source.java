package org.unlaxer;

import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;
import java.util.stream.Stream;

import org.unlaxer.util.FactoryBoundCache;
import org.unlaxer.util.function.TriFunction;

public interface Source extends CodePointAccessor, PositionResolver {

  public static class Origin {
    final Source root;
    final CursorRange rangeInRoot;

    public Origin(Source root, CursorRange rangeInRoot) {
      super();
      this.root = root;
      this.rangeInRoot = rangeInRoot;
    }

    public Source root() {
      return root;
    }

    public CursorRange rangeInRoot() {
      return rangeInRoot;
    }

  }

  public enum SourceKind {
    root,
    detached,
    attached,
    subSource;

    public boolean isRoot() {
      return this == root;
    }

    public boolean isDetached() {
      return this == detached;
    }

    public boolean isAttached() {
      return this == attached;
    }

    public boolean isSubSource() {
      return this == subSource;
    }
  }

  PositionResolver positionResolver();

  SourceKind sourceKind();

  CursorRange cursorRange();

  Stream<Source> linesAsSource();

  default Source reRoot() {
    return StringSource.createRootSource(this.sourceAsString());
  }

  default Source reRoot(UnaryOperator<String> transformer) {
    return StringSource.createRootSource(transformer.apply(this.sourceAsString()));
  }

  default Optional<Origin> origin() {
    return Optional.empty();
  }

//  default CursorRange cursorRangeOnParent() {
//    
//  }

//  CursorRange cursorRangeOnRoot();

  CodePointOffset offsetFromParent();

//  static InfiniteLoopDetector infiniteLoopDetector = new InfiniteLoopDetector();

  default CodePointOffset offsetFromRoot() {
    // root は常に 0
    if (isRoot()) {
      return CodePointOffset.ZERO;
    }

    // detached は「独立root」扱い：起源座標を共有しないので 0
    if (sourceKind().isDetached()) {
      return CodePointOffset.ZERO;
    }

    // subSource のみ：親チェーンを辿って合成
    CodePointOffset sum = CodePointOffset.ZERO;

    Source cur = thisSource();
    while (true) {
      sum = sum.newWithAdd(cur.offsetFromParent());

      Optional<Source> p = cur.parent();
      if (p.isEmpty()) {
        // ここに来るのは「subSourceなのに親がない」＝不整合。
        // でも落とさない。デバッグ表示などで死なないための最後の砦。
        return CodePointOffset.ZERO;
      }

      cur = p.get();
      if (cur.isRoot()) {
        return sum;
      }
      if (cur.sourceKind().isDetached()) {
        // 万一 chain 上に detached が混ざったら、そこから先は共有不能
        return CodePointOffset.ZERO;
      }
    }
  }

  Source peek(CodePointIndex startIndexInclusive, CodePointLength length);

  default Source peek(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive) {
    return peek(startIndexInclusive, new CodePointLength(endIndexExclusive.newWithMinus(startIndexInclusive)));
  }

  default Source peekLast(CodePointIndex endIndexInclusive, CodePointLength length) {

    CodePointIndex start = endIndexInclusive.newWithMinus(length)
        .createIfMatch(CodePointIndex::isNegative, () -> new CodePointIndex(0));
    return peek(start, endIndexInclusive);
  }

  default Source subSource(CursorRange cursorRange) {
    return subSource(cursorRange.startIndexInclusive.position(), cursorRange.endIndexExclusive.position());
  }

  Source subSource(CodePointIndex startIndexInclusive, CodePointIndex endIndexExclusive);

  Source subSource(CodePointIndex startIndexInclusive, CodePointLength codePointLength);

  Optional<Source> parent();

  Source root();

  Source thisSource();

  Depth depth();

  boolean isRoot();

  default boolean isPresent() {
    return stringLength().value() > 0;
  }

  default boolean isEmpty() {
    return stringLength().value() == 0;
  }

  default boolean hasParent() {
    return parent().isPresent();
  }

  default PositionResolver createPositionResolver(int[] codePoints) {
    return PositionResolver.createPositionResolver(codePoints);
  }

//  Function<String,Source> stringToSource();
  Function<Source, String> sourceToStgring();

  TriFunction<Source, String, CodePointOffset, Source> parentSourceAndStringToSource();

  default Source subSource(CodePointIndex beginIndex) {
    return parentSourceAndStringToSource().apply(
        thisSource(),
        stringIndexAccessor().substring(toStringIndex(beginIndex).value()),
        new CodePointOffset(beginIndex));
  }

//  default Source subSource(CodePointIndex beginIndex, CodePointIndex endIndex) {
//      return parentSourceAndStringToSource().apply(
//          thisSource(),
//          stringIndexAccessor().substring(toStringIndex(beginIndex).value(),toStringIndex(endIndex).value()),
//          new CodePointOffset(beginIndex)
//      );
//  }
//  
//  default Source subSource(CodePointIndex beginIndex, CodePointLength length) {
//    
//    return subSource(beginIndex , beginIndex.newWithAdd(length()));
//  }

  default Source concat(CodePointAccessor str) {
    return parentSourceAndStringToSource().apply(
        thisSource(),
        concat(str.sourceAsString()),
        CodePointOffset.ZERO);
  }

  default Source replaceAsStringInterface(char oldChar, char newChar) {
    return parentSourceAndStringToSource().apply(
        thisSource(),
        replace(oldChar, newChar),
        CodePointOffset.ZERO);
  }

  default Source replaceFirst(String regex, CodePointAccessor replacement) {
    return parentSourceAndStringToSource().apply(
        thisSource(),
        replaceFirst(regex, replacement.toString()),
        CodePointOffset.ZERO);
  }

  default Source replaceAll(String regex, CodePointAccessor replacement) {
    return parentSourceAndStringToSource().apply(
        thisSource(),
        replaceAll(regex, replacement.toString()),
        CodePointOffset.ZERO);
  }

  default Source replaceAsStringInterface(CharSequence target, CharSequence replacement) {
    return parentSourceAndStringToSource().apply(
        thisSource(),
        replace(target, replacement),
        CodePointOffset.ZERO);
  }

  default Source[] splitAsStringInterface(String regex, int limit) {

    String[] returning = split(regex, limit);

    Source[] result = new Source[returning.length];

    int i = 0;
    int index = 0;
    for (String string : returning) {
      int indexOf = indexOf(string, index);
      if (indexOf == -1) {
        throw new IllegalArgumentException();
      }
      CodePointIndex codePointIndex = toCodePointIndex(new StringIndex(indexOf));

      result[i++] = parentSourceAndStringToSource().apply(
          thisSource(), string, new CodePointOffset(codePointIndex));

      index++;
    }
    return result;
  }

  default Source[] splitAsStringInterface(String regex) {

    String[] returning = split(regex);

    Source[] result = new Source[returning.length];

    int i = 0;
    int index = 0;
    for (String string : returning) {
      int indexOf = indexOf(string, index);
      if (indexOf == -1) {
        throw new IllegalArgumentException();
      }
      CodePointIndex codePointIndex = toCodePointIndex(new StringIndex(indexOf));

      result[i++] = parentSourceAndStringToSource().apply(
          thisSource(), string, new CodePointOffset(codePointIndex));

      index++;
    }
    return result;
  }

  default Source toLowerCaseAsStringInterface(Locale locale) {
    return parentSourceAndStringToSource().apply(
        thisSource(), toLowerCase(locale), CodePointOffset.ZERO);
  }

  default Source toLowerCaseAsStringInterface() {
    return parentSourceAndStringToSource().apply(
        thisSource(), toLowerCase(), CodePointOffset.ZERO);
  }

  default Source toUpperCaseAsStringInterface(Locale locale) {
    return parentSourceAndStringToSource().apply(
        thisSource(), toUpperCase(locale), CodePointOffset.ZERO);
  }

  default Source toUpperCaseAsStringInterface() {
    return parentSourceAndStringToSource().apply(
        thisSource(), toUpperCase(), CodePointOffset.ZERO);
  }

  default Source trimAsStringInterface() {
    return parentSourceAndStringToSource().apply(
        thisSource(), trim(), CodePointOffset.ZERO);
  }

  default Source stripAsStringInterface() {

    return parentSourceAndStringToSource().apply(
        thisSource(), strip(), CodePointOffset.ZERO);
  }

  default Source stripLeadingAsStringInterface() {
    return parentSourceAndStringToSource().apply(
        thisSource(), stripLeading(), CodePointOffset.ZERO);
  }

  default Source stripTrailingAsStringInterface() {
    return parentSourceAndStringToSource().apply(
        thisSource(), stripTrailing(), CodePointOffset.ZERO);
  }

  default Stream<Source> linesAsStringInterface() {
    TriFunction<Source, String, CodePointOffset, Source> parentSourceAndStringToSource = parentSourceAndStringToSource();

    AtomicInteger index = new AtomicInteger();
    return lines().map(line -> {

      int indexOf = indexOf(line, index.intValue());
      index.set(indexOf);
      index.incrementAndGet();
      CodePointIndex codePointIndex = toCodePointIndex(new StringIndex(indexOf));
      return parentSourceAndStringToSource.apply(thisSource(), line, new CodePointOffset(codePointIndex));
    });
  }

  default Source repeatAsStringInterface(int count) {

    return parentSourceAndStringToSource().apply(
        thisSource(), repeat(count), CodePointOffset.ZERO);
  }

//  public boolean detached();

//	/**
//   * Returns a {@code Collector} that concatenates the input elements into a
//   * {@code String}, in encounter order.
//   *
//   * @return a {@code Collector} that concatenates the input elements into a
//   * {@code String}, in encounter order
//   */
//  public static Collector<CharSequence, ?, Source> joining() {
//      return new CollectorImpl<CharSequence, SimpleBuilder, Source>(
//              SimpleBuilder::new, SimpleBuilder::append,
//              (r1, r2) -> { 
//                SimpleBuilder append = r1.append(r2); 
//                return append; 
//              },
//              SimpleBuilder::toSource, CH_NOID);
//  }

  /**
   * Returns a {@code Collector} that concatenates the input elements into a
   * {@code String}, in encounter order.
   *
   * @return a {@code Collector} that concatenates the input elements into a
   *         {@code String}, in encounter order
   */
  public static Collector<CharSequence, ?, Source> joining() {
    return joining("");
  }

  /**
   * Returns a {@code Collector} that concatenates the input elements,
   * separated by the specified delimiter, in encounter order.
   *
   * @param delimiter the delimiter to be used between each element
   * @return A {@code Collector} which concatenates CharSequence elements,
   *         separated by the specified delimiter, in encounter order
   */
  public static Collector<CharSequence, ?, Source> joining(CharSequence delimiter) {
    return joining(delimiter, "", "");
  }

  /**
   * Returns a {@code Collector} that concatenates the input elements,
   * separated by the specified delimiter, with the specified prefix and
   * suffix, in encounter order.
   *
   * @param delimiter the delimiter to be used between each element
   * @param prefix    the sequence of characters to be used at the beginning
   *                  of the joined result
   * @param suffix    the sequence of characters to be used at the end
   *                  of the joined result
   * @return A {@code Collector} which concatenates CharSequence elements,
   *         separated by the specified delimiter, in encounter order
   */
  public static Collector<CharSequence, ?, Source> joining(CharSequence delimiter,
      CharSequence prefix,
      CharSequence suffix) {
    return new CollectorImpl<>(
        () -> new SourceJoiner(delimiter, prefix, suffix),
        SourceJoiner::add, SourceJoiner::merge,
        SourceJoiner::toSource, CH_NOID);
  }

  /**
   * Simple implementation class for {@code Collector}.
   *
   * @param <T> the type of elements to be collected
   * @param <R> the type of the result
   */
  static class CollectorImpl<T, A, R> implements Collector<T, A, R> {
    private final Supplier<A> supplier;
    private final BiConsumer<A, T> accumulator;
    private final BinaryOperator<A> combiner;
    private final Function<A, R> finisher;
    private final Set<Characteristics> characteristics;

    CollectorImpl(Supplier<A> supplier,
        BiConsumer<A, T> accumulator,
        BinaryOperator<A> combiner,
        Function<A, R> finisher,
        Set<Characteristics> characteristics) {
      this.supplier = supplier;
      this.accumulator = accumulator;
      this.combiner = combiner;
      this.finisher = finisher;
      this.characteristics = characteristics;
    }

    CollectorImpl(Supplier<A> supplier,
        BiConsumer<A, T> accumulator,
        BinaryOperator<A> combiner,
        Set<Characteristics> characteristics) {
      this(supplier, accumulator, combiner, castingIdentity(), characteristics);
    }

    @Override
    public BiConsumer<A, T> accumulator() {
      return accumulator;
    }

    @Override
    public Supplier<A> supplier() {
      return supplier;
    }

    @Override
    public BinaryOperator<A> combiner() {
      return combiner;
    }

    @Override
    public Function<A, R> finisher() {
      return finisher;
    }

    @Override
    public Set<Characteristics> characteristics() {
      return characteristics;
    }
  }

  @SuppressWarnings("unchecked")
  private static <I, R> Function<I, R> castingIdentity() {
    return i -> (R) i;
  }

  static final Set<Collector.Characteristics> CH_NOID = Collections.emptySet();

  public static final Source EMPTY = StringSource.createRootSource("");

  public static final FactoryBoundCache<Source, Source> SUB_EMPTY = new FactoryBoundCache<>(
      rootSource -> StringSource.createSubSource("", rootSource, CodePointOffset.ZERO));
}
