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

/** Capture a successful child's actual text without adding a CST boundary. */
public class NamedCapture extends ConstructedAbstractParser implements MetaFunctionParser {
    private static final long serialVersionUID = 1L;
    private final String binding;

    public NamedCapture(String binding, Parser child) {
        super(new Parsers(Objects.requireNonNull(child, "child")));
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    public String binding() { return binding; }
    @Override public ChildOccurs getChildOccurs() { return ChildOccurs.single; }
    @Override public Parser getChild() { return getChildren().get(0); }
    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        return context.parseCaptureBoundary(this, getChild(), binding, false, kind, invert);
    }
}
