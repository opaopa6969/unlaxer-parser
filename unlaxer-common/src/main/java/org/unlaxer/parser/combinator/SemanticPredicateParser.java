package org.unlaxer.parser.combinator;

import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.ErrorMessageParser;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/** CST-transparent postcondition: a false condition rolls the successful child back. */
public class SemanticPredicateParser extends ConstructedAbstractParser implements HasChildrenParser {
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
            Parsed parsed = getChild().parse(context, kind, invert);
            boolean accepted = parsed.isSucceeded();
            if (accepted) {
                var source = parsed.getConsumed().source;
                String text = source.sourceAsString();
                int start = source.offsetFromRoot().value();
                accepted = SemanticPredicateBindings.test(context, predicate,
                    new SemanticPredicateBindings.Candidate(text, start, start + text.codePointCount(0, text.length())));
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
