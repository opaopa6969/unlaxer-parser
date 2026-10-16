package org.unlaxer;

import java.util.Objects;

/** Immutable captured text. Its logical extent is not necessarily an input slice. */
public record CapturedValue(String text, Provenance provenance, int start, int end) {
    public enum Provenance { INPUT, PROJECTED, GENERATED }

    public CapturedValue {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(provenance, "provenance");
        if (start < 0 || end < start
                || provenance == Provenance.GENERATED && start != end
                || provenance != Provenance.GENERATED && end - start != text.codePointCount(0, text.length())) {
            throw new IllegalArgumentException("invalid capture extent");
        }
    }

    /** Classify a source without materializing or copying its text. */
    public static Provenance provenanceOf(Source source, Source input) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(input, "input");
        if (source.sourceKind().isDetached() || source.root() != input.root()) {
            return Provenance.GENERATED;
        }
        boolean slice = source instanceof StringSource value && input instanceof StringSource root
                && value.sharesCodePointArrayWith(root);
        return slice ? Provenance.INPUT : Provenance.PROJECTED;
    }

    /** Snapshot actual text; never reconstruct projected or generated text from input coordinates. */
    public static CapturedValue from(Source source, Source input, int anchor) {
        Provenance provenance = provenanceOf(source, input);
        String text = source.sourceAsString();
        if (provenance == Provenance.GENERATED) {
            return new CapturedValue(text, provenance, anchor, anchor);
        }
        int start = source.offsetFromRoot().value() - input.offsetFromRoot().value();
        int end = Math.addExact(start, source.codePointLength().value());
        return new CapturedValue(text, provenance, start, end);
    }
}
