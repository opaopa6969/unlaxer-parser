package org.unlaxer.dsl.codegen.rust;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** Structural contract shared with the native Rust lowering and emitter. */
public class RustRecoveryBackendTest {
    private static final String SOURCE = """
        grammar RecoveryLowering {
          @root @mapping(Root) Root ::= Auto ';' Skip '!' Sync;
          @recovery(auto) Auto ::= 'a';
          @recovery(skip) Skip ::= 'b';
          @recovery(sync=';,}') Sync ::= 'c';
        }
        """;

    @Test public void lowersAllModesWithOrderedFollowAndGuardsMapper() {
        var grammar = UBNFMapper.parse(SOURCE).grammars().get(0);
        var ir = RustGrammarLowering.lower(grammar);
        assertEquals(4, ir.rules().size());
        assertArrayEquals(new GrammarIR.RecoveryMode[]{GrammarIR.RecoveryMode.BEFORE_SYNC,
            GrammarIR.RecoveryMode.SKIP, GrammarIR.RecoveryMode.SYNC},
            ir.rules().subList(1, 4).stream().map(rule -> ((GrammarIR.Recovery) rule.body()).mode())
                .toArray(GrammarIR.RecoveryMode[]::new));
        assertEquals(List.of(";"), ((GrammarIR.Recovery) ir.rules().get(1).body()).tokens());
        assertEquals(List.of("!"), ((GrammarIR.Recovery) ir.rules().get(2).body()).tokens());
        assertEquals(List.of(";", "}"), ((GrammarIR.Recovery) ir.rules().get(3).body()).tokens());
        var generated = new RustBackend().generate(grammar);
        assertEquals(5, generated.size());
        String parser = generated.stream().filter(file -> file.relativePath().equals("parser.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(parser.contains("unlaxer_runtime::RecoveryMode::BeforeSync"));
        assertTrue(parser.contains("unlaxer_runtime::RecoveryMode::Skip"));
        assertTrue(parser.contains("unlaxer_runtime::RecoveryMode::Sync"));
        String mapper = generated.stream().filter(file -> file.relativePath().equals("mapper.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(mapper.contains("cannot map recovered syntax"));
    }

    @Test public void rejectsDuplicateAndEmptyRawSyncTokens() {
        for (List<String> tokens : List.of(List.of(";", ";"), List.of(""))) {
            assertThrows(IllegalArgumentException.class, () -> new GrammarIR.Recovery(
                new GrammarIR.Literal("x"), GrammarIR.RecoveryMode.SYNC, tokens, "error"));
        }
        assertEquals(List.of(), new GrammarIR.Recovery(new GrammarIR.Literal("x"),
            GrammarIR.RecoveryMode.SYNC, List.of(), "error").tokens());
    }

    @Test public void predictiveReferenceToRecoverableRuleCannotBePruned() {
        String source = "grammar G { @root @mapping(Root,params=[value]) @predictiveChoice "
            + "Root ::= Recoverable @value | 'x' @value; "
            + "@recovery(sync=';') Recoverable ::= 'a'; }";
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var ir = RustGrammarLowering.lower(grammar);
        var choice = (GrammarIR.PredictiveChoice) ir.rules().get(0).body();
        assertTrue(choice.predictors().get(0) instanceof GrammarIR.AnyPredictor);
    }

    @Test public void customProviderMayRecoverInternallySoMapperAlwaysChecksTree() {
        String source = "grammar G { @tokenAdapter: { id: 'example.word' version: '1' "
            + "java: 'example.WordParser' rust: 'crate::word_token' } "
            + "token W = ADAPTER('example.word', version=1) "
            + "@root @mapping(R,params=[value]) Root ::= W @value; }";
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        String mapper = new RustBackend().generate(grammar).stream()
            .filter(file -> file.relativePath().equals("mapper.rs"))
            .findFirst().orElseThrow().content();
        assertTrue(mapper.contains("cannot map recovered syntax"));
    }
}
