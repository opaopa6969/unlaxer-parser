package exceptionprobe;

import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.combinator.NamedCapture;
import org.unlaxer.parser.elementary.WordParser;

import java.util.*;

public final class JavaProbe {
    public static int run(String input, int[] failures, int end) throws Exception {
        int observed = 0;
        var parser = ExceptionProbeParsers.getRootParser();
        for (int failCP : failures)
            for (var memo : Memoization.values())
                for (var diagnostic : ParseOptions.Diagnostics.values())
                    for (boolean manual : List.of(false, true))
                        for (boolean abandoned : List.of(false, true)) {
                            try (var c =
                                    ParseContext.withOptions(
                                            StringSource.createRootSource(input),
                                            ParseOptions.withMemoization(memo)
                                                    .withDiagnostics(diagnostic))) {
                                CallbackWord.failAt = -1;
                                CallbackWord.abandoned = abandoned;
                                CallbackWord.registered = new int[] {7};
                                int[] registered = CallbackWord.registered;
                                c.registerTransactionalState(
                                        () -> {
                                            int saved = registered[0];
                                            return () -> registered[0] = saved;
                                        });
                                CallbackWord.metadata = new PortableTokenMetadata(c);
                                var old =
                                        new NamedCapture("word", new WordParser("p"))
                                                .parse(c)
                                                .getRootToken();
                                var oldId = CallbackWord.metadata.register(old);
                                CallbackWord.oldId = oldId;
                                CallbackWord.metadata.putExtra(oldId, "label", "before");
                                var retained = CallbackWord.metadata.snapshot();
                                CallbackWord.failure =
                                        new IllegalStateException("generated callback");
                                CallbackWord.failAt = failCP;
                                CallbackWord.freshId = null;
                                if (manual) c.begin(parser);
                                var before = List.copyOf(c.getCurrent().getTokens());
                                try {
                                    parser.parse(c);
                                    throw new AssertionError("callback accepted at " + failCP);
                                } catch (RuntimeException error) {
                                    if (error != CallbackWord.failure)
                                        throw new AssertionError("exception identity", error);
                                }
                                check(
                                        c.position() == 1 && c.getMatchedPosition().value() == 1,
                                        "rollback cursors");
                                check(
                                        c.getTokenStack().size() == (manual ? 2 : 1),
                                        "rollback frames");
                                check(before.equals(c.getCurrent().getTokens()), "rollback CST");
                                check(registered[0] == 7, "rollback registered");
                                check(
                                        c.captured("word").orElseThrow().text().equals("p"),
                                        "rollback capture");
                                check(
                                        CallbackWord.metadata
                                                .info(oldId)
                                                .orElseThrow()
                                                .extra()
                                                .get("label")
                                                .equals("before"),
                                        "rollback metadata");
                                check(
                                        CallbackWord.freshId != null
                                                && CallbackWord.metadata
                                                        .info(CallbackWord.freshId)
                                                        .isEmpty(),
                                        "rollback fresh ID");
                                check(
                                        CallbackWord.metadata
                                                .info(oldId)
                                                .orElseThrow()
                                                .related()
                                                .isEmpty(),
                                        "rollback related");
                                CallbackWord.failAt = -1;
                                check(parser.parse(c).isSucceeded(), "retry");
                                check(c.position() == end, "retry cursor");
                                check(
                                        c.captured("word").orElseThrow().text().equals("p"),
                                        "scope restored after success");
                                var root =
                                        c.getCurrent().getTokens().stream()
                                                .filter(t -> t.parser == parser)
                                                .findFirst()
                                                .orElseThrow();
                                var mapped = ExceptionProbeMapper.mapParsedTokenWithSourceMap(root);
                                var value = (ExceptionProbeAST.Value) mapped.ast();
                                check(
                                        value.items().equals(__EXPECTED_ITEMS__),
                                        "retry AST values " + value.items());
                                check(
                                        Arrays.equals(
                                                mapped.sourceSpanOf(value).orElseThrow(),
                                                new int[] {__ROOT_START__, __ROOT_END__}),
                                        "retry AST span");
                                check(
                                        root.source.offsetFromRoot().value() == 1
                                                && root.source.codePointLength().value() == end - 1,
                                        "retry CST span");
                                check(
                                        retained.info(oldId)
                                                .orElseThrow()
                                                .extra()
                                                .get("label")
                                                .equals("before"),
                                        "retained metadata");
                                if (manual) {
                                    c.rollback(parser);
                                    check(c.position() == 1, "manual outer rollback");
                                }
                                observed++;
                            } finally {
                                CallbackWord.metadata = null;
                                CallbackWord.registered = null;
                                CallbackWord.oldId = null;
                                CallbackWord.freshId = null;
                                CallbackWord.failure = null;
                            }
                        }
        return observed;
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
