package org.unlaxer.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.TokenList;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.elementary.WordParser;

/** Regression coverage for a parser that is its own transaction listener. */
public class TransactionListenerSelfNotificationTest {

    @Test
    public void selfListenerReceivesOneCollectedRuleToken() {
        SelfListeningChain parser = new SelfListeningChain(
            new WordParser("ab"), new WordParser("cd"));

        try (ParseContext ctx = new ParseContext(
                StringSource.createRootSource("abcd"),
                CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);

            assertTrue(parsed.isSucceeded());
            assertEquals("self listener must be notified exactly once", 1, parser.commitCount);
            assertEquals("commit payload must contain the collected rule token", 1, parser.committed.size());
            assertSame("collected token must belong to the rule parser",
                parser, parser.committed.get(0).getParser());
            assertEquals("abcd", parser.committed.get(0).source.sourceAsString());
        }
    }

    private static final class SelfListeningChain extends Chain implements TransactionListener {
        private static final long serialVersionUID = 1L;

        private int commitCount;
        private TokenList committed = TokenList.of();

        private SelfListeningChain(Parser... children) {
            super(children);
        }

        @Override public void setLevel(OutputLevel level) {}
        @Override public void onOpen(ParseContext parseContext) {}
        @Override public void onBegin(ParseContext parseContext, Parser parser) {}

        @Override
        public void onCommit(ParseContext parseContext, Parser parser, TokenList tokens) {
            commitCount++;
            committed = tokens;
        }

        @Override public void onClose(ParseContext parseContext) {}
    }
}
