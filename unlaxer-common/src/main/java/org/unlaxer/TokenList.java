package org.unlaxer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.unlaxer.Source.SourceKind;

public class TokenList implements List<Token>{
  
  /*
   * Every token owns two of these lists and more than half of them (both lists of every leaf)
   * stay empty for as long as the tree lives, so an empty TokenList backs onto one shared
   * immutable list and only allocates an ArrayList when it is first written to. Reads on the
   * shared list behave exactly like reads on an empty ArrayList. (perf #276)
   */
  private static final List<Token> EMPTY_BACKING = List.of();

  List<Token> tokens;

  public TokenList(List<Token> tokens) {
    super();
    this.tokens = tokens.isEmpty() ? EMPTY_BACKING : new ArrayList<>(tokens);
  }
  
  public TokenList(Token... tokens) {
    super();
    if (tokens.length == 0) {
      this.tokens = EMPTY_BACKING;
      return;
    }
    this.tokens = new ArrayList<>(tokens.length);
    for (Token token : tokens) {
      this.tokens.add(token);
    }
  }
  
  public TokenList() {
    super();
    this.tokens = EMPTY_BACKING;
  }

  /** Creates an empty list that can hold {@code initialCapacity} tokens without growing. */
  public TokenList(int initialCapacity) {
    super();
    this.tokens = initialCapacity == 0 ? EMPTY_BACKING : new ArrayList<>(initialCapacity);
  }

  /** Returns the backing list, replacing the shared empty one on the first write. */
  private List<Token> mutable() {
    List<Token> backing = tokens;
    if (backing == EMPTY_BACKING) {
      backing = new ArrayList<>();
      tokens = backing;
    }
    return backing;
  }

  private List<Token> mutable(int expectedSize) {
    List<Token> backing = tokens;
    if (backing == EMPTY_BACKING) {
      backing = new ArrayList<>(expectedSize < 1 ? 1 : expectedSize);
      tokens = backing;
    }
    return backing;
  }
  
  public static TokenList of(List<Token> tokens) {
    return new TokenList(tokens);
  }
  
  public static TokenList of(Token... tokens) {
    return new TokenList(tokens);
  }

  public void forEach(Consumer<? super Token> action) {
    tokens.forEach(action);
  }

  public int size() {
    return tokens.size();
  }

  public boolean isEmpty() {
    return tokens.isEmpty();
  }

  public boolean contains(Object o) {
    return tokens.contains(o);
  }

  public Iterator<Token> iterator() {
    return tokens.iterator();
  }

  public Object[] toArray() {
    return tokens.toArray();
  }

  public <T> T[] toArray(T[] a) {
    return tokens.toArray(a);
  }

  public boolean add(Token e) {
    return mutable().add(e);
  }

  public boolean remove(Object o) {
    return !tokens.isEmpty() && tokens.remove(o);
  }

  public boolean containsAll(Collection<?> c) {
    return tokens.containsAll(c);
  }

  public boolean addAll(Collection<? extends Token> c) {
    return c.isEmpty() ? false : mutable(c.size()).addAll(c);
  }

  public boolean addAll(int index, Collection<? extends Token> c) {
    if (c.isEmpty()) {
      if (tokens == EMPTY_BACKING && index != 0) {
        throw new IndexOutOfBoundsException("Index: " + index + ", Size: 0");
      }
      return tokens == EMPTY_BACKING ? false : tokens.addAll(index, c);
    }
    return mutable(c.size()).addAll(index, c);
  }

  public boolean removeAll(Collection<?> c) {
    return !tokens.isEmpty() && tokens.removeAll(c);
  }

  public <T> T[] toArray(IntFunction<T[]> generator) {
    return tokens.toArray(generator);
  }

  public boolean retainAll(Collection<?> c) {
    return !tokens.isEmpty() && tokens.retainAll(c);
  }

  public void replaceAll(UnaryOperator<Token> operator) {
    if (!tokens.isEmpty()) tokens.replaceAll(operator);
  }

  public void sort(Comparator<? super Token> c) {
    if (!tokens.isEmpty()) tokens.sort(c);
  }

  public void clear() {
    if (!tokens.isEmpty()) tokens.clear();
  }

  public boolean equals(Object o) {
    return tokens.equals(o);
  }

  public int hashCode() {
    return tokens.hashCode();
  }

  public Token get(TokenIndex index) {
    return tokens.get(index.value());
  }
  
  public Token get(int index) {
    return tokens.get(index);
  }

  public boolean removeIf(Predicate<? super Token> filter) {
    return !tokens.isEmpty() && tokens.removeIf(filter);
  }

  public Token set(int index, Token element) {
    if (tokens == EMPTY_BACKING) {
      throw new IndexOutOfBoundsException("Index: " + index + ", Size: 0");
    }
    return tokens.set(index, element);
  }
  
  public Token set(TokenIndex index, Token element) {
    return tokens.set(index.value(), element);
  }

  public void add(int index, Token element) {
    mutable().add(index, element);
  }
  
  public void add(TokenIndex index, Token element) {
    tokens.add(index.value(), element);
  }

  public Token remove(int index) {
    if (tokens == EMPTY_BACKING) {
      throw new IndexOutOfBoundsException("Index: " + index + ", Size: 0");
    }
    return tokens.remove(index);
  }
  
  public Token remove(TokenIndex index) {
    return tokens.remove(index.value());
  }

  public int indexOf(Object o) {
    return tokens.indexOf(o);
  }

  public int lastIndexOf(Object o) {
    return tokens.lastIndexOf(o);
  }

