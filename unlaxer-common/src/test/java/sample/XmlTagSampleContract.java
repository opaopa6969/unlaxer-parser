package sample;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.CaptureScope;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.NamedCapture;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.elementary.ReplayParser;
import org.unlaxer.parser.elementary.SpaceDelimitor;
import org.unlaxer.parser.elementary.WordParser;

import sample.Usage002_01_SimpleXmlTag.EmptyElementTag;
import sample.Usage002_01_SimpleXmlTag.TagIdentifier;

/** Shared XML tag fixture for the Usage002 class-factory samples; run by {@link XmlTagSampleTest}. */
public class XmlTagSampleContract {
    static final Map<String, Supplier<Parser>> RECIPES = Map.of(
        "02", Usage002_02_SimpleXmlTagOpenAndClose.NoMixedContentsXmlElements::new,
        "03", Usage002_03_SimpleXmlTagPaired.NoMixedContentsXmlElementsPaired::new,
        "04", Usage002_04_SimpleXmlTagPaired_alternative.NoMixedContentsXmlElementsPaired::new,
        "scoped", ScopedXmlElement::new);

    @Test public void xmlTagSamplesMatchSharedPrefixContract() throws Exception {
        assertEquals(57, check("xml-tags.tsv", Map.of("02", 1, "03", 2, "04", 2, "scoped", 3)));
    }

    /** The java column records MatchedTokenParser candidate order and cache lifetime (#852). */
    @Test public void pairedSamplesKeepJavaCandidateHistory() throws Exception {
        assertEquals(4, check("xml-tags-history.tsv", Map.of("03", 1, "04", 1, "scoped", 3)));
    }

    static int check(String fixture, Map<String, Integer> columns) throws Exception {
        int cases = 0;
        for (String line : Files.readAllLines(Path.of("..", "conformance", "samples", fixture))) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            String[] row = line.split("\t", -1);
            assertEquals(line, 5, row.length);
            cases++;
            String input = unescape(row[0]);
            for (Map.Entry<String, Integer> column : columns.entrySet()) {
                String expected = row[column.getValue()];
                boolean success = false == expected.equals("-");
                int consumed = success ? Integer.parseInt(expected) : 0;
                // One parser instance per row also checks that class-factory singletons do not leak context state.
                Parser parser = RECIPES.get(column.getKey()).get();
                for (Memoization memoization : Memoization.values()) {
                    for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                        String label = column.getKey() + " " + memoization + " " + diagnostics + ": " + line;
                        ParseOptions options = ParseOptions.withMemoization(memoization).withDiagnostics(diagnostics);
                        try (ParseContext context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                            Parsed parsed = parser.parse(context);
                            assertEquals(label, success, parsed.isSucceeded());
                            assertEquals(label, consumed, context.getConsumedPosition().value());
                            assertEquals(label, consumed, context.getMatchedPosition().value());
                            if (success) {
                                assertEquals(label, input.substring(0, input.offsetByCodePoints(0, consumed)),
                                    parsed.getConsumed().source.sourceAsString());
                            }
                        }
                    }
                }
            }
        }
        return cases;
    }

    static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character != '\\') {
                out.append(character);
                continue;
            }
            index++;
            char next = text.charAt(index);
            out.append(switch (next) {
                case 'r' -> '\r';
                case 'n' -> '\n';
                case 't' -> '\t';
                case '\\' -> '\\';
                default -> throw new IllegalArgumentException(text);
            });
        }
        return out.toString();
    }

    /** Owned-capture recipe: the close tag replays the innermost open name. Not a sample class. */
    public static class ScopedXmlElement extends LazyChoice {
        private static final long serialVersionUID = 1L;

        @Override public Parsers getLazyParsers() {
            return new Parsers(
                new CaptureScope(new Chain(
                    new WordParser("<"), new SpaceDelimitor(), new NamedCapture("tag", new TagIdentifier()),
                    new SpaceDelimitor(), new WordParser(">"),
                    new Optional(ScopedXmlElement.class),
                    new WordParser("<"), new SpaceDelimitor(), new WordParser("/"), new SpaceDelimitor(),
                    new ReplayParser("tag"), new SpaceDelimitor(), new WordParser(">"))),
                new EmptyElementTag());
        }
    }
}
