package org.unlaxer;

public interface StringIndexAccessor {
  
  /**
   * Returns the {@code char} value at the
   * specified index. An index ranges from {@code 0} to
   * {@code length() - 1}. The first {@code char} value of the sequence
   * is at index {@code 0}, the next at index {@code 1},
   * and so on, as for array indexing.
   *
   * <p>If the {@code char} value specified by the index is a
   * <a href="Character.html#unicode">surrogate</a>, the surrogate
   * value is returned.
   *
   * @param      index   the index of the {@code char} value.
   * @return     the {@code char} value at the specified index of this string.
   *             The first {@code char} value is at index {@code 0}.
   * @exception  IndexOutOfBoundsException  if the {@code index}
   *             argument is negative or not less than the length of this
   *             string.
   */
  char charAt(int index);
  
  default char charAt(StringIndex index) {
    return charAt(index.value());
  }

  /**
   * Returns the character (Unicode code point) at the specified
   * index. The index refers to {@code char} values
   * (Unicode code units) and ranges from {@code 0} to
   * {@link #length()}{@code  - 1}.
   *
   * <p> If the {@code char} value specified at the given index
   * is in the high-surrogate range, the following index is less
   * than the length of this {@code String}, and the
   * {@code char} value at the following index is in the
   * low-surrogate range, then the supplementary code point
   * corresponding to this surrogate pair is returned. Otherwise,
   * the {@code char} value at the given index is returned.
   *
   * @param      index the index to the {@code char} values
   * @return     the code point value of the character at the
   *             {@code index}
   * @exception  IndexOutOfBoundsException  if the {@code index}
   *             argument is negative or not less than the length of this
   *             string.
   * @since      1.5
   */
  int codePointAt(int index);
  
  /**
   * Returns the character (Unicode code point) before the specified
   * index. The index refers to {@code char} values
   * (Unicode code units) and ranges from {@code 1} to {@link
   * CharSequence#length() length}.
   *
   * <p> If the {@code char} value at {@code (index - 1)}
   * is in the low-surrogate range, {@code (index - 2)} is not
   * negative, and the {@code char} value at {@code (index -
   * 2)} is in the high-surrogate range, then the
   * supplementary code point value of the surrogate pair is
   * returned. If the {@code char} value at {@code index -
   * 1} is an unpaired low-surrogate or a high-surrogate, the
   * surrogate value is returned.
   *
   * @param     index the index following the code point that should be returned
   * @return    the Unicode code point value before the given index.
   * @exception IndexOutOfBoundsException if the {@code index}
   *            argument is less than 1 or greater than the length
   *            of this string.
   * @since     1.5
   */
  int codePointBefore(int index);
  
  /**
   * Returns the number of Unicode code points in the specified text
   * range of this {@code String}. The text range begins at the
   * specified {@code beginIndex} and extends to the
   * {@code char} at index {@code endIndex - 1}. Thus the
   * length (in {@code char}s) of the text range is
   * {@code endIndex-beginIndex}. Unpaired surrogates within
   * the text range count as one code point each.
   *
   * @param beginIndex the index to the first {@code char} of
   * the text range.
   * @param endIndex the index after the last {@code char} of
   * the text range.
   * @return the number of Unicode code points in the specified text
   * range
   * @exception IndexOutOfBoundsException if the
   * {@code beginIndex} is negative, or {@code endIndex}
   * is larger than the length of this {@code String}, or
   * {@code beginIndex} is larger than {@code endIndex}.
   * @since  1.5
   */
  int codePointCount(int beginIndex, int endIndex);
  
  default Count codePointCount(StringIndex beginIndex, StringIndex endIndex) {
    return new Count(codePointCount(beginIndex.value(), endIndex.value()));
  }
  
  /**
   * Returns the index within this {@code String} that is
   * offset from the given {@code index} by
   * {@code codePointOffset} code points. Unpaired surrogates
   * within the text range given by {@code index} and
   * {@code codePointOffset} count as one code point each.
   *
   * @param index the index to be offset
   * @param codePointOffset the offset in code points
   * @return the index within this {@code String}
   * @exception IndexOutOfBoundsException if {@code index}
   *   is negative or larger then the length of this
   *   {@code String}, or if {@code codePointOffset} is positive
   *   and the substring starting with {@code index} has fewer
   *   than {@code codePointOffset} code points,
   *   or if {@code codePointOffset} is negative and the substring
   *   before {@code index} has fewer than the absolute value
   *   of {@code codePointOffset} code points.
   * @since 1.5
   */
  int offsetByCodePoints(int index, int codePointOffset);
  
