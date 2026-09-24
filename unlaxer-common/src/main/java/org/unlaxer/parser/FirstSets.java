package org.unlaxer.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.unlaxer.parser.combinator.ChainInterface;
import org.unlaxer.parser.combinator.ChoiceInterface;
import org.unlaxer.parser.combinator.Occurs;
import org.unlaxer.parser.elementary.SingleCharacterParser;
import org.unlaxer.parser.elementary.WordParser;

/**
 * Derives a conservative {@link FirstSet} for a parser from the combinator graph, once per parser
 * instance.
 *
 * <p>The graph of a recursive grammar is cyclic, so the sets are the least fixed point of the
 * combinator rules over the sub-graph reachable from the requested parser: a choice unions its
 * alternatives, a chain unions its nullable prefix, a repetition unions its body (and its
 * terminator, which is conservative), and every construct the analysis does not model — lookahead,
 * inverted matching, error recovery, back references, anything with its own
 * {@code parse} — contributes {@link FirstSet#UNKNOWN} and can therefore never be excluded.
 *
 * <p>Parsers are shared singletons across threads (see {@code AbstractParser.getChildren}), so the
 * analysis runs under one lock and publishes each result into a concurrent map. Two threads racing
 * on the same parser at worst analyse the same sub-graph twice and store the same answer; a reader
 * either misses the map and takes the lock, or sees a fully constructed, immutable set.
 */
public final class FirstSets {

  /**
   * A grammar larger than this is analysed as {@link FirstSet#UNKNOWN} rather than walked. The
   * generated tinyexpression P4 grammar reaches about 1,200 parsers.
   */
  private static final int MAX_NODES = 100_000;

  /** The lattice is finite and every rule is monotone, so this bound is never reached in practice. */
  private static final int MAX_ROUNDS = 1_000;

  /**
   * Results live here rather than in a field on the parser, because a consumer can put its own fork
   * of {@code org.unlaxer.parser.AbstractParser} ahead of this jar on the classpath, and a field
   * that fork does not declare would fail at run time. No parser in this library overrides
   * {@code equals}/{@code hashCode}, so the map is keyed by identity in practice; a grammar built
   * per request rather than once stops being cached at {@link #MAX_CACHED} entries.
   */
  private static final Map<Parser, FirstSet> RESULTS = new ConcurrentHashMap<>();

  /** Bounds the cache so a caller that builds fresh parsers per request cannot retain them all. */
  private static final int MAX_CACHED = 200_000;

  private FirstSets() {
  }

  /** The start set of {@code parser}, computed on first use and cached on the parser instance. */
  public static FirstSet of(Parser parser) {
    FirstSet cached = cached(parser);
    return cached != null ? cached : analyze(parser);
  }

  private static FirstSet cached(Parser parser) {
    return RESULTS.get(parser);
  }

  private static void publish(Parser parser, FirstSet firstSet) {
    if (RESULTS.size() < MAX_CACHED) {
      RESULTS.put(parser, firstSet);
    }
  }

  private static synchronized FirstSet analyze(Parser root) {
    FirstSet cached = cached(root);
    if (cached != null) {
      return cached;
    }
    Map<Parser, Node> nodes = new IdentityHashMap<>();
    List<Node> ordered = new ArrayList<>();
    Deque<Parser> pending = new ArrayDeque<>();
    pending.add(root);
    while (false == pending.isEmpty()) {
      Parser parser = pending.poll();
      if (nodes.containsKey(parser)) {
        continue;
      }
      if (nodes.size() >= MAX_NODES) {
        return FirstSet.UNKNOWN;
      }
      Node node = new Node(parser);
      nodes.put(parser, node);
      ordered.add(node);
      for (Parser child : node.children) {
        if (false == nodes.containsKey(child)) {
          pending.add(child);
        }
      }
    }
    boolean changed = true;
    for (int round = 0; changed; round++) {
      if (round > MAX_ROUNDS) {
        for (Node node : ordered) {
          node.value = FirstSet.UNKNOWN;
        }
        break;
      }
      changed = false;
      for (Node node : ordered) {
        changed |= node.recompute(nodes);
      }
    }
    for (Node node : ordered) {
      publish(node.parser, node.value);
    }
    return nodes.get(root).value;
  }

  private enum Kind {
    /** Not modelled: contributes {@link FirstSet#UNKNOWN} whatever its children do. */
    OPAQUE,
    /** Value known without looking at children. */
    LEAF,
    /** Ordered or longest choice: union of the alternatives. */
    CHOICE,
    /** Sequence: union of the nullable prefix. */
    CHAIN,
    /** Repetition: body (and terminator) with the repetition's own lower bound. */
    OCCURS
  }

  private static final class Node {

    private final Parser parser;
    private final Kind kind;
    private final List<Parser> children;
    private final boolean occursNullable;
    private FirstSet value;

    Node(Parser parser) {
      this.parser = parser;
      List<Parser> found = List.of();
      Kind classified;
      boolean nullableRepetition = false;
      FirstSet leaf = null;
      if (parser instanceof PropagatableSource || parser instanceof WordParser
          || parser instanceof SingleCharacterParser) {
        classified = Kind.LEAF;
        leaf = leafOf(parser);
      } else if (parser instanceof Occurs occurs) {
        List<Parser> body = occursChildren(occurs);
        if (body.isEmpty()) {
          classified = Kind.OPAQUE;
        } else {
          classified = Kind.OCCURS;
          found = body;
          nullableRepetition = occurs.min() <= 0;
        }
      } else if (parser instanceof ChoiceInterface || parser instanceof ChainInterface) {
        List<Parser> declared = childrenOf(parser);
        if (declared.isEmpty()) {
          classified = Kind.OPAQUE;
        } else {
          classified = parser instanceof ChoiceInterface ? Kind.CHOICE : Kind.CHAIN;
          found = declared;
        }
      } else {
        classified = Kind.OPAQUE;
      }
      this.kind = classified;
      this.children = found;
      this.occursNullable = nullableRepetition;
      this.value = switch (classified) {
        case OPAQUE -> FirstSet.UNKNOWN;
        case LEAF -> leaf;
        default -> FirstSet.EMPTY;
      };
    }

