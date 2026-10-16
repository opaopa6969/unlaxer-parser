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
                                var outerTokens=List.copyOf(c.getCurrent().getTokens());
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
                                check(c.position() == end && c.getMatchedPosition().value() == end, "retry cursors");
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
                                String expectedBoundary = __EXPECTED_BOUNDARY__;
                                var retainedBoundary = expectedBoundary == null ? null : boundary(root, expectedBoundary);
                                check(
                                        retained.info(oldId)
                                                .orElseThrow()
                                                .extra()
                                                .get("label")
                                                .equals("before"),
                                        "retained metadata");
                                if (manual) {
                                    c.rollback(parser);
                                    check(c.position() == 1 && c.getMatchedPosition().value() == 1, "manual outer rollback cursors");
                                    check(c.getTokenStack().size() == 1 && registered[0] == 7, "manual outer rollback state/frames");
                                    check(outerTokens.equals(c.getCurrent().getTokens()), "manual outer rollback CST");
                                    check(c.captured("word").orElseThrow().text().equals("p"), "manual outer rollback capture");
                                    check(CallbackWord.metadata.info(oldId).orElseThrow().extra().get("label").equals("before"), "manual outer rollback metadata");
                                    check(Arrays.equals(mapped.sourceSpanOf(value).orElseThrow(),new int[] {__ROOT_START__, __ROOT_END__}), "retained AST span after rollback");
                                }
                                if (retainedBoundary != null) check(canonical(retainedBoundary).equals(expectedBoundary), "retained nested boundary");
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

    private static org.unlaxer.capture.CaptureSnapshot.Element boundary(Token root,String expected) {
        var pending=new ArrayDeque<Token>();pending.push(root);
        org.unlaxer.capture.CaptureSnapshot.Element retained=null;
        int separators=0;
        while(!pending.isEmpty()) {
            Token token=pending.pop();
            if(token.parser instanceof org.unlaxer.parser.combinator.CaptureBoundaryParser marker) {
                if(marker.boundaryKind()==org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.LIST) {
                    check(retained==null,"one outer list boundary");
                    retained=org.unlaxer.capture.CaptureSnapshot.from(token);
                    check(canonical(retained).equals(expected),"retry item boundaries " + canonical(retained));
                }
                if(marker.boundaryKind()==org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.SEPARATOR) {
                    separators++;
                    try {org.unlaxer.capture.CaptureSnapshot.from(token);throw new AssertionError("separator root accepted");}
                    catch(org.unlaxer.capture.CaptureSnapshot.SnapshotException failure) {check(failure.error()==org.unlaxer.capture.CaptureSnapshot.Error.NOT_BOUNDARY,"separator root diagnosis");}
                }
            }
            for(Token child:token.getOriginalChildren())pending.push(child);
        }
        check(retained!=null && separators==1,"separator CST retained exactly once");
        return retained;
    }
    private static String canonical(org.unlaxer.capture.CaptureSnapshot.Element element) {
        String kind=switch(element.kind()) {case VALUE->"V";case OPTIONAL->"O";case LIST->"L";case SEPARATOR->throw new AssertionError("opaque separator is not a value");};
        String text=element.text().isEmpty()?"~":HexFormat.of().formatHex(element.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return kind+"@"+element.startCP()+":"+element.endCP()+":"+text+"["+element.children().stream().map(JavaProbe::canonical).collect(java.util.stream.Collectors.joining(","))+"]";
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
