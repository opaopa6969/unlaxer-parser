package org.unlaxer.dsl.tools.railroad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * Tests for the UBNF → Railroad Diagram generator.
 *
 * Verifies that:
 * - The tool can parse {@code grammar/ubnf.ubnf} and produce diagrams.
 * - Basic SVG structure is valid (contains svg, rect, text elements).
 * - Each rule produces a non-empty SVG string.
 * - Individual element types render correctly.
 */
public class RailroadDiagramTest {

    // =========================================================================
    // Helper — load grammar/ubnf.ubnf
    // =========================================================================

    private static String loadUbnfGrammar() throws IOException {
        Path grammarPath = Paths.get("grammar", "ubnf.ubnf");
        if (false == Files.exists(grammarPath)) {
            // Try from project root relative to typical Maven working directory
            grammarPath = Paths.get("..", "grammar", "ubnf.ubnf");
        }
        if (false == Files.exists(grammarPath)) {
            // Fall back to absolute path used in CI / documentation
            grammarPath = Paths.get(
                "/mnt/c/var/unlaxer-temp/unlaxer-dsl/grammar/ubnf.ubnf");
        }
        return new String(Files.readAllBytes(grammarPath), StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Parsing grammar/ubnf.ubnf
    // =========================================================================

    @Test
    public void testParseUbnfGrammarFile() throws IOException {
        String source = loadUbnfGrammar();
        UBNFFile ubnfFile = UBNFMapper.parse(source);
        assertNotNull(ubnfFile);
        assertFalse("Expected at least one grammar block", ubnfFile.grammars().isEmpty());
    }

    @Test
    public void testEachRuleProducesNonEmptySvg() throws IOException {
        String source = loadUbnfGrammar();
        UBNFFile ubnfFile = UBNFMapper.parse(source);

        for (GrammarDecl grammarDecl : ubnfFile.grammars()) {
            for (RuleDecl ruleDecl : grammarDecl.rules()) {
                RailroadDiagram diagram = UBNFToRailroad.convertRule(ruleDecl);
                String svg = RailroadDiagram.renderFullDiagram(ruleDecl.name(), diagram);

                assertFalse(
                    "SVG for rule '" + ruleDecl.name() + "' must not be empty",
                    svg.isEmpty()
                );
                assertTrue(
                    "SVG for rule '" + ruleDecl.name() + "' must start with <svg",
                    svg.contains("<svg")
                );
            }
        }
    }

    // =========================================================================
    // SVG structure validation
    // =========================================================================

    @Test
    public void testSvgContainsRequiredElements() throws IOException {
        String source = loadUbnfGrammar();
        UBNFFile ubnfFile = UBNFMapper.parse(source);

        // Pick the first rule to inspect
        GrammarDecl firstGrammar = ubnfFile.grammars().get(0);
        assertFalse("Grammar must have at least one rule", firstGrammar.rules().isEmpty());
        RuleDecl firstRule = firstGrammar.rules().get(0);

        RailroadDiagram diagram = UBNFToRailroad.convertRule(firstRule);
        String svg = RailroadDiagram.renderFullDiagram(firstRule.name(), diagram);

        assertTrue("SVG must contain <svg element", svg.contains("<svg"));
        assertTrue("SVG must contain closing </svg>", svg.contains("</svg>"));
        assertTrue("SVG must contain a <style> block", svg.contains("<style>"));
        assertTrue("SVG must contain at least one <text element", svg.contains("<text"));
        // Start and end markers use <circle>
        assertTrue("SVG must contain at least one <circle element", svg.contains("<circle"));
    }

    @Test
    public void testSvgContainsRailClass() throws IOException {
        String source = loadUbnfGrammar();
        UBNFFile ubnfFile = UBNFMapper.parse(source);

        for (GrammarDecl grammarDecl : ubnfFile.grammars()) {
            for (RuleDecl ruleDecl : grammarDecl.rules()) {
                RailroadDiagram diagram = UBNFToRailroad.convertRule(ruleDecl);
                String svg = RailroadDiagram.renderFullDiagram(ruleDecl.name(), diagram);
                // Every diagram must reference the .rail CSS class somewhere
                assertTrue(
                    "SVG for rule '" + ruleDecl.name() + "' must reference class=\"rail\"",
                    svg.contains("class=\"rail\"") || svg.contains("class='rail'")
                );
            }
        }
    }

    // =========================================================================
    // Individual element rendering
    // =========================================================================

    @Test
    public void testTerminalRendering() {
        RailroadDiagram.Terminal terminal = new RailroadDiagram.Terminal("test1", "'hello'");
        assertTrue("Terminal width must be positive", terminal.width() > 0);
        assertTrue("Terminal height must be positive", terminal.height() > 0);
        assertTrue("Terminal entryY must be within bounds", terminal.entryY() <= terminal.height());

        StringBuilder svg = new StringBuilder();
        terminal.renderSvg(svg, 0, 0);
        String output = svg.toString();

        assertTrue("Terminal must render a <rect", output.contains("<rect"));
        assertTrue("Terminal must render a <text", output.contains("<text"));
        assertTrue("Terminal must include terminal-box class", output.contains("terminal-box"));
        assertTrue("Terminal must include terminal-text class", output.contains("terminal-text"));
        assertTrue("Terminal must include the label text", output.contains("&apos;hello&apos;")
            || output.contains("'hello'"));
    }

    @Test
    public void testNonTerminalRendering() {
        RailroadDiagram.NonTerminal nonTerminal = new RailroadDiagram.NonTerminal("test2", "Expression", "TestGrammar");
        assertTrue("NonTerminal width must be positive", nonTerminal.width() > 0);

        StringBuilder svg = new StringBuilder();
        nonTerminal.renderSvg(svg, 0, 0);
        String output = svg.toString();

        assertTrue("NonTerminal must render a <rect", output.contains("<rect"));
        assertTrue("NonTerminal must render a <text", output.contains("<text"));
        assertTrue("NonTerminal must include nonterminal-box class", output.contains("nonterminal-box"));
        assertTrue("NonTerminal must include the label text", output.contains("Expression"));
    }

    @Test
    public void testSequenceRendering() {
        List<RailroadDiagram> elements = List.of(
            new RailroadDiagram.Terminal("test3a", "'if'"),
            new RailroadDiagram.NonTerminal("test3b", "Condition", "TestGrammar"),
            new RailroadDiagram.Terminal("test3c", "'then'")
        );
        RailroadDiagram.Sequence sequence = new RailroadDiagram.Sequence("test3seq", elements);

        assertTrue("Sequence width must be larger than any single element width",
            sequence.width() > elements.get(0).width());

        StringBuilder svg = new StringBuilder();
        sequence.renderSvg(svg, 0, 0);
        String output = svg.toString();

        assertTrue("Sequence must include connecting lines", output.contains("<line"));
    }

    @Test
    public void testChoiceRendering() {
        List<RailroadDiagram> alternatives = List.of(
            new RailroadDiagram.Terminal("tid131502651587552", "'+'"),
            new RailroadDiagram.Terminal("tid131502651587552", "'-'"),
            new RailroadDiagram.Terminal("tid131502651587552", "'*'")
        );
        RailroadDiagram.Choice choice = new RailroadDiagram.Choice("chid", alternatives);

        assertTrue("Choice height must account for all alternatives",
            choice.height() > alternatives.get(0).height());

        StringBuilder svg = new StringBuilder();
        choice.renderSvg(svg, 0, 0);
        String output = svg.toString();

        assertTrue("Choice must render curved connectors (<path)", output.contains("<path"));
    }

    @Test
    public void testOptionalRendering() {
        RailroadDiagram inner = new RailroadDiagram.NonTerminal("nid131502651587552", "Modifier", "Test");
        RailroadDiagram.Optional optional = new RailroadDiagram.Optional("optid", inner);

        assertTrue("Optional height must exceed inner height",
            optional.height() > inner.height());

        StringBuilder svg = new StringBuilder();
        optional.renderSvg(svg, 0, 0);
        String output = svg.toString();

        assertTrue("Optional must render bypass line (HLine)", output.contains("<line"));
        assertTrue("Optional must render curved connectors (<path)", output.contains("<path"));
    }

    @Test
    public void testRepeatRendering() {
        RailroadDiagram inner = new RailroadDiagram.NonTerminal("nid131502651587552", "Item", "Test");
        RailroadDiagram.Repeat repeat = new RailroadDiagram.Repeat("repid", inner);

        assertTrue("Repeat height must exceed inner height",
            repeat.height() > inner.height());

        StringBuilder svg = new StringBuilder();
        repeat.renderSvg(svg, 0, 0);
        String output = svg.toString();

        assertTrue("Repeat must render loop-back line", output.contains("<line"));
        assertTrue("Repeat must render curved connectors (<path)", output.contains("<path"));
        // The loop-back arrow
        assertTrue("Repeat must render an arrow", output.contains("<polygon"));
    }

    @Test
    public void testGroupRendersLikeInner() {
        RailroadDiagram inner = new RailroadDiagram.Terminal("tid131502651587552", "'token'");
        RailroadDiagram.Group group = new RailroadDiagram.Group("grpid", inner);

        assertEquals("Group width must equal inner width", inner.width(), group.width());
        assertEquals("Group height must equal inner height", inner.height(), group.height());
        assertEquals("Group entryY must equal inner entryY", inner.entryY(), group.entryY());

        StringBuilder innerSvg = new StringBuilder();
        inner.renderSvg(innerSvg, 0, 0);
        StringBuilder groupSvg = new StringBuilder();
        group.renderSvg(groupSvg, 0, 0);
        assertEquals("Group SVG output must match inner SVG output", innerSvg.toString(), groupSvg.toString());
    }

    // =========================================================================
    // Full-diagram SVG wrapper
    // =========================================================================

    @Test
    public void testFullDiagramSvgStructure() {
        RailroadDiagram element = new RailroadDiagram.NonTerminal("nid131502651587552", "MyRule", "Test");
        String svg = RailroadDiagram.renderFullDiagram("MyRule", element);

        assertTrue("Full SVG must open with <svg", svg.startsWith("<svg"));
        assertTrue("Full SVG must close with </svg>", svg.trim().endsWith("</svg>"));
        assertTrue("Full SVG must contain start circle", svg.contains("start-circle"));
        assertTrue("Full SVG must contain end circle", svg.contains("end-circle-outer"));
        assertTrue("Full SVG must contain the rule name label", svg.contains("MyRule"));
    }

    @Test
    public void testFullDiagramHasDimensions() {
        RailroadDiagram element = new RailroadDiagram.Terminal("tid131502651587552", "'test'");
        String svg = RailroadDiagram.renderFullDiagram("TestRule", element);

        assertTrue("SVG must declare width attribute", svg.contains("width=\""));
        assertTrue("SVG must declare height attribute", svg.contains("height=\""));
    }

    // =========================================================================
    // UBNFToRailroad conversion
    // =========================================================================

    @Test
    public void testSimpleRuleConversion() {
        String source = "grammar G {\n  MyRule ::= 'hello' ;\n}";
        UBNFFile ubnfFile = UBNFMapper.parse(source);
        RuleDecl rule = ubnfFile.grammars().get(0).rules().get(0);

        RailroadDiagram diagram = UBNFToRailroad.convertRule(rule);
        assertNotNull("Converted diagram must not be null", diagram);

        String svg = RailroadDiagram.renderFullDiagram(rule.name(), diagram);
        assertFalse("SVG must not be empty", svg.isEmpty());
        assertTrue("SVG must contain the terminal label text", svg.contains("hello"));
    }

    @Test
    public void testChoiceRuleConversion() {
        String source = "grammar G {\n  Op ::= '+' | '-' | '*' ;\n}";
        UBNFFile ubnfFile = UBNFMapper.parse(source);
        RuleDecl rule = ubnfFile.grammars().get(0).rules().get(0);

        RailroadDiagram diagram = UBNFToRailroad.convertRule(rule);
        assertTrue("Choice rule must produce a Choice element",
            diagram instanceof RailroadDiagram.Choice);

        RailroadDiagram.Choice choice = (RailroadDiagram.Choice) diagram;
        assertEquals("Choice must have 3 alternatives", 3, choice.alternatives().size());
    }

    @Test
    public void testOptionalElementConversion() {
        String source = "grammar G {\n  R ::= A [ B ] C ;\n}";
        UBNFFile ubnfFile = UBNFMapper.parse(source);
        RuleDecl rule = ubnfFile.grammars().get(0).rules().get(0);

        RailroadDiagram diagram = UBNFToRailroad.convertRule(rule);
        // The rule body is a Sequence: A, Optional(B), C
        assertTrue("Rule with optional must produce a Sequence",
            diagram instanceof RailroadDiagram.Sequence);

        RailroadDiagram.Sequence sequence = (RailroadDiagram.Sequence) diagram;
        assertEquals("Sequence must have 3 elements", 3, sequence.elements().size());
        assertTrue("Second element must be Optional",
            sequence.elements().get(1) instanceof RailroadDiagram.Optional);
    }

    @Test
    public void testRepeatElementConversion() {
        String source = "grammar G {\n  R ::= { Item } ;\n}";
        UBNFFile ubnfFile = UBNFMapper.parse(source);
        RuleDecl rule = ubnfFile.grammars().get(0).rules().get(0);

        RailroadDiagram diagram = UBNFToRailroad.convertRule(rule);
        assertTrue("Repeat rule must produce a Repeat element",
            diagram instanceof RailroadDiagram.Repeat);
    }

    @Test
    public void testGroupElementConversion() {
        String source = "grammar G {\n  R ::= ( A | B ) ;\n}";
        UBNFFile ubnfFile = UBNFMapper.parse(source);
        RuleDecl rule = ubnfFile.grammars().get(0).rules().get(0);

        RailroadDiagram diagram = UBNFToRailroad.convertRule(rule);
        assertTrue("Group rule must produce a Group element",
            diagram instanceof RailroadDiagram.Group);

        RailroadDiagram.Group group = (RailroadDiagram.Group) diagram;
        assertTrue("Group inner must be a Choice",
            group.inner() instanceof RailroadDiagram.Choice);
    }

    // =========================================================================
    // SVG escape helper
    // =========================================================================

    @Test
    public void testEscapeSvgSpecialCharacters() {
        assertEquals("&amp;", RailroadDiagram.escapeSvg("&"));
        assertEquals("&lt;", RailroadDiagram.escapeSvg("<"));
        assertEquals("&gt;", RailroadDiagram.escapeSvg(">"));
        assertEquals("&quot;", RailroadDiagram.escapeSvg("\""));
        assertEquals("hello world", RailroadDiagram.escapeSvg("hello world"));
    }
}
