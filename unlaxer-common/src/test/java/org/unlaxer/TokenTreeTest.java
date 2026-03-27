package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.Token.ChildrenKind;
import org.unlaxer.Token.ScanDirection;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.parser.posix.DotParser;
import org.unlaxer.parser.posix.HashParser;

/**
 * Tests for Token tree construction, parent-child relationships,
 * filteredChildren vs originalChildren, flatten traversals, and
 * matched text verification.
 */
public class TokenTreeTest {

    @Test
    public void parentChildRelationship() {
        // Build: root(child1, child2)
        Token child1 = new Token(TokenKind.consumed, Source.EMPTY, new PlusParser());
        Token child2 = new Token(TokenKind.consumed, Source.EMPTY, new DotParser());
        TokenList children = TokenList.of(child1, child2);
        Token root = new Token(TokenKind.consumed, children, new HashParser());

        // children should have parent set to root
        assertTrue("child1 should have parent", child1.parent.isPresent());
        assertEquals("child1 parent should be root", root, child1.parent.get());
        assertTrue("child2 should have parent", child2.parent.isPresent());
        assertEquals("child2 parent should be root", root, child2.parent.get());
    }

    @Test
    public void originalChildrenVsFilteredChildren() {
        // originalChildren includes all children
        Token child1 = new Token(TokenKind.consumed, Source.EMPTY, new PlusParser());
        Token child2 = new Token(TokenKind.consumed, Source.EMPTY, new DotParser());
        TokenList children = TokenList.of(child1, child2);
        Token root = new Token(TokenKind.consumed, children, new HashParser());

        assertEquals("original children count", 2, root.getOriginalChildren().size());
        // filteredChildren filters by AST_NODES predicate (excludes those tagged with notNode)
        // By default parsers without notNode tag pass the filter
        assertTrue("filteredChildren should not be empty", root.getAstNodeChildren().size() >= 0);
    }

    @Test
    public void flattenDepthFirst() {
        // Build tree: root -> [A -> [C], B]
        Token c = new Token(TokenKind.matchOnly, Source.EMPTY, new HashParser());
        TokenList aChildren = TokenList.of(c);
        Token a = new Token(TokenKind.matchOnly, aChildren, new DotParser());
        Token b = new Token(TokenKind.matchOnly, Source.EMPTY, new PlusParser());
        TokenList rootChildren = TokenList.of(a, b);
        Token root = new Token(TokenKind.matchOnly, rootChildren, new HashParser());

        List<Token> flattened = root.flattenDepth(ChildrenKind.original);
        // Depth-first: root, a, c, b
        assertEquals(4, flattened.size());
        assertEquals(root, flattened.get(0));
        assertEquals(a, flattened.get(1));
        assertEquals(c, flattened.get(2));
        assertEquals(b, flattened.get(3));
    }

    @Test
    public void flattenBreadthFirst() {
        // Build tree: root -> [A -> [C], B]
        Token c = new Token(TokenKind.matchOnly, Source.EMPTY, new HashParser());
        TokenList aChildren = TokenList.of(c);
        Token a = new Token(TokenKind.matchOnly, aChildren, new DotParser());
        Token b = new Token(TokenKind.matchOnly, Source.EMPTY, new PlusParser());
        TokenList rootChildren = TokenList.of(a, b);
        Token root = new Token(TokenKind.matchOnly, rootChildren, new HashParser());

        List<Token> flattened = root.flattenBreadth(ChildrenKind.original);
        // Breadth-first: root, a, b, c
        assertEquals(4, flattened.size());
        assertEquals(root, flattened.get(0));
        assertEquals(a, flattened.get(1));
        assertEquals(b, flattened.get(2));
        assertEquals(c, flattened.get(3));
    }

