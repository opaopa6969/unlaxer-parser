package org.unlaxer.util;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.unlaxer.Source;
import org.unlaxer.StringSource;

// FIXME! current implementation is base on string. change to Source
public class SimpleBuilder implements CharSequence{

  int index;
  StringBuilder builder;

  int tabSpace = 2;
  
  String lf= new String(new char[] {10});
  String crlf= new String(new char[] {13,10});
  String cr = new String(new char[] {13});
  
  public SimpleBuilder() {
    this(0);
  }

  public SimpleBuilder(int index) {
    this(index, new StringBuilder());
  }

  public SimpleBuilder(int index, StringBuilder builder) {
    super();
    this.index = index;
    this.builder = builder;
  }
  
  public SimpleBuilder(int index, SimpleBuilder builder) {
    super();
    this.index = index;
    this.builder = builder.builder;
  }
  
  public SimpleBuilder(Source source) {
    this(0,new StringBuilder(source.toString()));
  }


  public SimpleBuilder incTab() {
    ++index;
    return this;
  }

  public SimpleBuilder decTab() {
    --index;
    return this;

  }
  
  public SimpleBuilder append(CharSequence word) {
    builder.append(word);
    return this;
  }
  
  public SimpleBuilder append(String word) {
    builder.append(word);
    return this;
  }

  public SimpleBuilder withTab(String word) {
    tab();
    builder.append(word);
    return this;
  }


  public SimpleBuilder line(String word) {
    tab();
    append(word + lf);
    return this;
  }

  public SimpleBuilder lines(String lines) {
    String[] split = lines.split("\n");
    for (String line : split) {
      tab();
      append(line + lf);
    }
    return this;
  }
  
//  public SimpleBuilder append(Source word) {
//    builder.append(word);
//    return this;
//  }

  public SimpleBuilder withTab(Source word) {
    tab();
    builder.append(word);
    return this;
  }


  public SimpleBuilder line(Source word) {
    tab();
    append(word + lf);
    return this;
  }

  public SimpleBuilder lines(Source lines) {
    String[] split = lines.split("\n");
    for (String line : split) {
      tab();
      append(line + lf);
    }
    return this;
  }


  public SimpleBuilder lines(Stream<String> linesStream) {
    linesStream.forEach(this::line);
    return this;
  }

  static byte tabBytes = " ".getBytes()[0];

  private SimpleBuilder tab() {
    byte[] tabs = new byte[index];
    for (int i = 0; i < index * tabSpace; i++) {
      tabs[i] = tabBytes;
    }
    builder.append(new String(tabs));
    return this;
  }


  public SimpleBuilder w(String word) {
    word = word == null ? "" : word;
    append("\"" + word.replaceAll("\"", "\\\"") + "\"");
    return this;
  }

  public SimpleBuilder p(String word) {
    word = word == null ? "" : word;
    append("(" + word + ")");
    return this;
  }
  
  public SimpleBuilder w(Source word) {
    word = word == null ? StringSource.EMPTY : word;
    append("\"" + word.replaceAll("\"", "\\\"") + "\"");
    return this;
  }

  public SimpleBuilder p(Source word) {
    word = word == null ? StringSource.EMPTY : word;
    append("(" + word + ")");
    return this;
  }


  @Override
  public String toString() {
    return builder.toString();
  }
  
  public Source toSource() {
    return StringSource.createRootSource(builder.toString());
  }

  public SimpleBuilder n() {
    append(lf);
    return this;
  }
  
  public SimpleBuilder lf() {
    append(lf);
    return this;
  }
  
  public SimpleBuilder cr() {
    append(cr);
    return this;
  }
  
  public SimpleBuilder crlf() {
    append(crlf);
    return this;
  }


  @Override
  public int length() {
    return builder.length();
  }

  @Override
  public char charAt(int index) {
    return builder.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return builder.subSequence(start,end);
  }

  public SimpleBuilder append(char[] chars) {
    builder.append(chars);
    return this;
  }

  public int hashCode() {
    return builder.hashCode();
  }

  public int capacity() {
    return builder.capacity();
  }

  public void ensureCapacity(int minimumCapacity) {
    builder.ensureCapacity(minimumCapacity);
  }

  public boolean equals(Object obj) {
    return builder.equals(obj);
  }

  public int compareTo(StringBuilder another) {
    return builder.compareTo(another);
  }

  public StringBuilder append(Object obj) {
    return builder.append(obj);
  }

