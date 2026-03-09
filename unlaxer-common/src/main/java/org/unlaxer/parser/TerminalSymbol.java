package org.unlaxer.parser;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public interface TerminalSymbol extends Serializable{

  default Optional<String> expectedDisplayText() {
    return Optional.empty();
  }

  default List<String> expectedDisplayTexts() {
    Optional<String> single = expectedDisplayText();
    if (single.isPresent()) {
      return Collections.singletonList(single.get());
    }
    return Collections.emptyList();
  }
}
