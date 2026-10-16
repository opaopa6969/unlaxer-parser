package org.unlaxer.parser.combinator;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

import java.util.Objects;

/** Read outer capture bindings, discard local definitions on every exit. */
public class CaptureScope extends ConstructedAbstractParser implements MetaFunctionParser {
    private static final long serialVersionUID = 1L;

    public CaptureScope(Parser child) { super(new Parsers(Objects.requireNonNull(child, "child"))); }
    @Override public ChildOccurs getChildOccurs() { return ChildOccurs.single; }
    @Override public Parser getChild() { return getChildren().get(0); }
    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        return context.parseCaptureBoundary(this, getChild(), null, true, kind, invert);
    }
}
