package org.unlaxer.parser;

import org.unlaxer.listener.OutputLevel;

public class ParserPrinter{
	
	public static String get(Parser parser , OutputLevel level){
		
		if(level.isMostDetail()) {
			String path = parser.getPath();
			return String.format("parser:%s", path);
		}
		
		String parserString = level == OutputLevel.detail ? 
				parser.getParentPath()+"/"+ parser.getName().toString():
				parser.getName().toString();
		return String.format("parser:%s", parserString);
	}
}