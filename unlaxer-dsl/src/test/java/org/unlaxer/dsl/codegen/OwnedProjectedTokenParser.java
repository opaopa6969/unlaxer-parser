package org.unlaxer.dsl.codegen;

import org.unlaxer.*;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.NoneChildParser;

/** Own text is bb at logical CP1..3, while the callback consumes only input a at CP0..1. */
public class OwnedProjectedTokenParser extends NoneChildParser {
    private static final long serialVersionUID=1L;
    @Override public Parser createParser(){return this;}
    @Override public Parsed parse(ParseContext c,TokenKind kind,boolean invert){
        Source b=c.peek(new CodePointIndex(1),new CodePointLength(1));
        if(!b.sourceAsString().equals("b"))return Parsed.FAILED;
        var leaf=new Token(TokenKind.virtualTokenConsumed,b,this);
        Token token=new Token(TokenKind.virtualTokenConsumed,TokenList.of(leaf,leaf),this);
        c.getCurrent().addToken(token,TokenKind.virtualTokenConsumed);c.consume(new CodePointLength(1));return new Parsed(token);
    }
}
