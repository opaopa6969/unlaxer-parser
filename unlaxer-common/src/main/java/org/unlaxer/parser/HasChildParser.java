package org.unlaxer.parser;

public interface HasChildParser extends CollectingParser{
	
	public Parser getChild();
}