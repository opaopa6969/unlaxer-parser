package org.unlaxer.parser.elementary;

import java.util.List;
import java.util.function.Supplier;

import org.unlaxer.CodePointOffset;
import org.unlaxer.Cursor.StartInclusiveCursor;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.LazyZeroOrMore;
import org.unlaxer.util.SimpleBuilder;
import org.unlaxer.util.annotation.TokenExtractor;

public abstract class WildCardInterleaveParser extends LazyZeroOrMore{
  
  abstract List<Parser> interleaveParsers();
  
  @Override
  public Supplier<Parser> getLazyParser() {
    Parsers parsers = Parsers.of(interleaveParsers());
    parsers.add(Parser.get(WildCardCharacterParser.class));
    return ()->   new Choice(parsers);
  }
  
  
  @TokenExtractor
  public TokenList getParsedWithConcattedCharcter(Token thisParserParsed) {
    
    TokenList concatted = new TokenList();
    TokenList flatten = thisParserParsed.filteredChildren;
    
    SimpleBuilder characters = new SimpleBuilder();
    StartInclusiveCursor current = null;
    
    for (Token token : flatten) {
      token = ChoiceInterface.choiced(token);
      if(token.parser instanceof SingleCharacterParser) {
        if(current == null) {
          current = token.source.cursorRange().startIndexInclusive;
        }
        characters.append(token.source.toString());
      }else {
        if(characters.length()>0) {
          Token token2 = new Token(
              TokenKind.consumed,
              StringSource.createDetachedSource(
                  characters.toString(),
                  thisParserParsed.source.root(),
                  new CodePointOffset(current.position())
              ), 
              Parser.get(WildCardStringParser.class)
           );
          concatted.add(token2);
          characters = new SimpleBuilder();
          current = null;
        }
        concatted.add(token);
      }
    }
    return concatted;
  }

}
