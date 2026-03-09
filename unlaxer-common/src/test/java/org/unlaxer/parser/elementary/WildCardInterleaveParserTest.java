package org.unlaxer.parser.elementary;

import java.util.List;
import java.util.Optional;

import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.ParserTestBase;
import org.unlaxer.TestResult;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.TokenPrinter;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

public class WildCardInterleaveParserTest extends ParserTestBase{

  @Test
  public void test() {
    
    setLevel(OutputLevel.mostDetail);
    
    String[] contents  = new String[] {
        "123",//
        "123牛丼  天ぷら",//
        "123牛丼寿司天ぷら",//
        "123牛丼すし天ぷら",//
        "123牛丼すし天ぷらasdas焼肉",//
    };
    
    for (String content : contents) {
      
      MixedContentParser mixedContentParser = new MixedContentParser();
      TestResult testAllMatch = testAllMatch(mixedContentParser,  content);
      
      Token rootToken = testAllMatch.parsed.getRootToken();
      System.err.println("test:" + content);
      TokenPrinter.output(rootToken , System.err);
      System.err.println();
      
      TokenList parsedWithConcattedCharcter = mixedContentParser.getParsedWithConcattedCharcter(rootToken);
      
      Token token = new Token(TokenKind.consumed, parsedWithConcattedCharcter, mixedContentParser);
      TokenPrinter.output(token , System.err);
      System.err.println();

    }
  }
  
  
  public static class MixedContentParser extends  WildCardInterleaveParser{

    @Override
    public Optional<Parser> getLazyTerminatorParser() {
      return Optional.of(Parser.get(LineTerminatorParser.class));
    }

    @Override
    List<Parser> interleaveParsers() {
      return new Parsers(
          new WordParser(Name.of("牛丼Parser"), "牛丼"),
          new WordParser("焼肉"),
          new WordParser("寿司"),
          new WordParser("天ぷら")
      );
    }
    
  }

}
