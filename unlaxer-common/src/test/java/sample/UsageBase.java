package sample;

import org.unlaxer.Parsed;
import org.unlaxer.TokenPrinter;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

public class UsageBase {

	public static Parsed parse(Parser parser, ParseContext parseContext) {
		
		Parsed parsed = parser.parse(parseContext);
		
		//get parsing status
		System.out.format("parsed status: %s \n" , parsed.status);
		
		//get rootToken
		System.out.format("parsed Token: %s \n" , parsed.getRootToken());
		
		//get tokenTree representation
		System.out.format("parsed TokenTree:\n%s \n" , TokenPrinter.get(parsed.getRootToken()));

		//get rootToken
		System.out.format("parsed Token(reduced): %s \n" , parsed.getRootToken(true));

		//get tokenTree representation
		System.out.format("parsed TokenTree(reduced):\n%s \n" , TokenPrinter.get(parsed.getRootToken(true)));
		
		return parsed;

	}

}
