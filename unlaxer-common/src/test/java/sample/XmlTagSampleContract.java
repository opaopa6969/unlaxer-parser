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
import org.unlaxer.parser.combinator.Choice;
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
    static final Class<Usage002_03_SimpleXmlTagPaired.NoMixedContentsXmlElementsPaired> SHARED_03 =
        Usage002_03_SimpleXmlTagPaired.NoMixedContentsXmlElementsPaired.class;
    static final Class<Usage002_04_SimpleXmlTagPaired_alternative.NoMixedContentsXmlElementsPaired> SHARED_04 =
        Usage002_04_SimpleXmlTagPaired_alternative.NoMixedContentsXmlElementsPaired.class;
    static final Map<String, Supplier<Parser>> RECIPES = Map.ofEntries(
        Map.entry("02", Usage002_02_SimpleXmlTagOpenAndClose.NoMixedContentsXmlElements::new),
        Map.entry("03", Usage002_03_SimpleXmlTagPaired.NoMixedContentsXmlElementsPaired::new),
        Map.entry("04", Usage002_04_SimpleXmlTagPaired_alternative.NoMixedContentsXmlElementsPaired::new),
        Map.entry("scoped", ScopedXmlElement::new),
        // The class-factory instance that every nested level of 03/04 uses.
        Map.entry("shared03", () -> Parser.get(SHARED_03)),
        Map.entry("shared04", () -> Parser.get(SHARED_04)),
        Map.entry("retry03", () -> retry(SHARED_03)),
        Map.entry("retry04", () -> retry(SHARED_04)),
        Map.entry("second03", () -> second(SHARED_03)),
        Map.entry("second04", () -> second(SHARED_04)),
        Map.entry("twice03", () -> twice(SHARED_03)),
        Map.entry("twice04", () -> twice(SHARED_04)));

    @Test public void xmlTagSamplesMatchSharedPrefixContract() throws Exception {
        assertEquals(70, check("xml-tags.tsv", 5, Map.of("02", 1, "03", 2, "04", 2, "scoped", 3)));
    }

    /** Every close reads the current token stack: no names of earlier closes, uses or rolled-back alternatives. */
    @Test public void pairedCloseUsesCurrentCandidates() throws Exception {
        assertEquals(15, check("xml-tags-current-candidates.tsv", 6, Map.of(
            "shared03", 1, "shared04", 1, "retry03", 2, "retry04", 2, "second03", 3, "second04", 3,
            "twice03", 4, "twice04", 4)));
    }

    static Parser retry(Class<? extends Parser> shared) {
        return new Choice(new Chain(Parser.get(shared), new WordParser("!")), second(shared));
    }

    static Parser second(Class<? extends Parser> shared) {
        return new Chain(new WordParser("<a><ab/></a>"), Parser.get(shared));
    }

    static Parser twice(Class<? extends Parser> shared) {
        return new Chain(Parser.get(shared), Parser.get(shared));
    }

    static int check(String fixture, int width, Map<String, Integer> columns) throws Exception {
        int cases = 0;
        for (String line : Files.readAllLines(Path.of("..", "conformance", "samples", fixture))) {
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            String[] row = line.split("\t", -1);
            assertEquals(line, width, row.length);
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
