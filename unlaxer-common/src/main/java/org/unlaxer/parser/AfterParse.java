package org.unlaxer.parser;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;

public interface AfterParse{
	  public Parsed afterParse(ParseContext parseContext, Parsed parsed , TokenKind tokenKind, boolean invertMatch);
  }