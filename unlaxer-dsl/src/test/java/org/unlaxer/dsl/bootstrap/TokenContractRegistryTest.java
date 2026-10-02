package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;
import org.unlaxer.dsl.PortabilityCheck;

public class TokenContractRegistryTest {
    @Test
    public void format2ContractDescribesAnExistingFqnTokenWithoutBindingReplacement() {
        String source = """
            grammar G {
              @ubnf: v2
              @tokenContract: { token: 'IDENTIFIER' accepts: 'ASCII identifier' failure: 'no-consume' consumes: 'always' context: 'remaining,position' }
              token IDENTIFIER = org.unlaxer.parser.clang.IdentifierParser
              @root @mapping(Root, params=[value]) Root ::= IDENTIFIER @value;
            }
            """;
        var snapshot = UBNFMapper.parseWithSource(source);
        var build = TokenContractRegistry.build(snapshot.ast().grammars().get(0), snapshot);
        assertTrue(build.diagnostics().toString(), build.diagnostics().isEmpty());
        var contract = build.contracts().get("IDENTIFIER");
        assertEquals("ASCII identifier", contract.accepts());
        assertEquals("no-consume", contract.failure());
        assertEquals("always", contract.consumes());
        assertEquals(Set.of("remaining", "position"), contract.contextAccessors());
        var portability = PortabilityCheck.check(source);
        assertTrue(portability.toString(), portability.portable());
    }

    @Test
    public void contractRequiresV2AndAKnownUniqueTokenWithValidProgressContract() {
        String source = """
            grammar G {
              @tokenContract: { token: 'MISSING' accepts: 'x' failure: 'no-consume' consumes: 'sometimes' context: 'captures' }
              @root Root ::= 'x';
            }
            """;
        var grammar = UBNFMapper.parseWithSource(source).ast().grammars().get(0);
        assertEquals("P-TOKEN-CONTRACT-VERSION", TokenContractRegistry.build(grammar, null).diagnostics().get(0).code());
    }
}
