package org.unlaxer.dsl.runtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.unlaxer.CodePointLength;
import org.unlaxer.Source;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.TerminalSymbol;
import org.unlaxer.parser.elementary.AbstractTokenParser;

/**
 * 正規表現パターンに基づいてトークンをマッチするパーサー基底クラス。
 *
 * <p>UBNF の {@code REGEX('pattern')} キーワードから生成されるパーサークラスの継承元。
 * 指定された TokenKind の現在位置に正規表現をアンカリングして適用し、
 * マッチした Unicode コードポイント数のソースを消費する。</p>
 *
 * <p>反転指定では、正規表現が不一致のときに次の1コードポイントを返す。
 * ゼロ幅の正規一致と入力末尾は非空トークンを生成しない。</p>
 *
 * <pre>
 * // UBNF での使い方:
 * token IDENTIFIER = REGEX('[a-zA-Z_][a-zA-Z0-9_]*')
 * </pre>
 */
public abstract class RegexTokenParser extends AbstractTokenParser implements TerminalSymbol {

    private static final long serialVersionUID = 1L;

    private final Pattern pattern;

    protected RegexTokenParser(String regex) {
        this.pattern = Pattern.compile(regex);
    }

    @Override
    public Token getToken(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        Source source = parseContext.getSource();
        int position = parseContext.getPosition(tokenKind).value();
        String fullSource = source.sourceAsString();

        if (position >= source.codePointLength().value()) {
            return Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
        }

        int stringPosition = fullSource.offsetByCodePoints(0, position);
        String remaining = fullSource.substring(stringPosition);
        Matcher matcher = pattern.matcher(remaining);

        boolean matched = matcher.lookingAt();
        if (matched ^ invertMatch) {
            // An unmatched pattern has no group/end; inverted tokens use the same
            // one-scalar progress rule as SingleCharacterParser instead of guessing a width.
            int len = invertMatch ? 1 : remaining.codePointCount(0, matcher.end());
            if (len == 0) {
                return Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
            }
            Source peeked = parseContext.peek(tokenKind, new CodePointLength(len));
            return peeked.isPresent()
                ? new Token(tokenKind, peeked, this)
                : Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
        }
        return Token.empty(tokenKind, parseContext.getCursor(TokenKind.consumed), this);
    }

    public Optional<String> expectedDisplayText() {
        return Optional.of("REGEX('" + pattern.pattern() + "')");
    }
}