    boolean recompute(Map<Parser, Node> nodes) {
      FirstSet computed = switch (kind) {
        case OPAQUE, LEAF -> value;
        case CHOICE -> choice(nodes);
        case CHAIN -> chain(nodes);
        case OCCURS -> occurs(nodes);
      };
      if (computed.equals(value) || sameAnswer(computed, value)) {
        return false;
      }
      value = computed;
      return true;
    }

    private FirstSet choice(Map<Parser, Node> nodes) {
      FirstSet result = FirstSet.EMPTY;
      for (Parser child : children) {
        result = result.union(nodes.get(child).value);
        if (result.isUnknown()) {
          return FirstSet.UNKNOWN;
        }
      }
      return result;
    }

    private FirstSet chain(Map<Parser, Node> nodes) {
      FirstSet result = FirstSet.EMPTY;
      for (Parser child : children) {
        FirstSet childValue = nodes.get(child).value;
        if (childValue.isUnknown()) {
          return FirstSet.UNKNOWN;
        }
        result = result.unionStarts(childValue);
        if (false == childValue.isNullable()) {
          return result;
        }
      }
      return result.asNullable();
    }

    private FirstSet occurs(Map<Parser, Node> nodes) {
      FirstSet result = FirstSet.EMPTY;
      boolean bodyNullable = false;
      for (int index = 0; index < children.size(); index++) {
        FirstSet childValue = nodes.get(children.get(index)).value;
        if (childValue.isUnknown()) {
          return FirstSet.UNKNOWN;
        }
        result = result.unionStarts(childValue);
        if (index == 0) {
          bodyNullable = childValue.isNullable();
        }
      }
      return occursNullable || bodyNullable ? result.asNullable() : result;
    }
  }

  /**
   * Two sets answer the same questions when they agree on every code point and on nullability, so
   * the fixed point can stop even though the objects differ.
   */
  private static boolean sameAnswer(FirstSet computed, FirstSet current) {
    if (computed.isUnknown() || current.isUnknown()) {
      return computed.isUnknown() && current.isUnknown();
    }
    if (computed.isNullable() != current.isNullable()) {
      return false;
    }
    for (int codePoint = 0; codePoint < 128; codePoint++) {
      if (computed.mayStartWith(codePoint) != current.mayStartWith(codePoint)) {
        return false;
      }
    }
    return computed.mayStartWith(0x10000) == current.mayStartWith(0x10000);
  }

  /** The body first, then the terminator: a repetition can also end by matching its terminator. */
  private static List<Parser> occursChildren(Occurs occurs) {
    List<Parser> children = childrenOf(occurs);
    if (children.isEmpty()) {
      return List.of();
    }
    List<Parser> result = new ArrayList<>(2);
    result.add(children.get(0));
    occurs.getTerminator().ifPresent(result::add);
    return result;
  }

  private static List<Parser> childrenOf(Parser parser) {
    try {
      List<Parser> children = parser.getChildren();
      if (children == null || children.isEmpty()) {
        return List.of();
      }
      List<Parser> copy = new ArrayList<>(children.size());
      for (Parser child : children) {
        if (child == null) {
          return List.of();
        }
        copy.add(child);
      }
      return copy;
    } catch (RuntimeException notAnalyzable) {
      return List.of();
    }
  }

  private static FirstSet leafOf(Parser parser) {
    if (parser instanceof PropagatableSource) {
      // An inverted sub-tree matches the complement of what its terminals declare.
      return FirstSet.UNKNOWN;
    }
    if (parser instanceof WordParser wordParser) {
      return wordOf(wordParser);
    }
    if (parser instanceof SingleCharacterParser characterParser) {
      return characterOf(characterParser);
    }
    return FirstSet.UNKNOWN;
  }

  /** A zero length word matches the empty input; otherwise only its first code point can start it. */
  private static FirstSet wordOf(WordParser wordParser) {
    int length = wordParser.word.codePointLength().value();
    if (length == 0) {
      return FirstSet.EPSILON;
    }
    int first = wordParser.word.codePointValueAt(0);
    if (first < 0) {
      return FirstSet.UNKNOWN;
    }
    if (false == wordParser.ignoreCase) {
      return FirstSet.ofCodePoints(first);
    }
    return FirstSet.ofCodePoints(first, Character.toUpperCase(first), Character.toLowerCase(first));
  }

  /**
   * {@code isMatch(int)} is a pure predicate on a code point — the diagnostics code already probes
   * it to render a terminal's expected text — so the ASCII range can be read off exactly. Anything
   * above ASCII stays possible, which costs nothing for grammars that discriminate on ASCII.
   */
  private static FirstSet characterOf(SingleCharacterParser parser) {
    int[] matched = new int[128];
    int count = 0;
    try {
      for (int codePoint = 0; codePoint < 128; codePoint++) {
        if (parser.isMatch(codePoint)) {
          matched[count++] = codePoint;
        }
      }
    } catch (RuntimeException notProbeable) {
      return FirstSet.UNKNOWN;
    }
    int[] codePoints = new int[count];
    System.arraycopy(matched, 0, codePoints, 0, count);
    return FirstSet.ofCodePoints(codePoints).withNonAscii();
  }
}
