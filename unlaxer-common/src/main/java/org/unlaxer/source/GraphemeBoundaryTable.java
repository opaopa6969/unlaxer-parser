package org.unlaxer.source;

import static org.unlaxer.source.UnicodeGraphemeData.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import org.unlaxer.CodePointIndex;
import org.unlaxer.GraphemeIndex;
import org.unlaxer.GraphemeLength;

/**
 * Immutable Unicode 17.0.0 extended grapheme boundaries for one source snapshot.
 * Positions are absolute offsets, not line columns or rendered widths. No normalization.
 * Construction scans scalars once; conversion uses binary search over cluster boundaries.
 */
public final class GraphemeBoundaryTable {
    public static final String UNICODE_VERSION = "17.0.0";
    private final DocumentSnapshot snapshot;
    private final int[] codePoints;
    private final int[] utf16;
    private final int[] utf8;

    public GraphemeBoundaryTable(DocumentSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
        var points = new ArrayList<Integer>();
        var units = new ArrayList<Integer>();
        var bytes = new ArrayList<Integer>();
        points.add(0); units.add(0); bytes.add(0);
        String text = snapshot.text();
        int point = 0, byteOffset = 0, previous = GCB_OTHER, regionalCount = 0, conjunct = INCB_NONE;
        boolean pictographicExtend = false, pictographicZwj = false;
        for (int unit = 0; unit < text.length(); point++) {
            int scalar = text.codePointAt(unit);
            int property = UnicodeGraphemeData.gcb(scalar);
            int indic = UnicodeGraphemeData.incb(scalar);
            boolean pictographic = UnicodeGraphemeData.pictographic(scalar);
            if (point > 0 && breaks(previous, property, regionalCount, conjunct, indic,
                    pictographicZwj, pictographic)) {
                points.add(point); units.add(unit); bytes.add(byteOffset);
            }
            pictographicZwj = property == GCB_ZWJ && pictographicExtend;
            pictographicExtend = pictographic || (property == GCB_EXTEND && pictographicExtend);
            if (indic == INCB_CONSONANT) { conjunct = 1; }
            else if (indic == INCB_LINKER && conjunct != 0) { conjunct = 2; }
            else if (indic != INCB_EXTEND) { conjunct = 0; }
            regionalCount = property == GCB_REGIONAL_INDICATOR ? regionalCount + 1 : 0;
            previous = property;
            unit += Character.charCount(scalar);
            byteOffset = Math.addExact(byteOffset, scalar <= 0x7f ? 1 : scalar <= 0x7ff ? 2 : scalar <= 0xffff ? 3 : 4);
        }
        if (point != 0) { points.add(point); units.add(text.length()); bytes.add(byteOffset); }
        codePoints = points.stream().mapToInt(Integer::intValue).toArray();
        utf16 = units.stream().mapToInt(Integer::intValue).toArray();
        utf8 = bytes.stream().mapToInt(Integer::intValue).toArray();
    }

    // UAX #29 revision 47, GB3–GB999, in normative rule order.
    private static boolean breaks(int left, int right, int regionalCount, int conjunct, int indic,
            boolean pictographicZwj, boolean pictographic) {
        if (left == GCB_CR && right == GCB_LF) { return false; } // GB3
        if (control(left) || control(right)) { return true; } // GB4/5
        if (left == GCB_L && (right == GCB_L || right == GCB_V || right == GCB_LV || right == GCB_LVT)) { return false; } // GB6
        if ((left == GCB_LV || left == GCB_V) && (right == GCB_V || right == GCB_T)) { return false; } // GB7
        if ((left == GCB_LVT || left == GCB_T) && right == GCB_T) { return false; } // GB8
        if (right == GCB_EXTEND || right == GCB_ZWJ || right == GCB_SPACINGMARK || left == GCB_PREPEND) { return false; } // GB9/9a/9b
        if (conjunct == 2 && indic == INCB_CONSONANT) { return false; } // GB9c
        if (pictographicZwj && pictographic) { return false; } // GB11
        return left != GCB_REGIONAL_INDICATOR || right != GCB_REGIONAL_INDICATOR || regionalCount % 2 == 0; // GB12/13, GB999
    }

    private static boolean control(int property) { return property == GCB_CR || property == GCB_LF || property == GCB_CONTROL; }

    public DocumentSnapshot snapshot() { return snapshot; }
    public void requireSnapshot(DocumentSnapshot source) {
        if (false == snapshot.equals(source)) { throw new IllegalArgumentException("stale grapheme boundary table"); }
    }
    public GraphemeLength length() { return new GraphemeLength(codePoints.length - 1); }
    public CodePointIndex codePoint(GraphemeIndex index) { return new CodePointIndex(at(codePoints, index)); }
    public int utf16(GraphemeIndex index) { return at(utf16, index); }
    public int utf8(GraphemeIndex index) { return at(utf8, index); }
    public GraphemeIndex fromCodePoint(CodePointIndex point) { return strict(codePoints, point.value()); }
    public GraphemeIndex fromUtf16(int offset) { return strict(utf16, offset); }
    public GraphemeIndex fromUtf8(int offset) { return strict(utf8, offset); }
    /** Greatest boundary at or before the supplied scalar offset. */
    public GraphemeIndex floorCodePoint(CodePointIndex point) { return rounded(codePoints, point.value(), false); }
    /** Least boundary at or after the supplied scalar offset. */
    public GraphemeIndex ceilingCodePoint(CodePointIndex point) { return rounded(codePoints, point.value(), true); }
    public GraphemeIndex floorUtf16(int offset) { return rounded(utf16, offset, false); }
    public GraphemeIndex ceilingUtf16(int offset) { return rounded(utf16, offset, true); }
    public GraphemeIndex floorUtf8(int offset) { return rounded(utf8, offset, false); }
    public GraphemeIndex ceilingUtf8(int offset) { return rounded(utf8, offset, true); }
    /** Next cluster's end in CP units; -1 at EOF or a position inside a cluster. */
    public int nextCodePoint(int point) {
        int index = Arrays.binarySearch(codePoints, point);
        return index < 0 || index + 1 == codePoints.length ? -1 : codePoints[index + 1];
    }
    private static int at(int[] offsets, GraphemeIndex index) {
        if (index.value() >= offsets.length) { throw new IllegalArgumentException("outside grapheme table"); }
        return offsets[index.value()];
    }
    private static GraphemeIndex strict(int[] offsets, int offset) {
        int index = Arrays.binarySearch(offsets, offset);
        if (index < 0) { throw new IllegalArgumentException("not a grapheme boundary"); }
        return new GraphemeIndex(index);
    }
    private static GraphemeIndex rounded(int[] offsets, int offset, boolean forward) {
        if (offset < 0 || offset > offsets[offsets.length - 1]) { throw new IllegalArgumentException("outside grapheme table"); }
        int index = Arrays.binarySearch(offsets, offset);
        return new GraphemeIndex(index >= 0 ? index : -index - (forward ? 1 : 2));
    }
}
