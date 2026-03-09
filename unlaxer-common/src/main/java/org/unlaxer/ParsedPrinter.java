package org.unlaxer;

import org.unlaxer.listener.OutputLevel;

public class ParsedPrinter{
	
	public static String get(Parsed parsed , OutputLevel outputLevel){
		return String.format("status:%s | %s " , parsed.status.name() , 
				TokenPrinter.get("parsed", parsed.getOriginalTokens()));
	}
}