  public SimpleBuilder append(StringBuffer sb) {
    builder.append(sb);
    
    return this;
  }

  public void trimToSize() {
    builder.trimToSize();
  }


  public SimpleBuilder append(CharSequence s, int start, int end) {
    builder.append(s, start, end);
    return this;
  }

  public SimpleBuilder setLength(int newLength) {
    builder.setLength(newLength);
    return this;
  }


  public SimpleBuilder append(char[] str, int offset, int len) {
    builder.append(str, offset, len);
    return this;
  }

  public SimpleBuilder append(boolean b) {
    builder.append(b);
    return this;
  }

  public SimpleBuilder append(char c) {
    builder.append(c);
    return this;
  }

  public SimpleBuilder append(int i) {
    builder.append(i);
    return this;
  }

  public SimpleBuilder append(long lng) {
    builder.append(lng);
    return this;
  }

  public SimpleBuilder append(float f) {
    builder.append(f);
    return this;
  }

  public SimpleBuilder append(double d) {
    builder.append(d);
    return this;
  }

  public SimpleBuilder appendCodePoint(int codePoint) {
    builder.appendCodePoint(codePoint);
    return this;
  }

  public SimpleBuilder delete(int start, int end) {
    builder.delete(start, end);
    return this;
  }

  public SimpleBuilder deleteCharAt(int index) {
    builder.deleteCharAt(index);
    return this;
  }

  public SimpleBuilder replace(int start, int end, String str) {
    builder.replace(start, end, str);
    return this;
  }

  public SimpleBuilder insert(int index, char[] str, int offset, int len) {
    builder.insert(index, str, offset, len);
    return this;
  }

  public SimpleBuilder insert(int offset, Object obj) {
    builder.insert(offset, obj);
    return this;
  }

  public SimpleBuilder insert(int offset, String str) {
    builder.insert(offset, str);
    return this;
  }

  public SimpleBuilder insert(int offset, char[] str) {
    builder.insert(offset, str);
    return this;
  }

  public int codePointAt(int index) {
    return builder.codePointAt(index);
  }

  public SimpleBuilder insert(int dstOffset, CharSequence s) {
    builder.insert(dstOffset, s);
    return this;
  }

  public SimpleBuilder insert(int dstOffset, CharSequence s, int start, int end) {
    builder.insert(dstOffset, s, start, end);
    return this;
  }

  public SimpleBuilder insert(int offset, boolean b) {
    builder.insert(offset, b);
    return this;
  }

  public SimpleBuilder insert(int offset, char c) {
    builder.insert(offset, c);
    return this;
  }

  public SimpleBuilder insert(int offset, int i) {
    builder.insert(offset, i);
    return this;
  }

  public SimpleBuilder insert(int offset, long l) {
    builder.insert(offset, l);
    return this;
  }

  public int codePointBefore(int index) {
    return builder.codePointBefore(index);
  }

  public SimpleBuilder insert(int offset, float f) {
    builder.insert(offset, f);
    return this;
  }

  public SimpleBuilder insert(int offset, double d) {
    builder.insert(offset, d);
    return this;
  }

  public int indexOf(String str) {
    return builder.indexOf(str);
  }

  public int indexOf(String str, int fromIndex) {
    return builder.indexOf(str, fromIndex);
  }

  public int lastIndexOf(String str) {
    return builder.lastIndexOf(str);
  }

  public int lastIndexOf(String str, int fromIndex) {
    return builder.lastIndexOf(str, fromIndex);
  }

  public SimpleBuilder reverse() {
    builder.reverse();
    return this;
  }

  public int codePointCount(int beginIndex, int endIndex) {
    return builder.codePointCount(beginIndex, endIndex);
  }

  public int offsetByCodePoints(int index, int codePointOffset) {
    return builder.offsetByCodePoints(index, codePointOffset);
  }

  public void getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin) {
    builder.getChars(srcBegin, srcEnd, dst, dstBegin);
  }

  public void setCharAt(int index, char ch) {
    builder.setCharAt(index, ch);
  }

  
  public Source substring(int start) {
    return StringSource.createRootSource(builder.substring(start));
  }


  public Source substring(int start, int end) {
    return StringSource.createRootSource(builder.substring(start, end));
  }

  public IntStream chars() {
    return builder.chars();
  }

  public IntStream codePoints() {
    return builder.codePoints();
  }
  
}