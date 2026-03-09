package org.unlaxer.parser.combinator;

import java.util.List;

import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.Transaction.AdditionalPreCommitAction;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/**
 * aka interleave in RelaxNG
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
				Parsed parsed = children.get(i).parse(parseContext, tokenKind, invertMatch);
				if (parsed.isSucceeded()) {
					remain--;
					comsumeds[i] = true;
					determineds.add(children.get(i));
					break;
				}
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
	}
	
	public static class NonOrderedCommitAction implements AdditionalPreCommitAction{

		Parsers determineds;

		public NonOrderedCommitAction(Parsers determineds) {
			super();
			this.determineds = determineds;
		}

		@Override
		public void effect(Parser parser, ParseContext parseContext) {

			if (false == determineds.isEmpty() && parser instanceof NonOrdered) {
				parseContext.orderedParsersByNonOrdered.put((NonOrdered)parser, determineds);
			}
		}
	}

	@Override
	public HasChildrenParser createWith(Parsers children) {
		return new NonOrdered(children);
	}
}