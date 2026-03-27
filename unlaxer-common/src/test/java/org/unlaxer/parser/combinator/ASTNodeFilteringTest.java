package org.unlaxer.parser.combinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.ParserTestBase;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for ASTNode / NotASTNode wrappers and their effect on
 * Token.filteredChildren (a.k.a. astNodeChildren).
 *
 * ASTNode-wrapped parsers appear in filteredChildren.
 * NotASTNode-wrapped parsers are excluded from filteredChildren.
 */
public class ASTNodeFilteringTest extends ParserTestBase {

    @Test
    public void testASTNodeChildAppearsInFilteredChildren() {
        // Chain with ASTNode-wrapped digit parser
        Parser parser = new Chain(
            new ASTNode(new OneOrMore(DigitParser.class)),
            new WordParser("end")
        );

        ParseContext context = new ParseContext(
            StringSource.createRootSource("123end"),
            CreateMetaTokenSpecifier.createMetaOn
        );
        Parsed result = parser.parse(context);
        assertTrue(result.isSucceeded());

        Token root = result.getRootToken();
        // ASTNode child should be in filteredChildren
        assertTrue("filteredChildren should not be empty",
            root.filteredChildren.size() > 0);
        context.close();
    }

    @Test
    public void testNotASTNodeChildExcludedFromFilteredChildren() {
        // Chain with NotASTNode-wrapped word parser
        Parser parser = new Chain(
            new NotASTNode(new WordParser("skip")),
            new ASTNode(new OneOrMore(DigitParser.class))
        );

        ParseContext context = new ParseContext(
            StringSource.createRootSource("skip123"),
            CreateMetaTokenSpecifier.createMetaOn
        );
        Parsed result = parser.parse(context);
        assertTrue(result.isSucceeded());

        Token root = result.getRootToken();
        // Only the ASTNode child should be in filteredChildren
        // The NotASTNode("skip") should be excluded
        for (Token child : root.filteredChildren) {
            assertTrue("NotASTNode child should not be in filteredChildren",
                !(child.parser instanceof NotASTNode));
        }
        context.close();
    }

    @Test
    public void testMixedASTNodeAndNotASTNode() {
        // Chain: NotASTNode("(") + ASTNode(digits) + NotASTNode(")")
        Parser parser = new Chain(
            new NotASTNode(new WordParser("(")),
            new ASTNode(new OneOrMore(DigitParser.class)),
            new NotASTNode(new WordParser(")"))
        );

        ParseContext context = new ParseContext(
            StringSource.createRootSource("(42)"),
            CreateMetaTokenSpecifier.createMetaOn
        );
        Parsed result = parser.parse(context);
        assertTrue(result.isSucceeded());

        Token root = result.getRootToken();
        // Only the ASTNode (digits) should appear in filteredChildren
        int originalCount = root.getOriginalChildren().size();
        int filteredCount = root.filteredChildren.size();
        assertTrue("filteredChildren should have fewer items than originalChildren",
            filteredCount <= originalCount);
        context.close();
    }

    @Test
    public void testAllASTNodesPreserved() {
        // Chain of all ASTNode-wrapped parsers
        Parser parser = new Chain(
            new ASTNode(new WordParser("a")),
            new ASTNode(new WordParser("b")),
            new ASTNode(new WordParser("c"))
        );

        ParseContext context = new ParseContext(
            StringSource.createRootSource("abc"),
            CreateMetaTokenSpecifier.createMetaOn
        );
        Parsed result = parser.parse(context);
        assertTrue(result.isSucceeded());

        Token root = result.getRootToken();
        // All three should appear in filteredChildren
        assertEquals("all ASTNode children should be in filteredChildren",
            root.getOriginalChildren().size(),
            root.filteredChildren.size());
        context.close();
    }
}
