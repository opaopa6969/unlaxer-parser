package org.unlaxer.parser.elementary;

import java.util.function.Consumer;
import org.unlaxer.Name;
import org.unlaxer.Source;
import org.unlaxer.util.Slicer;

/**
 * Locale-independent Unicode 17.0.0 simple case-insensitive word matching.
 * Each code point is compared using Simple_Lowercase_Mapping(Simple_Uppercase_Mapping(cp)).
 * The source is preserved, without normalization or multi-code-point case folding.
 * Empty words fail to parse, as for {@link WordParser}. Unpaired UTF-16 surrogates never match.
 * The legacy {@link IgnoreCaseWordParser} retains its JDK-dependent behavior.
 */
public class UnicodeIgnoreCaseWordParser extends WordParser {
    private static final long serialVersionUID = 1L;
    public static final String UNICODE_VERSION = "17.0.0";

    public UnicodeIgnoreCaseWordParser(String word) { super(word, true); }
    public UnicodeIgnoreCaseWordParser(Name name, String word) { super(name, word, true); }
    public UnicodeIgnoreCaseWordParser(Source word) { super(word, true); }
    public UnicodeIgnoreCaseWordParser(Name name, Source word) { super(name, word, true); }

    @Override
    boolean equals(Source target, Source candidate) {
        int length = target.codePointLength().value();
        if (length != candidate.codePointLength().value()) { return false; }
        for (int index = 0; index < length; index++) {
            int left = target.codePointValueAt(index);
            int right = candidate.codePointValueAt(index);
            if (left >= 0xd800 && left <= 0xdfff || right >= 0xd800 && right <= 0xdfff
                    || UnicodeCaseData.key(left) != UnicodeCaseData.key(right)) { return false; }
        }
        return true;
    }

    @Override
    public UnicodeIgnoreCaseWordParser slice(Consumer<Slicer> effector) {
        Slicer slicer = new Slicer(word);
        effector.accept(slicer);
        return new UnicodeIgnoreCaseWordParser(slicer.get());
    }
    @Override
    public UnicodeIgnoreCaseWordParser slice(BeginSpecifier begin, EndSpecifier end) {
        return slice(begin, end, false);
    }
    @Override
    public UnicodeIgnoreCaseWordParser slice(BeginSpecifier begin, EndSpecifier end, boolean reverse) {
        return new UnicodeIgnoreCaseWordParser(new Slicer(word).begin(begin).end(end).reverse(reverse));
    }
    @Override
    public UnicodeIgnoreCaseWordParser slice(RangeSpecifier range) { return slice(range, false); }
    @Override
    public UnicodeIgnoreCaseWordParser slice(RangeSpecifier range, boolean reverse) {
        return new UnicodeIgnoreCaseWordParser(new Slicer(word).range(range).reverse(reverse));
    }
    @Override
    public UnicodeIgnoreCaseWordParser effect(WordEffector effector) {
        return new UnicodeIgnoreCaseWordParser(effector.apply(word));
    }
}
