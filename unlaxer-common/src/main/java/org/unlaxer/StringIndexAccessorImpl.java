package org.unlaxer;

public class StringIndexAccessorImpl implements StringIndexAccessor{
  
  public StringIndexAccessorImpl(String source) {
    super();
    this.source = source;
  }

  String source;

  @Override
  public char charAt(int index) {
    return source.charAt(index);
  }

  @Override
  public int codePointAt(int index) {
    return source.codePointAt(index);
  }

  @Override
  public int codePointBefore(int index) {
    return source.codePointBefore(index);
  }

  @Override
  public int codePointCount(int beginIndex, int endIndex) {
    return source.codePointCount(beginIndex, endIndex);
  }

  @Override
  public int offsetByCodePoints(int index, int codePointOffset) {
    return source.offsetByCodePoints(index, codePointOffset);
  }

  @Override
  public void getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin) {
    source.getChars(srcBegin, srcEnd, dst, dstBegin);
  }

  @Override
  public void getBytes(int srcBegin, int srcEnd, byte[] dst, int dstBegin) {
  }

  @Override
  public boolean regionMatches(int toffset, String other, int ooffset, int len) {
    return source.regionMatches(toffset, other, ooffset, len);
  }

  @Override
  public boolean regionMatches(boolean ignoreCase, int toffset, String other, int ooffset, int len) {
    return source.regionMatches(ignoreCase, toffset, other, ooffset, len);
  }

  @Override
  public boolean startsWith(String prefix, int toffset) {
    return source.startsWith(prefix, toffset);
  }

  @Override
  public int indexOf(int ch, int fromIndex) {
    return source.indexOf(ch,fromIndex);
  }

  @Override
  public int lastIndexOf(int ch, int fromIndex) {
    return source.lastIndexOf(ch, fromIndex);
  }

  @Override
  public int indexOf(String str, int fromIndex) {
    return source.indexOf(fromIndex, fromIndex);
  }

  @Override
  public int lastIndexOf(String str, int fromIndex) {
    return source.lastIndexOf(fromIndex, fromIndex);
  }

  @Override
  public String substring(int beginIndex) {
    return source.substring(beginIndex);
  }

  @Override
  public String substring(int beginIndex, int endIndex) {
    return source.substring(beginIndex,endIndex);
  }

  @Override
  public CharSequence subSequence(int beginIndex, int endIndex) {
    return source.subSequence(beginIndex, endIndex);
  }
}