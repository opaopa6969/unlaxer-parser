package org.unlaxer.parser.clang;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.listener.OutputLevel;

public class BlockCommentTest extends ParserTestBase{

	@Test
	public void test() {
		
		setLevel(OutputLevel.detail);
		
		BlockComment blockComment = new BlockComment();
		testAllMatch(blockComment,"/* niku */");
		testUnMatch(blockComment,"");
		testAllMatch(blockComment,"/**/");
		testUnMatch(blockComment,"/*");
	}

}
