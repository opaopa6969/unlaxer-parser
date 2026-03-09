package org.unlaxer.parser.clang;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ChainParsers;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.LineTerminatorParser;
import org.unlaxer.parser.elementary.WildCardStringTerninatorParser;
import org.unlaxer.parser.elementary.WordParser;

public class CPPComment extends LazyChain{

  // parser for // comment
  public CPPComment() {
    super();
  }

  public CPPComment(Name name) {
    super(name);
  }
  
  @Override
  public Parsed parse(ParseContext parseContext , TokenKind tokenKind ,boolean invertMatch) {
    return super.parse(parseContext, tokenKind, invertMatch);
  }
  
  @Override
  public Parsers getLazyParsers() {
    return new ChainParsers(
      new WordParser("//"),
      new WildCardStringTerninatorParser(true,Parser.get(LineTerminatorParser.class))
    );
  }

}