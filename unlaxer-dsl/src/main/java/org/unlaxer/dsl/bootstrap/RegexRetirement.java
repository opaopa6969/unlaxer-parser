package org.unlaxer.dsl.bootstrap;

import org.unlaxer.Token;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;

/** Migration boundary for the removed runtime regex adapter. No regex is compiled here. */
public final class RegexRetirement {
    public static final String CODE = "E-REGEX-RETIRED";
    public static final String MESSAGE =
            CODE
                    + ": REGEX execution was removed; use token NAME ::= expression; (see"
                    + " docs/regex-retirement.md)";

    private RegexRetirement() {}

    public static boolean legacyClass(String name) {
        return name.equals("RegexTokenParser")
                || name.equals("org.unlaxer.dsl.runtime.RegexTokenParser");
    }

    public static boolean retired(TokenDecl token) {
        return token instanceof TokenDecl.Regex
                || token instanceof TokenDecl.Simple simple && legacyClass(simple.parserClass());
    }

    /** Direct AST callers have no source snapshot; never invent a source position. */
    public static void requireValid(GrammarDecl grammar) {
        for (TokenDecl token : grammar.tokens()) {
            if (retired(token))
                throw new IllegalArgumentException(MESSAGE + " (token " + token.name() + ")");
        }
    }

    public static final class MigrationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int start;
        private final int end;

        MigrationException(int start, int end, int line, int column) {
            super(
                    MESSAGE
                            + " at code points ["
                            + start
                            + ","
                            + end
                            + "), line "
                            + line
                            + ", column "
                            + column);
            this.start = start;
            this.end = end;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }
    }

    static MigrationException at(Token token, int length) {
        String text = token.source.toString();
        int whitespace = text.length() - text.stripLeading().length();
        String source = token.source.root().toString();
        int start = token.source.offsetFromRoot().value() + text.codePointCount(0, whitespace);
        int line = 1, column = 1;
        boolean cr = false;
        for (int index = 0, point = 0; point < start; point++) {
            int cp = source.codePointAt(index);
            index += Character.charCount(cp);
            if (cp == '\r') {
                line++;
                column = 1;
            } else if (cp == '\n' && cr) {
            } else if (cp == '\n') {
                line++;
                column = 1;
            } else column++;
            cr = cp == '\r';
        }
        return new MigrationException(start, start + length, line, column);
    }
}
