package org.unlaxer.parser.combinator;

import java.util.List;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.*;

/** Transparent language entry boundary: UNKNOWN cannot become an outer alternative's success. */
public final class NameResolutionScope extends ConstructedAbstractParser implements HasChildrenParser, DiagnosticsAgnostic {
    private static final long serialVersionUID = 1L;
    private final List<NameSnapshot.Requirement> requirements;
    public NameResolutionScope(Parser child, List<NameSnapshot.Requirement> requirements) {
        super(new Parsers(child));
        this.requirements = List.copyOf(requirements);
    }
    @Override public ChildOccurs getChildOccurs() {return ChildOccurs.single;}
    @Override public HasChildrenParser createWith(Parsers children) {
        if(children.size()!=1) throw new IllegalArgumentException("name entry needs one child");
        return new NameResolutionScope(children.get(0),requirements);
    }
    @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
        context.startParse(this, context, kind, invert);
        context.begin(this);
        var frame = NameResolution.begin(context, requirements);
        try {
            Parsed parsed = frame.failure().isEmpty() ? getChild().parse(context, kind, invert) : Parsed.FAILED;
            if (parsed.isFailed() || frame.failure().isPresent()) {
                context.rollback(this);
                context.endParse(this, Parsed.FAILED, context, kind, invert);
                return Parsed.FAILED;
            }
            // This wrapper does not implement CollectingParser; the original rule root is retained.
            context.commit(this, kind);
            context.endParse(this, parsed, context, kind, invert);
            return parsed;
        } finally { NameResolution.end(context, frame); }
    }
}
