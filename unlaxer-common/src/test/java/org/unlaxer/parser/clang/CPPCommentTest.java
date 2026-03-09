package org.unlaxer.parser.clang;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.elementary.WordParser;

public class CPPCommentTest extends ParserTestBase{

  @Test
  public void test() {
    
    WordParser abc = new WordParser("abc");
    WordParser dead = new WordParser("dead");
    CPPComment cppComment = new CPPComment();
    
    Chain chainAbcCpp = new Chain(abc,cppComment);
    Chain cpp = new Chain(cppComment);
    Chain chainCppDead = new Chain(cppComment , dead);

    testAllMatch(cpp,"//unko dead");
    testAllMatch(chainAbcCpp,"abc//unko");
    testUnMatch(chainCppDead,"//dead");
  }

}
