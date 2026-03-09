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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.unlaxer.Source.SourceKind;

public class TokenList implements List<Token>{
  
  List<Token> tokens;

  public TokenList(List<Token> tokens) {
    super();
    this.tokens = new ArrayList<>(tokens);
  }
  
  public TokenList(Token... tokens) {
    super();
    this.tokens = new ArrayList<>();
    for (Token token : tokens) {
      this.tokens.add(token);
    }
  }
  
  public TokenList() {
    super();
    this.tokens = new ArrayList<>();
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
    return tokens.add(e);
  }

  public boolean remove(Object o) {
    return tokens.remove(o);
  }

  public boolean containsAll(Collection<?> c) {
    return tokens.containsAll(c);
  }

  public boolean addAll(Collection<? extends Token> c) {
    return tokens.addAll(c);
  }

  public boolean addAll(int index, Collection<? extends Token> c) {
    return tokens.addAll(index, c);
  }

  public boolean removeAll(Collection<?> c) {
    return tokens.removeAll(c);
  }

  public <T> T[] toArray(IntFunction<T[]> generator) {
    return tokens.toArray(generator);
  }

  public boolean retainAll(Collection<?> c) {
    return tokens.retainAll(c);
  }

  public void replaceAll(UnaryOperator<Token> operator) {
    tokens.replaceAll(operator);
  }

  public void sort(Comparator<? super Token> c) {
    tokens.sort(c);
  }

  public void clear() {
    tokens.clear();
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
    return tokens.removeIf(filter);
  }

  public Token set(int index, Token element) {
    return tokens.set(index, element);
  }
  
  public Token set(TokenIndex index, Token element) {
    return tokens.set(index.value(), element);
  }

  public void add(int index, Token element) {
    tokens.add(index, element);
  }
  
  public void add(TokenIndex index, Token element) {
    tokens.add(index.value(), element);
  }

  public Token remove(int index) {
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
    return tokens.listIterator();
  }

  public ListIterator<Token> listIterator(int index) {
    return tokens.listIterator(index);
  }
  
  public ListIterator<Token> listIterator(TokenIndex index) {
    return tokens.listIterator(index.value());
  }

  public List<Token> subList(int fromIndex, int toIndex) {
    return tokens.subList(fromIndex, toIndex);
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
    return toSource(TokenList.of(tokens) , sourceKind);
  }

  
  public static Source toSource(TokenList tokens , SourceKind sourceKind) {
    
    Optional<Token> firstPrintableToken = tokens.firstPrintableToken();
    if(tokens.isEmpty() || firstPrintableToken.isEmpty()) {
      return StringSource.createSubSource("", null , new CodePointOffset(0));
    }
    
    String collect = tokens.stream()
      .map(Token::getSource)
      .map(Source::toString)
      .collect(Collectors.joining());
    
    Token token = firstPrintableToken.get();
    
    CodePointOffset offsetFromRoot = token.source.offsetFromRoot();
    
    if(sourceKind == SourceKind.subSource) {
      return StringSource.createSubSource(collect , token.source.root() , offsetFromRoot);
    }else {
      return StringSource.create(collect, sourceKind );
    }
  }
}