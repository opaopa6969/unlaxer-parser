package org.unlaxer;

/** Count of extended grapheme clusters, independent of code points and display width. */
public record GraphemeLength(int value) {
    public static final GraphemeLength ZERO = new GraphemeLength(0);
    public GraphemeLength {
        if (value < 0) { throw new IllegalArgumentException("negative grapheme length"); }
    }
    public static GraphemeLength of(int value) { return new GraphemeLength(value); }
}
