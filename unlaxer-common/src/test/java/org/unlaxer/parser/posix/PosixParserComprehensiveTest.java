package org.unlaxer.parser.posix;

import org.junit.Test;
import org.unlaxer.ParserTestBase;

/**
 * Comprehensive tests for all POSIX character class parsers.
 * Each parser matches a single character.
 */
public class PosixParserComprehensiveTest extends ParserTestBase {

  // --- DigitParser: 0-9 ---

  @Test
  public void testDigitMatches() {
    DigitParser parser = new DigitParser();
    for (char c = '0'; c <= '9'; c++) {
      testAllMatch(parser, String.valueOf(c));
    }
  }

  @Test
  public void testDigitRejects() {
    DigitParser parser = new DigitParser();
    testUnMatch(parser, "a");
    testUnMatch(parser, "Z");
    testUnMatch(parser, " ");
    testUnMatch(parser, "!");
  }

  // --- AlphabetParser: A-Za-z ---

  @Test
  public void testAlphabetMatchesLower() {
    AlphabetParser parser = new AlphabetParser();
    testAllMatch(parser, "a");
    testAllMatch(parser, "m");
    testAllMatch(parser, "z");
  }

  @Test
  public void testAlphabetMatchesUpper() {
    AlphabetParser parser = new AlphabetParser();
    testAllMatch(parser, "A");
    testAllMatch(parser, "M");
    testAllMatch(parser, "Z");
  }

  @Test
  public void testAlphabetRejects() {
    AlphabetParser parser = new AlphabetParser();
    testUnMatch(parser, "0");
    testUnMatch(parser, " ");
    testUnMatch(parser, "_");
    testUnMatch(parser, "!");
  }

  // --- AlphabetNumericParser: A-Za-z0-9 ---

  @Test
  public void testAlphaNumMatches() {
    AlphabetNumericParser parser = new AlphabetNumericParser();
    testAllMatch(parser, "a");
    testAllMatch(parser, "Z");
    testAllMatch(parser, "5");
  }

  @Test
  public void testAlphaNumRejects() {
    AlphabetNumericParser parser = new AlphabetNumericParser();
    testUnMatch(parser, "_");
    testUnMatch(parser, " ");
    testUnMatch(parser, "!");
  }

  // --- UpperParser: A-Z ---

  @Test
  public void testUpperMatches() {
    UpperParser parser = new UpperParser();
    testAllMatch(parser, "A");
    testAllMatch(parser, "M");
    testAllMatch(parser, "Z");
  }

  @Test
  public void testUpperRejects() {
    UpperParser parser = new UpperParser();
    testUnMatch(parser, "a");
    testUnMatch(parser, "z");
    testUnMatch(parser, "0");
  }

  // --- LowerParser: a-z ---

  @Test
  public void testLowerMatches() {
    LowerParser parser = new LowerParser();
    testAllMatch(parser, "a");
    testAllMatch(parser, "m");
    testAllMatch(parser, "z");
  }

  @Test
  public void testLowerRejects() {
    LowerParser parser = new LowerParser();
    testUnMatch(parser, "A");
    testUnMatch(parser, "Z");
    testUnMatch(parser, "0");
  }

  // --- SpaceParser: space, tab, newlines ---

  @Test
  public void testSpaceMatches() {
    SpaceParser parser = new SpaceParser();
    testAllMatch(parser, " ");
    testAllMatch(parser, "\t");
    testAllMatch(parser, "\n");
    testAllMatch(parser, "\r");
  }

  @Test
  public void testSpaceRejects() {
    SpaceParser parser = new SpaceParser();
    testUnMatch(parser, "a");
    testUnMatch(parser, "0");
    testUnMatch(parser, "!");
  }

  // --- BlankParser: space, tab ---

  @Test
  public void testBlankMatches() {
    BlankParser parser = new BlankParser();
    testAllMatch(parser, " ");
    testAllMatch(parser, "\t");
  }

  @Test
  public void testBlankRejects() {
    BlankParser parser = new BlankParser();
    testUnMatch(parser, "\n");
    testUnMatch(parser, "a");
    testUnMatch(parser, "0");
  }

  // --- PunctuationParser ---

  @Test
  public void testPunctuationMatches() {
    PunctuationParser parser = new PunctuationParser();
    testAllMatch(parser, "!");
    testAllMatch(parser, "@");
    testAllMatch(parser, "#");
    testAllMatch(parser, ".");
    testAllMatch(parser, ",");
    testAllMatch(parser, ";");
    testAllMatch(parser, ":");
    testAllMatch(parser, "?");
  }

  @Test
  public void testPunctuationRejects() {
    PunctuationParser parser = new PunctuationParser();
    testUnMatch(parser, "a");
    testUnMatch(parser, "0");
    testUnMatch(parser, " ");
  }

  // --- GraphParser: printable chars except space (33-126) ---

  @Test
  public void testGraphMatches() {
    GraphParser parser = new GraphParser();
    testAllMatch(parser, "!");  // 33
    testAllMatch(parser, "A");  // 65
    testAllMatch(parser, "~");  // 126
    testAllMatch(parser, "0");
    testAllMatch(parser, "a");
  }

  @Test
  public void testGraphRejects() {
    GraphParser parser = new GraphParser();
    testUnMatch(parser, " ");  // 32 is not graph
  }

  // --- PrintParser: printable chars including space (32-126) ---

  @Test
  public void testPrintMatches() {
    PrintParser parser = new PrintParser();
    testAllMatch(parser, " ");  // 32
    testAllMatch(parser, "!");  // 33
    testAllMatch(parser, "A");
    testAllMatch(parser, "~");  // 126
  }

