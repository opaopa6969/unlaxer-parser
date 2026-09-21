package org.unlaxer.context;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;

/** Preparation-time checks for entry points that retry failures with detailed diagnostics. */
public final class DiagnosticsSafety {
  private DiagnosticsSafety() {}

  /**
   * Checks every reachable parser, including children of marked parsers. Existing library
   * parsers in {@code org.unlaxer.parser} and its subpackages are trusted; other parsers must
   * implement {@link DiagnosticsAgnostic}. Cycles and shared nodes are visited by identity.
   * Cache the result only while the parser graph and its behavior remain unchanged.
   */
  public static boolean isDeferredDiagnosticsSafe(Parser root) {
    Set<Parser> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    var pending = new ArrayDeque<Parser>();
    pending.push(Objects.requireNonNull(root, "root"));
    while (!pending.isEmpty()) {
      Parser parser = pending.pop();
      if (!visited.add(parser)) continue;
      String packageName = parser.getClass().getPackageName();
      if (!(parser instanceof DiagnosticsAgnostic)
          && !packageName.equals("org.unlaxer.parser")
          && !packageName.startsWith("org.unlaxer.parser.")) return false;
      if (parser instanceof HasChildrenParser parent) {
        for (Parser child : parent.getChildren()) pending.push(child);
      }
    }
    return true;
  }
}
