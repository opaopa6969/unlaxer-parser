package org.unlaxer.parser.clang;

import java.util.Optional;
import java.util.function.Supplier;

import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.LazyZeroOrMore;

public class CStyleDelimitor extends LazyZeroOrMore{

  @Override
  public Supplier<Parser> getLazyParser() {
    return CStyleDelimitorElements::new;
  }

  @Override
  public Optional<Parser> getLazyTerminatorParser() {
    return Optional.empty();
  }
  
}