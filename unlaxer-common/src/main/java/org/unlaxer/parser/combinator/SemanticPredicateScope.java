package org.unlaxer.parser.combinator;

import java.util.List;
import org.unlaxer.Parsed;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.ConstructedAbstractParser;
import org.unlaxer.parser.HasChildrenParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;

/** Validate the entry's immutable registry before alternatives can hide missing bindings. */
public final class SemanticPredicateScope extends ConstructedAbstractParser implements HasChildrenParser {
    private static final long serialVersionUID = 1L;
    private final List<String> requirements;
    public SemanticPredicateScope(Parser child, List<String> requirements) {
        super(new Parsers(child));
        if (requirements.size() > 64) throw new IllegalArgumentException("at most 64 semantic predicates");
        requirements.forEach(SemanticPredicateBindings::requireName);
        this.requirements = List.copyOf(requirements);
    }
    @Override public ChildOccurs getChildOccurs() { return ChildOccurs.single; }
    @Override public HasChildrenParser createWith(Parsers children) {
        if (children.size() != 1) throw new IllegalArgumentException("semantic predicate scope needs one child");
        return new SemanticPredicateScope(children.get(0), requirements);
    }
    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        context.enterParserCall();
        try {
            context.startParse(this, context, kind, invert);
            context.begin(this);
            var frame = SemanticPredicateBindings.begin(context, requirements);
            try {
                Parsed parsed = frame.failed() ? Parsed.FAILED : getChild().parse(context, kind, invert);
                if (parsed.isFailed() || frame.failed()) {
                    context.rollback(this);
                    context.endParse(this, Parsed.FAILED, context, kind, invert);
                    return Parsed.FAILED;
                }
                context.commit(this, kind);
                context.endParse(this, parsed, context, kind, invert);
                return parsed;
            } finally {
                SemanticPredicateBindings.end(context, frame);
            }
        } catch (RuntimeException | Error failure) {
            context.failParserCall(failure);
            throw failure;
        } finally {
            context.leaveParserCall();
        }
    }
}
