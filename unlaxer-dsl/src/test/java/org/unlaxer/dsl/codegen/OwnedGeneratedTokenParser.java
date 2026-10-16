package org.unlaxer.dsl.codegen;

import org.unlaxer.*;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.NoneChildParser;

/** Real external adapter: generated text has no input extent, independently of virtual kind. */
public class OwnedGeneratedTokenParser extends NoneChildParser {
    private static final long serialVersionUID=1L;
    @Override public Parser createParser(){return this;}
    @Override public Parsed parse(ParseContext c,TokenKind kind,boolean invert){
        Token token=new Token(TokenKind.virtualTokenConsumed,StringSource.createDetachedSource("bb"),this);
        c.getCurrent().addToken(token,TokenKind.virtualTokenConsumed);return new Parsed(token);
    }
}