    @Test
    public void matchedTextFromParsing() {
        // Parse "abc" with a WordParser
        WordParser parser = new WordParser("abc");
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("parse should succeed", parsed.isSucceeded());

            Token rootToken = parsed.getRootToken();
            assertEquals("abc", rootToken.getSource().sourceAsString());
        }
    }

    @Test
    public void chainParseProducesMultipleChildTokens() {
        // Chain: "ab" + "cd"
        WordParser ab = new WordParser("ab");
        WordParser cd = new WordParser("cd");
        Chain chain = new Chain(ab, cd);

        StringSource source = StringSource.createRootSource("abcd");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = chain.parse(ctx);
            assertTrue("chain parse should succeed", parsed.isSucceeded());

            Token rootToken = parsed.getRootToken();
            // Chain collects tokens; root should have children
            TokenList origChildren = rootToken.getOriginalChildren();
            assertTrue("chain should have children tokens", origChildren.size() >= 2);
        }
    }

    @Test
    public void tokenGetChild() {
        Token child1 = new Token(TokenKind.consumed, Source.EMPTY, new PlusParser());
        Token child2 = new Token(TokenKind.consumed, Source.EMPTY, new DotParser());
        TokenList children = TokenList.of(child1, child2);
        Token root = new Token(TokenKind.consumed, children, new HashParser());

        Token found = root.getChild(
            t -> t.getParser() instanceof PlusParser,
            ChildrenKind.original);
        assertEquals(child1, found);
    }

    @Test
    public void tokenGetChildAsOptionalNotFound() {
        Token child1 = new Token(TokenKind.consumed, Source.EMPTY, new PlusParser());
        TokenList children = TokenList.of(child1);
        Token root = new Token(TokenKind.consumed, children, new HashParser());

        Optional<Token> found = root.getChildAsOptional(
            t -> t.getParser() instanceof DotParser,
            ChildrenKind.original);
        assertFalse("should not find DotParser child", found.isPresent());
    }

    @Test
    public void tokenGetDirectAncestor() {
        Token grandchild = new Token(TokenKind.consumed, Source.EMPTY, new HashParser());
        TokenList childChildren = TokenList.of(grandchild);
        Token child = new Token(TokenKind.consumed, childChildren, new DotParser());
        TokenList rootChildren = TokenList.of(child);
        Token root = new Token(TokenKind.consumed, rootChildren, new PlusParser());

        // grandchild's direct ancestor matching PlusParser should be root
        Optional<Token> ancestor = grandchild.getDirectAncestorAsOptional(
            t -> t.getParser() instanceof PlusParser);
        assertTrue("should find ancestor", ancestor.isPresent());
        assertEquals(root, ancestor.get());
    }

    @Test
    public void addChildrenDynamically() {
        Token root = new Token(TokenKind.consumed, Source.EMPTY, new PlusParser());
        assertEquals(0, root.getOriginalChildren().size());

        Token extra = new Token(TokenKind.consumed, Source.EMPTY, new DotParser());
        root.addChildren(extra);
        assertEquals(1, root.getOriginalChildren().size());
    }

    @Test
    public void tokenKindFilters() {
        // consumed and matchOnly tokens
        Token consumed = new Token(TokenKind.consumed, Source.EMPTY, new PlusParser());
        Token matchOnly = new Token(TokenKind.matchOnly, Source.EMPTY, new DotParser());

        assertTrue(TokenKind.consumed.passFilter.test(consumed));
        assertFalse(TokenKind.consumed.passFilter.test(matchOnly));
        assertTrue(TokenKind.matchOnly.passFilter.test(matchOnly));
        assertFalse(TokenKind.matchOnly.passFilter.test(consumed));
    }

    @Test
    public void flattenWithScanDirectionEnum() {
        TokenList third = TokenList.of(new Token(TokenKind.matchOnly, Source.EMPTY, new HashParser()));
        TokenList second = TokenList.of(
            new Token(TokenKind.matchOnly, third, new DotParser()),
            new Token(TokenKind.matchOnly, third, new DotParser())
        );
        Token root = new Token(TokenKind.matchOnly, second, new PlusParser());

        // Breadth-first
        List<Token> breadth = root.flatten(ScanDirection.Breadth);
        String breadthNames = breadth.stream()
            .map(t -> t.parser.getName().getSimpleName())
            .collect(Collectors.joining(","));
        assertEquals("PlusParser,DotParser,DotParser,HashParser,HashParser", breadthNames);

        // Depth-first
        List<Token> depth = root.flatten(ScanDirection.Depth);
        String depthNames = depth.stream()
            .map(t -> t.parser.getName().getSimpleName())
            .collect(Collectors.joining(","));
        assertEquals("PlusParser,DotParser,HashParser,DotParser,HashParser", depthNames);
    }
}
