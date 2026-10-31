package org.unlaxer.parser.combinator;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.DiagnosticsAgnostic;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.ErrorMessageParser;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/** CST-transparent postcondition: a false condition rolls the successful child back. Callbacks are pure, so diagnostic retries may rerun them. */
public class SemanticPredicateParser extends ConstructedAbstractParser implements HasChildrenParser, DiagnosticsAgnostic {
    private static final long serialVersionUID = 1L;
    private final String predicate;
    public SemanticPredicateParser(Parser child, String predicate) {
        super(new Parsers(child));
        SemanticPredicateBindings.requireName(predicate);
        this.predicate = predicate;
    }
    @Override public ChildOccurs getChildOccurs() { return ChildOccurs.single; }
    @Override public HasChildrenParser createWith(Parsers children) {
        if (children.size() != 1) throw new IllegalArgumentException("semantic predicate needs one child");
        return new SemanticPredicateParser(children.get(0), predicate);
    }
    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        context.enterParserCall();
        try {
            context.startParse(this, context, kind, invert);
            context.begin(this);
            int before = (kind.isConsumed() ? context.getConsumedPosition() : context.getMatchedPosition()).value();
            Parsed parsed = getChild().parse(context, kind, invert);
            boolean accepted = parsed.isSucceeded();
            if (accepted) {
                // getConsumed() empties MatchOnly/virtual results; the root token keeps the matched source.
                accepted = SemanticPredicateBindings.test(context, predicate, parsed.getRootToken(false).source, before);
                if (!accepted && SemanticPredicateBindings.failure(context).isEmpty())
                    ErrorMessageParser.expected("predicate " + predicate).parse(context, kind, invert);
            }
            if (!accepted) {
                context.rollback(this);
                context.endParse(this, Parsed.FAILED, context, kind, invert);
                return Parsed.FAILED;
            }
            context.commit(this, kind);
            context.endParse(this, parsed, context, kind, invert);
            return parsed;
        } catch (RuntimeException | Error failure) {
            context.failParserCall(failure);
            throw failure;
        } finally {
            context.leaveParserCall();
        }
    }
}