  public ListIterator<Token> listIterator() {
    return mutable().listIterator();
  }

  public ListIterator<Token> listIterator(int index) {
    return mutable().listIterator(index);
  }
  
  public ListIterator<Token> listIterator(TokenIndex index) {
    return tokens.listIterator(index.value());
  }

  public List<Token> subList(int fromIndex, int toIndex) {
    return mutable().subList(fromIndex, toIndex);
  }
  
  public List<Token> subList(TokenIndex fromIndexInclusive , TokenIndex toIndexExclusive) {
    return tokens.subList(fromIndexInclusive.value(), toIndexExclusive.value());
  }

  public Spliterator<Token> spliterator() {
    return tokens.spliterator();
  }

  public Stream<Token> stream() {
    return tokens.stream();
  }

  public Stream<Token> parallelStream() {
    return tokens.parallelStream();
  }
  
  public CursorRange combinedCursorRange(PositionResolver positionResolver) {
    
    return combinedCursorRange(this,positionResolver);
  }
  
  public static CursorRange combinedCursorRange(TokenList tokens , PositionResolver positionResolver) {
    
    Optional<Token> firstPrintableToken = tokens.firstPrintableToken();
    if(tokens.isEmpty() || firstPrintableToken.isEmpty()) {
      return new CursorRange(
          new StartInclusiveCursorImpl(positionResolver), 
          new EndExclusiveCursorImpl(positionResolver).incrementPosition());
    }
    
    CursorRange first = firstPrintableToken.get().getSource().cursorRange();
    CursorRange last = tokens.lastPrintableToken().get().getSource().cursorRange();
    
    return new CursorRange(
          new StartInclusiveCursorImpl(positionResolver)
            .setPosition(first.startIndexInclusive.position()),
          new EndExclusiveCursorImpl(positionResolver)
            .setPosition(last.endIndexExclusive.position())
     );
  }
  
  public Optional<Token> firstPrintableToken(){
    for(int i = 0 ; i < tokens.size() ; i++) {
      Token token = tokens.get(i);
      if(token.getSource().isPresent()) {
        return Optional.of(token);
      }
    }
    return Optional.empty();
  }
  
  public Optional<Token> lastPrintableToken(){
    for(int i = tokens.size()-1 ; i >=0 ; i--) {
      Token token = tokens.get(i);
      if(token.getSource().isPresent()) {
        return Optional.of(token);
      }
    }
    return Optional.empty();
  }
  
  public Source toSource(SourceKind sourceKind) {
    // toSource(TokenList, SourceKind) only reads the list, so no defensive copy is needed.
    return toSource(this , sourceKind);
  }

  
  public static Source toSource(TokenList tokens , SourceKind sourceKind) {
    
    Optional<Token> firstPrintableToken = tokens.firstPrintableToken();
    if(tokens.isEmpty() || firstPrintableToken.isEmpty()) {
      if (sourceKind == SourceKind.subSource) {
        // A zero-width real child can still carry an exact source coordinate.
        // Do not replace it with an unrelated detached empty source.
        for (Token token : tokens) {
          if (token.tokenKind.isReal() && token.source != null
              && token.source.sourceKind() == SourceKind.subSource) {
            return token.source;
          }
        }
      }
      return StringSource.createDetachedSource("");
    }
    
    Token token = firstPrintableToken.get();
    
    if(sourceKind == SourceKind.subSource) {
      Source view = contiguousRootSliceOf(tokens, token);
      if (view != null) {
        return view;
      }
    }
    
    StringBuilder collect = new StringBuilder();
    for (int i = 0, size = tokens.size(); i < size; i++) {
      collect.append(tokens.get(i).getSource().toString());
    }
    
    CodePointOffset offsetFromRoot = token.source.offsetFromRoot();
    
    if(sourceKind == SourceKind.subSource) {
      return StringSource.createSubSource(collect.toString() , token.source.root() , offsetFromRoot);
    }else {
      return StringSource.create(collect.toString(), sourceKind );
    }
  }

  /**
   * Returns the concatenation of {@code tokens} as a view over the root's code points, or
   * {@code null} when the children are not one contiguous slice of it.
   *
   * <p>Concatenating the children's text and decoding it again re-created, on every commit, a
   * String and an {@code int[]} that the root already holds - a 20 KB input retained 24x its own
   * size in such copies. The children of a committed rule are consecutive by construction in
   * practice, but nothing enforces it (trivia handling and rewritten sources can break it), so the
   * offsets are checked and the copying path is kept for everything else. (perf #276)
   */
  private static Source contiguousRootSliceOf(TokenList tokens, Token firstPrintable) {
    Source rootSource = firstPrintable.source.root();
    if (!(rootSource instanceof StringSource root) || !root.isRoot()) {
      return null;
    }
    int cursor = -1;
    int start = -1;
    for (int i = 0, size = tokens.size(); i < size; i++) {
      Source source = tokens.get(i).getSource();
      if (!(source instanceof StringSource stringSource)) {
        return null;
      }
      int length = stringSource.codePointLength().value();
      if (length == 0) {
        continue;
      }
      if (!stringSource.sharesCodePointArrayWith(root)) {
        return null;
      }
      int offset = stringSource.codePointOffsetInArray();
      if (cursor == -1) {
        start = offset;
      } else if (offset != cursor) {
        return null;
      }
      cursor = offset + length;
    }
    if (cursor == -1) {
      return null;
    }
    return StringSource.createSubSourceView(root, start, cursor - start);
  }
}
