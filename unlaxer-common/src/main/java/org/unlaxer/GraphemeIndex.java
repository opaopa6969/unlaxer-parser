package org.unlaxer;

/** Zero-based extended grapheme cluster index; resolve it through its source boundary table. */
public record GraphemeIndex(int value) {
    public static final GraphemeIndex ZERO = new GraphemeIndex(0);
    public GraphemeIndex {
        if (value < 0) { throw new IllegalArgumentException("negative grapheme index"); }
    }
    public static GraphemeIndex of(int value) { return new GraphemeIndex(value); }
}