  @Test
  public void testPrintRejectsControl() {
    PrintParser parser = new PrintParser();
    testUnMatch(parser, "\t");  // tab = 9
    testUnMatch(parser, "\n");  // LF = 10
  }

  // --- ControlParser: 0-31, 127 ---

  @Test
  public void testControlMatches() {
    ControlParser parser = new ControlParser();
    testAllMatch(parser, "\0");  // null
    testAllMatch(parser, "\t");  // tab
    testAllMatch(parser, "\n");  // LF
    testAllMatch(parser, String.valueOf((char)127)); // DEL
  }

  @Test
  public void testControlRejects() {
    ControlParser parser = new ControlParser();
    testUnMatch(parser, " ");   // 32
    testUnMatch(parser, "A");
    testUnMatch(parser, "0");
  }

  // --- XDigitParser: 0-9, A-F, a-f ---

  @Test
  public void testXDigitMatches() {
    XDigitParser parser = new XDigitParser();
    testAllMatch(parser, "0");
    testAllMatch(parser, "9");
    testAllMatch(parser, "A");
    testAllMatch(parser, "F");
    testAllMatch(parser, "a");
    testAllMatch(parser, "f");
  }

  @Test
  public void testXDigitRejects() {
    XDigitParser parser = new XDigitParser();
    testUnMatch(parser, "g");
    testUnMatch(parser, "G");
    testUnMatch(parser, "z");
    testUnMatch(parser, " ");
  }

  // --- AsciiParser: 0-127 ---

  @Test
  public void testAsciiMatches() {
    AsciiParser parser = new AsciiParser();
    testAllMatch(parser, "\0");  // 0
    testAllMatch(parser, " ");   // 32
    testAllMatch(parser, "A");   // 65
    testAllMatch(parser, "~");   // 126
    testAllMatch(parser, String.valueOf((char)127));
  }

  // --- CommaParser ---

  @Test
  public void testCommaMatches() {
    CommaParser parser = new CommaParser();
    testAllMatch(parser, ",");
  }

  @Test
  public void testCommaRejects() {
    CommaParser parser = new CommaParser();
    testUnMatch(parser, ".");
    testUnMatch(parser, ";");
    testUnMatch(parser, "a");
  }

  // --- ColonParser ---

  @Test
  public void testColonMatches() {
    ColonParser parser = new ColonParser();
    testAllMatch(parser, ":");
  }

  @Test
  public void testColonRejects() {
    ColonParser parser = new ColonParser();
    testUnMatch(parser, ";");
    testUnMatch(parser, ",");
    testUnMatch(parser, "a");
  }

  // --- SemiColonParser ---

  @Test
  public void testSemiColonMatches() {
    SemiColonParser parser = new SemiColonParser();
    testAllMatch(parser, ";");
  }

  @Test
  public void testSemiColonRejects() {
    SemiColonParser parser = new SemiColonParser();
    testUnMatch(parser, ":");
    testUnMatch(parser, ",");
    testUnMatch(parser, "a");
  }

  // --- DotParser ---

  @Test
  public void testDotMatches() {
    DotParser parser = new DotParser();
    testAllMatch(parser, ".");
  }

  @Test
  public void testDotRejects() {
    DotParser parser = new DotParser();
    testUnMatch(parser, ",");
    testUnMatch(parser, ":");
    testUnMatch(parser, "a");
  }

  // --- HashParser ---

  @Test
  public void testHashMatches() {
    HashParser parser = new HashParser();
    testAllMatch(parser, "#");
  }

  @Test
  public void testHashRejects() {
    HashParser parser = new HashParser();
    testUnMatch(parser, "$");
    testUnMatch(parser, "%");
    testUnMatch(parser, "a");
  }

  // --- WordParser (POSIX [:word:]): A-Za-z0-9_ ---

  @Test
  public void testWordMatches() {
    WordParser parser = new WordParser();
    testAllMatch(parser, "a");
    testAllMatch(parser, "Z");
    testAllMatch(parser, "5");
    testAllMatch(parser, "_");
  }

  @Test
  public void testWordRejects() {
    WordParser parser = new WordParser();
    testUnMatch(parser, " ");
    testUnMatch(parser, "!");
    testUnMatch(parser, "-");
    testUnMatch(parser, ".");
  }

  // --- AlphabetUnderScoreParser ---

  @Test
  public void testAlphaUnderscoreMatches() {
    AlphabetUnderScoreParser parser = new AlphabetUnderScoreParser();
    testAllMatch(parser, "a");
    testAllMatch(parser, "Z");
    testAllMatch(parser, "_");
  }

  @Test
  public void testAlphaUnderscoreRejects() {
    AlphabetUnderScoreParser parser = new AlphabetUnderScoreParser();
    testUnMatch(parser, "0");
    testUnMatch(parser, " ");
    testUnMatch(parser, "!");
  }

  // --- AlphabetNumericUnderScoreParser ---

  @Test
  public void testAlphaNumUnderscoreMatches() {
    AlphabetNumericUnderScoreParser parser = new AlphabetNumericUnderScoreParser();
    testAllMatch(parser, "a");
    testAllMatch(parser, "Z");
    testAllMatch(parser, "5");
    testAllMatch(parser, "_");
  }

  @Test
  public void testAlphaNumUnderscoreRejects() {
    AlphabetNumericUnderScoreParser parser = new AlphabetNumericUnderScoreParser();
    testUnMatch(parser, " ");
    testUnMatch(parser, "!");
    testUnMatch(parser, "-");
  }
}
