package org.unlaxer.parser.combinator;

import java.util.ArrayList;
import java.util.List;

import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ErrorMessageParser;

/** A recovered input region, measured in half-open Unicode code-point offsets. */
public record RecoveryDiagnostic(int start, int end, String message) {
    /** Reads only committed tokens, so discarded alternatives and outer rollbacks are excluded. */
    public static List<RecoveryDiagnostic> from(ParseContext context) {
        List<RecoveryDiagnostic> result = new ArrayList<>();
        for (Token token : context.getCurrent().getTokens()) collect(token, result);
        return List.copyOf(result);
    }

    /** Reads recovery markers from a committed CST, including its original children. */
    public static List<RecoveryDiagnostic> from(Token token) {
        List<RecoveryDiagnostic> result = new ArrayList<>();
        if (token != null) collect(token, result);
        return List.copyOf(result);
    }

    private static void collect(Token token, List<RecoveryDiagnostic> result) {
        for (Token node : token.flatten(Token.ScanDirection.Depth, Token.ChildrenKind.original)) {
            if (node.parser instanceof Marker marker && node.source != null) {
                int start = node.source.offsetFromRoot().value();
                result.add(new RecoveryDiagnostic(start, start + node.source.codePointLength().value(), marker.get()));
            }
        }
    }

    /** Distinguishes recovery CST markers from ordinary ERROR elements and user error messages. */
    public static final class Marker extends ErrorMessageParser {
        private static final long serialVersionUID = 1L;

        public Marker(String message) {
            super(message);
        }
    }
}