  default StringIndex offsetByCodePoints(StringIndex index, CodePointOffset codePointOffset) {
    return new StringIndex(offsetByCodePoints(index.value(), codePointOffset.value()));
  }
  
  /**
   * Copies characters from this string into the destination character
   * array.
   * <p>
   * The first character to be copied is at index {@code srcBegin};
   * the last character to be copied is at index {@code srcEnd-1}
   * (thus the total number of characters to be copied is
   * {@code srcEnd-srcBegin}). The characters are copied into the
   * subarray of {@code dst} starting at index {@code dstBegin}
   * and ending at index:
   * <blockquote><pre>
   *     dstBegin + (srcEnd-srcBegin) - 1
   * </pre></blockquote>
   *
   * @param      srcBegin   index of the first character in the string
   *                        to copy.
   * @param      srcEnd     index after the last character in the string
   *                        to copy.
   * @param      dst        the destination array.
   * @param      dstBegin   the start offset in the destination array.
   * @exception IndexOutOfBoundsException If any of the following
   *            is true:
   *            <ul><li>{@code srcBegin} is negative.
   *            <li>{@code srcBegin} is greater than {@code srcEnd}
   *            <li>{@code srcEnd} is greater than the length of this
   *                string
   *            <li>{@code dstBegin} is negative
   *            <li>{@code dstBegin+(srcEnd-srcBegin)} is larger than
   *                {@code dst.length}</ul>
   */
  void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin);
  
  default void getChars(StringIndex srcBegin, StringIndex srcEnd, char dst[], StringIndex dstBegin) {
    getChars(srcBegin.value(), srcEnd.value(), dst, dstBegin.value());
  }
  
  /**
   * Copies characters from this string into the destination byte array. Each
   * byte receives the 8 low-order bits of the corresponding character. The
   * eight high-order bits of each character are not copied and do not
   * participate in the transfer in any way.
   *
   * <p> The first character to be copied is at index {@code srcBegin}; the
   * last character to be copied is at index {@code srcEnd-1}.  The total
   * number of characters to be copied is {@code srcEnd-srcBegin}. The
   * characters, converted to bytes, are copied into the subarray of {@code
   * dst} starting at index {@code dstBegin} and ending at index:
   *
   * <blockquote><pre>
   *     dstBegin + (srcEnd-srcBegin) - 1
   * </pre></blockquote>
   *
   * @deprecated  This method does not properly convert characters into
   * bytes.  As of JDK&nbsp;1.1, the preferred way to do this is via the
   * {@link #getBytes()} method, which uses the platform's default charset.
   *
   * @param  srcBegin
   *         Index of the first character in the string to copy
   *
   * @param  srcEnd
   *         Index after the last character in the string to copy
   *
   * @param  dst
   *         The destination array
   *
   * @param  dstBegin
   *         The start offset in the destination array
   *
   * @throws  IndexOutOfBoundsException
   *          If any of the following is true:
   *          <ul>
   *            <li> {@code srcBegin} is negative
   *            <li> {@code srcBegin} is greater than {@code srcEnd}
   *            <li> {@code srcEnd} is greater than the length of this String
   *            <li> {@code dstBegin} is negative
   *            <li> {@code dstBegin+(srcEnd-srcBegin)} is larger than {@code
   *                 dst.length}
   *          </ul>
   */
  void getBytes(int srcBegin, int srcEnd, byte dst[], int dstBegin);

  default void getBytes(StringIndex srcBegin, StringIndex srcEnd, byte dst[], StringIndex dstBegin) {
    getBytes(srcBegin.value(), srcEnd.value(), dst, dstBegin.value());
  }
  
  /**
   * Tests if two string regions are equal.
   * <p>
   * A substring of this {@code String} object is compared to a substring
   * of the argument other. The result is true if these substrings
   * represent identical character sequences. The substring of this
   * {@code String} object to be compared begins at index {@code toffset}
   * and has length {@code len}. The substring of other to be compared
   * begins at index {@code ooffset} and has length {@code len}. The
   * result is {@code false} if and only if at least one of the following
   * is true:
   * <ul><li>{@code toffset} is negative.
   * <li>{@code ooffset} is negative.
   * <li>{@code toffset+len} is greater than the length of this
   * {@code String} object.
   * <li>{@code ooffset+len} is greater than the length of the other
   * argument.
   * <li>There is some nonnegative integer <i>k</i> less than {@code len}
   * such that:
   * {@code this.charAt(toffset + }<i>k</i>{@code ) != other.charAt(ooffset + }
   * <i>k</i>{@code )}
   * </ul>
   *
   * <p>Note that this method does <em>not</em> take locale into account.  The
   * {@link java.text.Collator} class provides locale-sensitive comparison.
   *
   * @param   toffset   the starting offset of the subregion in this string.
   * @param   other     the string argument.
   * @param   ooffset   the starting offset of the subregion in the string
   *                    argument.
   * @param   len       the number of characters to compare.
   * @return  {@code true} if the specified subregion of this string
   *          exactly matches the specified subregion of the string argument;
   *          {@code false} otherwise.
   */
  boolean regionMatches(int toffset, String other, int ooffset, int len);
  
  default boolean regionMatches(StringIndex toffset, String other, StringIndex ooffset, Length len) {
    return regionMatches(toffset.value(), other , ooffset.value(), len.value());
  }
  
  default boolean regionMatches(StringIndex toffset, CodePointAccessor other, StringIndex ooffset, Length len) {
    return regionMatches(toffset.value(), other.sourceAsString(), ooffset.value(), len.value());
  }
  
  /**
   * Tests if two string regions are equal.
   * <p>
   * A substring of this {@code String} object is compared to a substring
   * of the argument {@code other}. The result is {@code true} if these
   * substrings represent character sequences that are the same, ignoring
   * case if and only if {@code ignoreCase} is true. The substring of
   * this {@code String} object to be compared begins at index
   * {@code toffset} and has length {@code len}. The substring of
   * {@code other} to be compared begins at index {@code ooffset} and
   * has length {@code len}. The result is {@code false} if and only if
   * at least one of the following is true:
   * <ul><li>{@code toffset} is negative.
   * <li>{@code ooffset} is negative.
   * <li>{@code toffset+len} is greater than the length of this
   * {@code String} object.
   * <li>{@code ooffset+len} is greater than the length of the other
   * argument.
   * <li>{@code ignoreCase} is {@code false} and there is some nonnegative
   * integer <i>k</i> less than {@code len} such that:
   * <blockquote><pre>
   * this.charAt(toffset+k) != other.charAt(ooffset+k)
   * </pre></blockquote>
   * <li>{@code ignoreCase} is {@code true} and there is some nonnegative
   * integer <i>k</i> less than {@code len} such that:
   * <blockquote><pre>
   * Character.toLowerCase(Character.toUpperCase(this.charAt(toffset+k))) !=
   Character.toLowerCase(Character.toUpperCase(other.charAt(ooffset+k)))
   * </pre></blockquote>
   * </ul>
   *
   * <p>Note that this method does <em>not</em> take locale into account,
   * and will result in unsatisfactory results for certain locales when
   * {@code ignoreCase} is {@code true}.  The {@link java.text.Collator} class
   * provides locale-sensitive comparison.
   *
   * @param   ignoreCase   if {@code true}, ignore case when comparing
   *                       characters.
   * @param   toffset      the starting offset of the subregion in this
   *                       string.
   * @param   other        the string argument.
   * @param   ooffset      the starting offset of the subregion in the string
   *                       argument.
   * @param   len          the number of characters to compare.
   * @return  {@code true} if the specified subregion of this string
   *          matches the specified subregion of the string argument;
   *          {@code false} otherwise. Whether the matching is exact
   *          or case insensitive depends on the {@code ignoreCase}
   *          argument.
   */
  boolean regionMatches(boolean ignoreCase, int toffset, String other, int ooffset, int len);
  
  default boolean regionMatches(boolean ignoreCase, StringIndex toffset, String other, StringIndex ooffset, Length len) {
    return regionMatches(ignoreCase, toffset.value(), other, ooffset.value(), len.value());
  }
  
  default boolean regionMatches(boolean ignoreCase, StringIndex toffset, CodePointAccessor other, StringIndex ooffset, Length len) {
    return regionMatches(ignoreCase, toffset.value(), other.sourceAsString(), ooffset.value(), len.value());
  }
  
  /**
   * Tests if the substring of this string beginning at the
   * specified index starts with the specified prefix.
   *
   * @param   prefix    the prefix.
   * @param   toffset   where to begin looking in this string.
   * @return  {@code true} if the character sequence represented by the
   *          argument is a prefix of the substring of this object starting
   *          at index {@code toffset}; {@code false} otherwise.
   *          The result is {@code false} if {@code toffset} is
   *          negative or greater than the length of this
   *          {@code String} object; otherwise the result is the same
   *          as the result of the expression
   *          <pre>
   *          this.substring(toffset).startsWith(prefix)
   *          </pre>
   */
  boolean startsWith(String prefix, int toffset);
  
  default boolean startsWith(String prefix, StringIndex toffset) {
    return startsWith(prefix, toffset.value());
  }
  default boolean startsWith(CodePointAccessor prefix, StringIndex toffset) {
    return startsWith(prefix.sourceAsString(), toffset.value());
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
  int indexOf(int ch, int fromIndex);
  
  default StringIndexWithNegativeValue indexOf(CodePoint codePoint, StringIndex fromIndex) {
    return new StringIndexWithNegativeValue(indexOf(codePoint.value(),fromIndex.value()));
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
  int lastIndexOf(int ch, int fromIndex);
  
  default StringIndexWithNegativeValue lastIndexOf(CodePoint codePoint, StringIndex fromIndex) {
    return new StringIndexWithNegativeValue(lastIndexOf(codePoint.value(), fromIndex.value()));
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
  int indexOf(String str, int fromIndex);
  
  default StringIndexWithNegativeValue indexOf(CodePointAccessor str, StringIndex fromIndex) {
    return new StringIndexWithNegativeValue(indexOf(str.sourceAsString(),fromIndex.value()));
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
  int lastIndexOf(String str, int fromIndex);

  /**
   * Returns a string that is a substring of this string. The
   * substring begins with the character at the specified index and
   * extends to the end of this string. <p>
   * Examples:
   * <blockquote><pre>
   * "unhappy".substring(2) returns "happy"
   * "Harbison".substring(3) returns "bison"
   * "emptiness".substring(9) returns "" (an empty string)
   * </pre></blockquote>
   *
   * @param      beginIndex   the beginning index, inclusive.
   * @return     the specified substring.
   * @exception  IndexOutOfBoundsException  if
   *             {@code beginIndex} is negative or larger than the
   *             length of this {@code String} object.
   */
  String substring(int beginIndex);
  
  /**
   * Returns a string that is a substring of this string. The
   * substring begins at the specified {@code beginIndex} and
   * extends to the character at index {@code endIndex - 1}.
   * Thus the length of the substring is {@code endIndex-beginIndex}.
   * <p>
   * Examples:
   * <blockquote><pre>
   * "hamburger".substring(4, 8) returns "urge"
   * "smiles".substring(1, 5) returns "mile"
   * </pre></blockquote>
   *
   * @param      beginIndex   the beginning index, inclusive.
   * @param      endIndex     the ending index, exclusive.
   * @return     the specified substring.
   * @exception  IndexOutOfBoundsException  if the
   *             {@code beginIndex} is negative, or
   *             {@code endIndex} is larger than the length of
   *             this {@code String} object, or
   *             {@code beginIndex} is larger than
   *             {@code endIndex}.
   */
  String substring(int beginIndex, int endIndex);
  
  /**
   * Returns a character sequence that is a subsequence of this sequence.
   *
   * <p> An invocation of this method of the form
   *
   * <blockquote><pre>
   * str.subSequence(begin,&nbsp;end)</pre></blockquote>
   *
   * behaves in exactly the same way as the invocation
   *
   * <blockquote><pre>
   * str.substring(begin,&nbsp;end)</pre></blockquote>
   *
   * @apiNote
   * This method is defined so that the {@code String} class can implement
   * the {@link CharSequence} interface.
   *
   * @param   beginIndex   the begin index, inclusive.
   * @param   endIndex     the end index, exclusive.
   * @return  the specified subsequence.
   *
   * @throws  IndexOutOfBoundsException
   *          if {@code beginIndex} or {@code endIndex} is negative,
   *          if {@code endIndex} is greater than {@code length()},
   *          or if {@code beginIndex} is greater than {@code endIndex}
   *
   * @since 1.4
   * @spec JSR-51
   */
  CharSequence subSequence(int beginIndex, int endIndex);
}
