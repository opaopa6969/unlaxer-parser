package org.unlaxer.parser.clang;

import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.SpaceDelimitor;

public class CStyleDelimitorElements extends LazyChoice{

	private static final long serialVersionUID = -5762498031382073733L;

@Override
  public Parsers getLazyParsers() {
    
    return new Parsers(
        BlockComment.class,
        CPPComment.class,
        SpaceDelimitor.class
    );
  }
  
}
