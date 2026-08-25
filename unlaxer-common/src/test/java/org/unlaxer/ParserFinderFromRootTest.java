package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.elementary.WordParser;

public class ParserFinderFromRootTest {

  @Test
  public void searchingFromRootDoesNotWriteToStandardOutput() {
    WordParser first = new WordParser("first");
    WordParser second = new WordParser("second");
    Chain root = new Chain(first, second);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream originalOutput = System.out;
    List<Parser> matches;
    Optional<Parser> firstMatch;
    try {
      System.setOut(new PrintStream(output, true));
      matches = root.findFromRoot(parser -> parser instanceof WordParser)
          .collect(Collectors.toList());
      firstMatch = root.findFirstFromRoot(parser -> parser == second);
    } finally {
      System.setOut(originalOutput);
    }

    assertEquals(2, matches.size());
    assertSame(first, matches.get(0));
    assertSame(second, matches.get(1));
    assertTrue(firstMatch.isPresent());
    assertSame(second, firstMatch.get());
    assertEquals("", new String(output.toByteArray(), StandardCharsets.UTF_8));
  }
}
