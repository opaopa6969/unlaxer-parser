package sample;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;

/** Run the reflection-based documentation example in the ordinary test suite. */
public class NamedConcreteSampleTest {
    @Test public void namedConcreteSampleMatchesSharedPrefixContract() throws Exception {
        var parser = new Usage001_createParserAndParseWithNamedConcrete.SimpleExpression();
        int cases = 0;
        for (String line : Files.readAllLines(Path.of("..", "conformance", "samples", "named-concrete.tsv"))) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String[] row = line.split("\t", -1);
            assertEquals(4, row.length);
            cases++;
            for (var memo : Memoization.values()) for (var diagnostic : ParseOptions.Diagnostics.values()) {
                try (var context = ParseContext.withOptions(StringSource.createRootSource(row[0]),
                        ParseOptions.withMemoization(memo).withDiagnostics(diagnostic))) {
                    var parsed = parser.parse(context);
                    assertEquals(line, Boolean.parseBoolean(row[1]), parsed.isSucceeded());
                    assertEquals(line, Integer.parseInt(row[2]), context.getConsumedPosition().value());
                    assertEquals(line, Integer.parseInt(row[3]), context.getMatchedPosition().value());
                    if (parsed.isSucceeded()) assertEquals(row[0].substring(0, Integer.parseInt(row[2])),
                        parsed.getConsumed().source.sourceAsString());
                }
            }
        }
        assertEquals(8, cases);
    }
}
