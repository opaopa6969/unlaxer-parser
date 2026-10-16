package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class OwnedCaptureSyntaxTest {
    @Test public void generalOwnedCallsAreParsedAsDedicatedOwnedSyntax() throws Exception {
        var file=UBNFMapper.parse(Files.readString(Path.of("../rust/unlaxer-ubnf/tests/fixtures/positive/owned-capture.ubnf")));
        var syntax=new RustUbnfFrontendConformanceTest().canonical(file).toString();
        assertTrue(syntax,syntax.contains("CaptureScopeElement"));
        assertTrue(syntax,syntax.contains("OwnedCaptureElement"));
        assertTrue(syntax,syntax.contains("ReplayElement"));
    }
    @Test public void triviaBeforeParenthesisPreservesExistingReferencesAndGroups() throws Exception {
        String source="grammar Old { Root ::= CAPTURE ('x') SAME_AS // comment\n('x') CAPTURE_SCOPE\n('x'); CAPTURE ::= 'c'; SAME_AS ::= 's'; CAPTURE_SCOPE ::= 'g'; }";
        var syntax=new RustUbnfFrontendConformanceTest().canonical(UBNFMapper.parse(source)).toString();
        assertFalse(syntax,syntax.contains("OwnedCaptureElement"));
        assertFalse(syntax,syntax.contains("ReplayElement"));
        assertFalse(syntax,syntax.contains("CaptureScopeElement"));
        assertTrue(syntax,syntax.contains("GroupElement"));
    }
    @Test public void bothJavaGenerationTargetsRetainTheGeneralOwnedOperations() throws Exception {
        var grammar=UBNFMapper.parse(Files.readString(Path.of("../rust/unlaxer-ubnf/tests/fixtures/positive/owned-capture.ubnf"))).grammars().get(0);
        var parser=new ParserGenerator().generate(grammar).source();
        assertTrue(parser,parser.contains("new org.unlaxer.parser.combinator.NamedCapture(\"word\""));
        assertTrue(parser,parser.contains("new org.unlaxer.parser.elementary.ReplayParser(\"word\""));
        assertTrue(parser,parser.contains("new org.unlaxer.parser.combinator.CaptureScope("));
        new ASTGenerator().generate(grammar);
        new MapperGenerator().generate(grammar);
        var rust=new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar).stream().map(f->f.content()).reduce("",String::concat);
        assertTrue(rust,rust.contains("Expr::OwnedCapture(\"word\""));
        assertTrue(rust,rust.contains("Expr::Replay(\"word\""));
        assertTrue(rust,rust.contains("Expr::CaptureScope("));
    }
    @Test public void malformedCallsCannotFallbackToAnOrdinaryReferenceAndGroup() {
        for(String body:List.of("CAPTURE(name)","CAPTURE(name,)","CAPTURE(, 'a')",
            "CAPTURE_SCOPE()","CAPTURE_SCOPE('a', 'b')","SAME_AS()","SAME_AS(name, other)")){
            String grammar="grammar BadOwned { @root Root ::= "+body+"; name ::= 'a'; other ::= 'b'; CAPTURE ::= 'c'; SAME_AS ::= 's'; CAPTURE_SCOPE ::= 'g'; }";
            assertThrows(body,RuntimeException.class,()->UBNFMapper.parse(grammar));
        }
    }
    @Test public void authoringViewsRetainOwnedOperatorsAndBindingNames() {
        var file=UBNFMapper.parse("grammar View { Root ::= CAPTURE_SCOPE(CAPTURE(word, 'a') SAME_AS(word)); }");
        String bnf=org.unlaxer.dsl.tools.bnf.UBNFToBNFConverter.convert(file,false);
        var rule=file.grammars().get(0).rules().get(0);
        String svg=org.unlaxer.dsl.tools.railroad.RailroadDiagram.renderFullDiagram(rule.name(),
            org.unlaxer.dsl.tools.railroad.UBNFToRailroad.convertRule(rule));
        for(String expected:List.of("CAPTURE_SCOPE", "CAPTURE(word", "SAME_AS(word)")) {
            assertTrue(bnf,bnf.contains(expected));
            assertTrue(svg,svg.contains(expected));
        }
    }
}
