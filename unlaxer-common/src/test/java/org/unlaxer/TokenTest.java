package org.unlaxer;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.Token.ScanDirection;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.posix.DotParser;
import org.unlaxer.parser.posix.HashParser;

public class TokenTest {

	@Test
	public void testFlatten() {
		
		
		TokenList third = TokenList.of(new Token(TokenKind.matchOnly, Source.EMPTY, new HashParser()));
		TokenList second = TokenList.of(
			new Token(TokenKind.matchOnly, third, new DotParser()),
			new Token(TokenKind.matchOnly, third, new DotParser())
		);
		Token root = new Token(TokenKind.matchOnly, second, new PlusParser());
		{
			List<Token> flatten = root.flatten(ScanDirection.Breadth);
			String collect = flatten.stream()
					.map(token->token.parser.getName().getSimpleName())
					.collect(Collectors.joining(","));
			
			System.out.println(collect);
			
			assertEquals("PlusParser,DotParser,DotParser,HashParser,HashParser",collect);
		}
		{
			List<Token> flatten = root.flatten(ScanDirection.Depth);
			String collect = flatten.stream()
					.map(token->token.parser.getName().getSimpleName())
					.collect(Collectors.joining(","));
			
			System.out.println(collect);
			
			assertEquals("PlusParser,DotParser,HashParser,DotParser,HashParser",collect);
		}
	}

}
