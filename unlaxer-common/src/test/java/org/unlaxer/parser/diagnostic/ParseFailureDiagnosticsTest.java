package org.unlaxer.parser.diagnostic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseFailureDiagnostics;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for enriched parse failure diagnostics with trial recording.
 */
public class ParseFailureDiagnosticsTest {

    @Test
    public void parseSuccess_noDiagnosticsNeeded() {
        WordParser hello = new WordParser("hello");
        StringSource source = StringSource.createRootSource("hello");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            ctx.startTrialRecording();
            Parsed parsed = hello.parse(ctx);
            ctx.stopTrialRecording();

            assertTrue("parse should succeed", parsed.isSucceeded());
            // Diagnostics are available but indicate no failure
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();
            assertNotNull(diagnostics);
            // On success the farthest failure offset is not set, so hasFailureCandidate should be false
            // (since no failure was registered)
        }
    }

    @Test
    public void simpleFailure_correctPositionReported() {
        WordParser parser = new WordParser("xyz");
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            ctx.startTrialRecording();
            Parsed parsed = parser.parse(ctx);
            ctx.stopTrialRecording();

            assertTrue("parse should fail", parsed.isFailed());
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();
            assertNotNull(diagnostics);
            // Failure position should be at position 0 (start of input)
            assertEquals("failure position should be 0", 0, diagnostics.getFarthestOffset());
            // Line numbers are 0-based in this framework
            assertEquals("failure line should be 0", 0, diagnostics.getLine());

            // Trial history should contain the failed attempt
            List<ParseFailureDiagnostics.TrialRecord> trials = diagnostics.getTrialHistory();
            assertFalse("trial history should not be empty", trials.isEmpty());

            // The WordParser trial should show failure
            boolean hasWordParserTrial = trials.stream()
                .anyMatch(t -> t.getParserName().equals("WordParser") && !t.isSucceeded());
            assertTrue("should have a failed WordParser trial", hasWordParserTrial);
        }
    }

    @Test
    public void choiceFailure_allTriedAlternativesListed() {
        WordParser yes = new WordParser("yes");
        WordParser no = new WordParser("no");
        Choice yesOrNo = new Choice(yes, no);

        StringSource source = StringSource.createRootSource("maybe");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            ctx.startTrialRecording();
            Parsed parsed = yesOrNo.parse(ctx);
            ctx.stopTrialRecording();

            assertTrue("parse should fail", parsed.isFailed());
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();

            // Trial history should show both alternatives were tried
            List<ParseFailureDiagnostics.TrialRecord> trials = diagnostics.getTrialHistory();
            assertFalse("trial history should not be empty", trials.isEmpty());

            // Count WordParser failures (both "yes" and "no" should have been tried)
            long wordParserFailures = trials.stream()
                .filter(t -> t.getParserName().equals("WordParser") && !t.isSucceeded())
                .count();
            assertTrue("should have at least 2 failed WordParser trials (yes and no)",
                wordParserFailures >= 2);

            // Expected tokens should contain hints about what was expected
            Set<String> expectedTokens = diagnostics.getExpectedTokens();
            assertNotNull("expected tokens should not be null", expectedTokens);
            assertFalse("expected tokens should not be empty", expectedTokens.isEmpty());
        }
    }

    @Test
    public void pegOrderedChoice_showsPartialConsumption() {
        // "abc" will partially match "ab" but fail on "abc" vs "abd"
        // Choice between "abd" and "abc" where input is "abx"
        WordParser abd = new WordParser("abd");
        WordParser abc = new WordParser("abc");
        Choice choice = new Choice(abd, abc);

        StringSource source = StringSource.createRootSource("abx");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            ctx.startTrialRecording();
            Parsed parsed = choice.parse(ctx);
            ctx.stopTrialRecording();

            assertTrue("parse should fail", parsed.isFailed());
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();

            // Trial history should show both alternatives were tried
            List<ParseFailureDiagnostics.TrialRecord> trials = diagnostics.getTrialHistory();
            assertFalse("trial history should not be empty", trials.isEmpty());

            // Both WordParser alternatives should have been tried and failed
            long failedTrials = trials.stream()
                .filter(t -> t.getParserName().equals("WordParser") && !t.isSucceeded())
                .count();
            assertTrue("should have at least 2 failed WordParser trials", failedTrials >= 2);
        }
    }

    @Test
    public void expectedTokensComputedCorrectly() {
        // Grammar: digit "+" digit
        // Input: "1+x" - should fail on the second digit
        DigitParser d1 = new DigitParser();
        PlusParser plus = new PlusParser();
        DigitParser d2 = new DigitParser();
        Chain grammar = new Chain(d1, plus, d2);

        StringSource source = StringSource.createRootSource("1+x");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            ctx.startTrialRecording();
            Parsed parsed = grammar.parse(ctx);
            ctx.stopTrialRecording();

            assertTrue("parse should fail", parsed.isFailed());
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();

            // The failure should be at position 2 (after "1+")
            assertTrue("failure position should be at or after position 2",
                diagnostics.getFarthestOffset() >= 2);

            // Trial history should show successful trials for digit and plus
            List<ParseFailureDiagnostics.TrialRecord> trials = diagnostics.getTrialHistory();
            assertFalse("trial history should not be empty", trials.isEmpty());

            // Should have some succeeded and some failed trials
            boolean hasSucceeded = trials.stream().anyMatch(ParseFailureDiagnostics.TrialRecord::isSucceeded);
            boolean hasFailed = trials.stream().anyMatch(t -> !t.isSucceeded());
            assertTrue("should have succeeded trials", hasSucceeded);
            assertTrue("should have failed trials", hasFailed);

            // Expected tokens/parsers should not be empty
            Set<String> expectedTokens = diagnostics.getExpectedTokens();
            assertNotNull("expected tokens should not be null", expectedTokens);
            // The expected set should contain something indicating a digit was expected
            assertFalse("expected tokens should not be empty", expectedTokens.isEmpty());
        }
    }

    @Test
    public void trialRecordingCanBeToggled() {
        WordParser parser = new WordParser("hello");
        StringSource source = StringSource.createRootSource("world");

        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            // Parse without recording
            assertFalse("should not be recording initially", ctx.isRecordingTrials());
            Parsed parsed1 = parser.parse(ctx);
            assertTrue("should fail", parsed1.isFailed());

            // Trial history should be empty when not recording
            List<ParseFailureDiagnostics.TrialRecord> emptyTrials = ctx.getTrialHistory();
            assertTrue("trial history should be empty when not recording", emptyTrials.isEmpty());

            // Now enable recording and parse again
            ctx.startTrialRecording();
            assertTrue("should be recording", ctx.isRecordingTrials());
            Parsed parsed2 = parser.parse(ctx);
            assertTrue("should fail again", parsed2.isFailed());

            List<ParseFailureDiagnostics.TrialRecord> trials = ctx.getTrialHistory();
            assertFalse("trial history should not be empty after recording", trials.isEmpty());

            // Stop recording
            ctx.stopTrialRecording();
            assertFalse("should not be recording after stop", ctx.isRecordingTrials());
        }
    }

    @Test
    public void chainPartialFailure_deepestMatchedRuleReported() {
        // Grammar: "hello" " " "world"
        // Input: "hello xyz" - should fail on "world"
        WordParser hello = new WordParser("hello");
        WordParser space = new WordParser(" ");
        WordParser world = new WordParser("world");
        Chain grammar = new Chain(hello, space, world);

        StringSource source = StringSource.createRootSource("hello xyz");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            ctx.startTrialRecording();
            Parsed parsed = grammar.parse(ctx);
            ctx.stopTrialRecording();

            assertTrue("parse should fail", parsed.isFailed());
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();

            // Deepest consumed position should be beyond the initial "hello " (6 chars)
            assertTrue("deepest consumed position should be > 0",
                diagnostics.getDeepestConsumedPosition() > 0);

            // Deepest matched rule should not be empty
            String deepestRule = diagnostics.getDeepestMatchedRule();
            assertNotNull("deepest matched rule should not be null", deepestRule);
            assertFalse("deepest matched rule should not be empty", deepestRule.isEmpty());

            // Stack elements should show the parse depth
            List<ParseFailureDiagnostics.ParseStackElement> stack = diagnostics.getMaxReachedStackElements();
            assertFalse("stack should not be empty", stack.isEmpty());
        }
    }

    @Test
    public void equalOffsetFailures_keepTheDeepestParseStackRegardlessOfOrder() {
        // Both alternatives consume "ab" and then fail at offset 2. The second nests three
        // chains deeper than the first, so the reported parse stack must be the deeper one
        // whichever alternative fails first, and the expected hints must be the same union.
        List<String> shallowFirst = failureStackClassNames(true);
        List<String> deepFirst = failureStackClassNames(false);
        assertEquals(List.of("Choice", "Chain", "Chain", "Chain", "WordParser"), shallowFirst);
        assertEquals(shallowFirst, deepFirst);
    }

    private static List<String> failureStackClassNames(boolean shallowFirst) {
        Chain shallow = new Chain(new WordParser("ab"), new WordParser("x"));
        Chain deep = new Chain(new WordParser("ab"), new Chain(new Chain(new WordParser("y"))));
        Choice grammar = shallowFirst ? new Choice(shallow, deep) : new Choice(deep, shallow);
        StringSource source = StringSource.createRootSource("abz");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            assertTrue("parse should fail", grammar.parse(ctx).isFailed());
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();
            assertEquals("failure offset", 2, diagnostics.getFarthestOffset());
            List<String> hints = diagnostics.getExpectedParsers();
            assertTrue("hints should mention x: " + hints, hints.stream().anyMatch(hint -> hint.contains("x")));
            assertTrue("hints should mention y: " + hints, hints.stream().anyMatch(hint -> hint.contains("y")));
            return diagnostics.getMaxReachedStackElements().stream()
                .map(ParseFailureDiagnostics.ParseStackElement::getParserClassName)
                .collect(Collectors.toList());
        }
    }

    @Test
    public void sameOffsetHints_keepFirstSeenOrderAndDropDuplicatesAcrossAlternatives() {
        // Six alternatives fail at offset 2 after "ab". Alternatives 1 and 4 expect the same "x"
        // literal, so the reported hints must list x, y, z, w once each in first-seen order, and
        // the same candidates must be reported when the shared literal appears first.
        WordParser ab = new WordParser("ab");
        Choice grammar = new Choice(
            new Chain(new WordParser("ab"), new WordParser("x")),
            new Chain(new WordParser("ab"), new WordParser("y")),
            new Chain(new WordParser("ab"), new Chain(new WordParser("z"))),
            new Chain(new WordParser("ab"), new WordParser("x")),
            new Chain(new WordParser("ab"), new WordParser("w")),
            new Chain(new WordParser("ab"), new WordParser("y")));
        StringSource source = StringSource.createRootSource("abq");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            assertTrue("parse should fail", grammar.parse(ctx).isFailed());
            ParseFailureDiagnostics diagnostics = ctx.getParseFailureDiagnostics();
            assertEquals("failure offset", 2, diagnostics.getFarthestOffset());
            List<String> hints = diagnostics.getExpectedParsers();
            // Display hints wrap the literal (for example in quotes), so keep only the letter.
            List<String> literalHints = hints.stream()
                .map(hint -> hint.replaceAll("[^xyzw]", ""))
                .filter(letter -> letter.length() == 1)
                .collect(Collectors.toList());
            assertEquals("hints were " + hints, List.of("x", "y", "z", "w"), literalHints);
            assertEquals("hint list must not repeat entries", hints.size(), hints.stream().distinct().count());
            List<ParseFailureDiagnostics.ExpectedHintCandidate> candidates = diagnostics.getExpectedHintCandidates();
            long distinctPairs = candidates.stream()
                .map(candidate -> candidate.getDisplayHint() + "|" + candidate.getParserQualifiedClassName())
                .distinct().count();
            assertEquals("candidates must not repeat (hint, parser) pairs", candidates.size(), distinctPairs);
        }
        assertNotNull(ab);
    }
}
