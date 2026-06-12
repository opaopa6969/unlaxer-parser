package org.unlaxer.parser.elementary;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.ParserTestBase;

/**
 * Tests for supplementary (outside BMP) code point handling in
 * SingleCharacterParser (issue #33).
 */
public class SingleCharacterParserCodePointTest extends ParserTestBase {

    static class LetterAParser extends SingleCharacterParser {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return target == 'a';
        }
    }

    static class SushiParser extends SingleCharacterParser {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return false;
        }

        @Override
        public boolean isMatch(int codePoint) {
            return codePoint == 0x1F363; // 🍣
        }
    }

    @Test
    public void bmpCharacterStillMatches() {
        testAllMatch(new LetterAParser(), "a");
    }

    @Test
    public void supplementaryCharacterDoesNotMatchBmpParser() {
        testUnMatch(new LetterAParser(), "🍣");
    }

    @Test
    public void codePointOverrideMatchesSupplementaryCharacter() {
        testAllMatch(new SushiParser(), "🍣");
    }

    @Test
    public void wildCardMatchesSupplementaryCharacter() {
        testAllMatch(new WildCardCharacterParser(), "🍣");
    }

    @Test
    public void defaultCodePointDelegationGatesOnBmp() {
        LetterAParser parser = new LetterAParser();
        assertTrue(parser.isMatch((int) 'a'));
        assertFalse(parser.isMatch(0x1F363));
    }
}
