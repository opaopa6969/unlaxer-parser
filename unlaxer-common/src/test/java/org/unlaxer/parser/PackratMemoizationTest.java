package org.unlaxer.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.WordParser;

/**
 * Opt-in packrat failure memoization (issue #40).
 *
 * <p>The grammar below reproduces the exponential backtracking that the real tinyexpression P4
 * grammar triggers (issue opaopa6969/tinyexpression#19): two alternatives ({@code A}, {@code B})
 * both parse the same recursive {@code Inner} before diverging on a trailing terminator, so when
 * {@code A} fails {@code B} re-parses {@code Inner} from scratch — and {@code Inner} recurses,
 * giving ~2^depth work on input that ultimately fails.
 *
 * <pre>
 *   Expr  ::= A | B
 *   A     ::= Inner '!'
 *   B     ::= Inner '?'
 *   Inner ::= '(' Expr ')' | 'x'
 * </pre>
 *
 * Rules are {@link LazyChain}/{@link LazyChoice} (the same shape the code generator emits), so they
 * exercise the {@code ChainInterface}/{@code ChoiceInterface} memo hook. With memoization off the
 * deep failing input is intractable; with it on, each {@code (rule, position)} failure is cached so
 * the parse collapses to linear.
 */
public class PackratMemoizationTest {

	public static class Expr extends LazyChoice {
		private static final long serialVersionUID = 1L;
		@Override public Parsers getLazyParsers() {
			return new Parsers(Parser.get(A.class), Parser.get(B.class));
		}
	}

	public static class A extends LazyChain {
		private static final long serialVersionUID = 1L;
		@Override public Parsers getLazyParsers() {
			return new Parsers(Parser.get(Inner.class), new WordParser("!"));
		}
	}

	public static class B extends LazyChain {
		private static final long serialVersionUID = 1L;
		@Override public Parsers getLazyParsers() {
			return new Parsers(Parser.get(Inner.class), new WordParser("?"));
		}
	}

	public static class Inner extends LazyChoice {
		private static final long serialVersionUID = 1L;
		@Override public Parsers getLazyParsers() {
			return new Parsers(Parser.get(Paren.class), new WordParser("x"));
		}
	}

	public static class Paren extends LazyChain {
		private static final long serialVersionUID = 1L;
		@Override public Parsers getLazyParsers() {
			return new Parsers(new WordParser("("), Parser.get(Expr.class), new WordParser(")"));
		}
	}

	private static String nested(int depth, String core) {
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < depth; index++) {
			builder.append('(');
		}
		builder.append(core);
		for (int index = 0; index < depth; index++) {
			builder.append(')');
		}
		return builder.toString();
	}

	private Parsed parse(String source, boolean memoize) {
		Parser parser = Parser.get(Expr.class);
		ParseContext parseContext = memoize
			? new ParseContext(StringSource.createRootSource(source), ParseContext.memoize())
			: new ParseContext(StringSource.createRootSource(source));
		try (parseContext) {
			return parser.parse(parseContext);
		}
	}

	/** Memoization must not change parse outcomes. Succeeding and failing inputs, on and off. */
	@Test
	public void memoizationPreservesParseResults() {
		// A valid Expr: Inner='(x!)' wrapped, then trailing '!'. nested(d,"x!") builds (^d x! )^d,
		// which parses as Inner='(...(x!)...)' followed by the outer '!' only at depth where a
		// terminator follows; here we use simple known cases.
		String[] succeeding = { "x!", "x?", "(x!)!", "(x?)?", "((x!)!)!" };
		String[] failing = { "x", "(x)", "((x))", "(x!", "y!" };

		for (String source : succeeding) {
			Parsed off = parse(source, false);
			Parsed on = parse(source, true);
			assertEquals("succeed parity for '" + source + "'", off.isSucceeded(), on.isSucceeded());
			assertTrue("expected success for '" + source + "'", on.isSucceeded());
		}
		for (String source : failing) {
			Parsed off = parse(source, false);
			Parsed on = parse(source, true);
			assertEquals("fail parity for '" + source + "'", off.isSucceeded(), on.isSucceeded());
			assertFalse("expected failure for '" + source + "'", on.isSucceeded());
		}
	}

	/**
	 * Deeply nested failing input. Off would be ~2^depth (intractable); on collapses to linear.
	 * The wall-clock bound proves the exponential is gone — if memoization regressed, this hangs.
	 */
	@Test(timeout = 15000)
	public void memoizationCollapsesExponentialBacktracking() {
		String source = nested(40, "x"); // 40 open parens, 'x', 40 close — no terminator -> fails
		long start = System.nanoTime();
		Parsed parsed = parse(source, true);
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
		assertFalse("deeply nested input without a terminator must fail to parse", parsed.isSucceeded());
		assertTrue("memoized parse should be fast (was " + elapsedMillis + "ms)", elapsedMillis < 5000);
	}
}
