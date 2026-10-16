package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Name;
import org.unlaxer.ParserCursor;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.Transaction.AdditionalPreCommitAction;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/**
 * Greedily matches each declared child occurrence once in input order.
 * The first unused successful child wins; later failure does not retry earlier selections.
 * Failed attempts restore registered state, tokens, and cursors. Empty children succeed;
 * nullable children are selected once. This parser does not add whitespace handling.
 * This is distinct from the UBNF trivia setting named interleave.
 */
public class NonOrdered extends ConstructedCombinatorParser {

	private static final long serialVersionUID = 5425945419472077891L;

	public NonOrdered(Name name, Parsers parsers) {
		super(name, parsers);
	}

	public NonOrdered(Parsers parsers) {
		super(parsers);
	}

	@SafeVarargs
	public NonOrdered(Name name, Parser... parsers) {
		super(name, parsers);
	}

	@SafeVarargs
	public NonOrdered(Parser... parsers) {
		super(parsers);
	}
	
	 @SafeVarargs
	 public NonOrdered(Class<? extends Parser>... parsers) {
	    super(parsers);
	  }


	public NonOrdered() {
		super(new Parser[] {});
	}

	public NonOrdered(Name name) {
		super(name);
	}

	@Override
	public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        parseContext.enterParserCall();
        try {


		parseContext.startParse(this, parseContext, tokenKind, invertMatch);

		parseContext.begin(this);

		List<Parser> children = getChildren();
		
		Parsers determineds = new Parsers();

		int size = children.size();
		int remain = size;
		boolean[] comsumeds = new boolean[size];

		while (remain != 0) {

			int start = remain;

			for (int i = 0; i < size; i++) {
				if (comsumeds[i]) {
					continue;
				}
				// Isolate failed custom children without adding a CST wrapper.
				ParserCursor candidateCursor = new ParserCursor(parseContext.getCurrent().getParserCursor(), false);
				parseContext.begin(CANDIDATE_BOUNDARY);
				// A candidate boundary must preserve a preceding match-only child.
				parseContext.getCurrent().setCursor(candidateCursor);
				Parsed parsed = children.get(i).parse(parseContext, tokenKind, invertMatch);
				if (parsed.isSucceeded()) {
					// Chain changes the current frame policy before its own begin. Preserve
					// that successful change for siblings, while failed candidates isolate it.
					parseContext.getPrevious().orElseThrow().setResetMatchedWithConsumed(
							parseContext.getCurrent().isResetMatchedWithConsumed());
					parseContext.commit(CANDIDATE_BOUNDARY, tokenKind);
					remain--;
					comsumeds[i] = true;
					determineds.add(children.get(i));
					break;
				}
				parseContext.rollback(CANDIDATE_BOUNDARY);
			}
			if (start == remain) {
				parseContext.rollback(this);
				parseContext.endParse(this, Parsed.FAILED , parseContext, tokenKind, invertMatch);

				return Parsed.FAILED;
			}
		}

		Parsed committed = new Parsed(
				parseContext.commit(this, tokenKind , new NonOrderedCommitAction(determineds)));
		
		parseContext.endParse(this, committed, parseContext, tokenKind, invertMatch);
		return committed;

        } catch (RuntimeException | Error failure) {
            parseContext.failParserCall(failure);
            throw failure;
        } finally {
            parseContext.leaveParserCall();
        }
    }
	
    private static final Parser CANDIDATE_BOUNDARY = new CandidateBoundary();

    /** Transaction owner only; deliberately not a CollectingParser or a grammar child. */
    private static final class CandidateBoundary extends NoneChildParser {
        private static final long serialVersionUID = 1L;
        @Override public Parser createParser() { return this; }
        @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
            throw new UnsupportedOperationException("internal NonOrdered transaction boundary");
        }
    }

	public static class NonOrderedCommitAction implements AdditionalPreCommitAction{

		Parsers determineds;

		public NonOrderedCommitAction(Parsers determineds) {
			super();
			this.determineds = determineds;
		}

		@Override
		public void effect(Parser parser, ParseContext parseContext) {

			if (false == determineds.isEmpty() && parser instanceof NonOrdered nonOrdered) {
				parseContext.order(nonOrdered, determineds);
			}
		}
	}

	@Override
	public HasChildrenParser createWith(Parsers children) {
		return new NonOrdered(children);
	}
}
