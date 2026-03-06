package org.unlaxer.dsl.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.Annotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.InterleaveAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.KeyValuePair;
import org.unlaxer.dsl.bootstrap.UBNFAST.LeftAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.PrecedenceAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RightAssocAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ScopeTreeAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.SettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.SimpleAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.TypeofElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFAST.WhitespaceAnnotation;
import org.unlaxer.parser.Parser;

/**
 * UBNF パースツリー（Token）を UBNFAST ノードに変換するマッパー。
 *
 * 使用方法:
 * <pre>
 *   UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
 * </pre>
 */
public class UBNFMapper {

    private UBNFMapper() {}

    // =========================================================================
    // エントリーポイント
    // =========================================================================

    /**
     * UBNF ソース文字列をパースして AST に変換する。
     *
     * @param source UBNF ファイルの文字列
     * @return パース＋変換された UBNFFile AST ノード
     * @throws IllegalArgumentException パースに失敗した場合
     */
    public static UBNFFile parse(String source) {
        StringSource stringSource = StringSource.createRootSource(source);
        try (ParseContext context = new ParseContext(stringSource)) {
            Parser rootParser = UBNFParsers.getRootParser();
            Parsed parsed = rootParser.parse(context);
            if (false == parsed.isSucceeded()) {
                throw new IllegalArgumentException("UBNF パース失敗: " + source.substring(0, Math.min(80, source.length())));
            }
            return toUBNFFile(parsed.getRootToken());
        }
    }

    // =========================================================================
    // ファイルレベル変換
    // =========================================================================

    static UBNFFile toUBNFFile(Token token) {
        List<GrammarDecl> grammars = findDescendants(token, UBNFParsers.GrammarDeclParser.class)
            .stream()
            .map(UBNFMapper::toGrammarDecl)
            .toList();
        return new UBNFFile(grammars);
    }

    static GrammarDecl toGrammarDecl(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String name = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();

        List<GlobalSetting> settings = findDescendants(token, UBNFParsers.GlobalSettingParser.class)
            .stream()
            .map(UBNFMapper::toGlobalSetting)
            .toList();

        List<TokenDecl> tokens = findDescendants(token, UBNFParsers.TokenDeclParser.class)
            .stream()
            .map(UBNFMapper::toTokenDecl)
            .toList();

        List<RuleDecl> rules = findDescendants(token, UBNFParsers.RuleDeclParser.class)
            .stream()
            .map(UBNFMapper::toRuleDecl)
            .toList();

        return new GrammarDecl(name, settings, tokens, rules);
    }

    // =========================================================================
    // グローバル設定変換
    // =========================================================================

    static GlobalSetting toGlobalSetting(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String key = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
        SettingValue value = toSettingValue(token);
        return new GlobalSetting(key, value);
    }

    static SettingValue toSettingValue(Token token) {
        List<Token> blockTokens = findDescendants(token, UBNFParsers.BlockSettingValueParser.class);
        if (false == blockTokens.isEmpty()) {
            return toBlockSettingValue(blockTokens.get(0));
        }
        List<Token> stringTokens = findDescendants(token, UBNFParsers.StringSettingValueParser.class);
        if (false == stringTokens.isEmpty()) {
            return toStringSettingValue(stringTokens.get(0));
        }
        return new StringSettingValue("");
    }

    static StringSettingValue toStringSettingValue(Token token) {
        List<Token> dottedTokens = findDescendants(token, UBNFParsers.DottedIdentifierParser.class);
        String value = dottedTokens.isEmpty() ? "" : dottedTokens.get(0).source.toString().trim();
        return new StringSettingValue(value);
    }

    static BlockSettingValue toBlockSettingValue(Token token) {
        List<KeyValuePair> entries = findDescendants(token, UBNFParsers.KeyValuePairParser.class)
            .stream()
            .map(UBNFMapper::toKeyValuePair)
            .toList();
        return new BlockSettingValue(entries);
    }

    static KeyValuePair toKeyValuePair(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        List<Token> strings = findDescendants(token, org.unlaxer.parser.elementary.SingleQuotedParser.class);
        String key = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
        String value = strings.isEmpty() ? "" : stripQuotes(strings.get(0).source.toString().trim());
        return new KeyValuePair(key, value);
    }

    // =========================================================================
    // トークン宣言変換
    // =========================================================================

    static TokenDecl toTokenDecl(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String name = identifiers.size() > 0 ? identifiers.get(0).source.toString().trim() : "";

        // UNTIL('terminator') 形式のチェック
        List<Token> untilTokens = findDescendants(token, UBNFParsers.UntilExpressionParser.class);
        if (!untilTokens.isEmpty()) {
            List<Token> quotedTokens = findDescendants(
                untilTokens.get(0), org.unlaxer.parser.elementary.SingleQuotedParser.class);
            String terminator = quotedTokens.isEmpty()
                ? ""
                : stripQuotes(quotedTokens.get(0).source.toString().trim());
            return new TokenDecl.Until(name, terminator);
        }

        // QualifiedClassNameParser ノードからドット区切りクラス名を再構築する。
        // IDENTIFIER { '.' IDENTIFIER } の各 Identifier を "." で結合する。
        List<Token> qualifiedTokens = findDescendants(token, UBNFParsers.QualifiedClassNameParser.class);
        String parserClass;
        if (!qualifiedTokens.isEmpty()) {
            List<Token> segments = findDescendants(qualifiedTokens.get(0), UBNFParsers.IdentifierParser.class);
            parserClass = segments.stream()
                .map(t -> firstWord(t.source.toString()))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining("."));
        } else {
            // フォールバック: 旧形式（単純 IDENTIFIER）
            parserClass = identifiers.size() > 1
                ? firstWord(identifiers.get(1).source.toString())
                : "";
        }
        return new TokenDecl.Simple(name, parserClass);
    }

    /** 文字列から先頭の空白を除き、最初の空白文字より前の部分だけを返す。 */
    private static String firstWord(String text) {
        String trimmed = text.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                return trimmed.substring(0, i);
            }
        }
        return trimmed;
    }

    // =========================================================================
    // ルール宣言変換
    // =========================================================================

    static RuleDecl toRuleDecl(Token token) {
        List<Annotation> annotations = collectAnnotations(token);

        // アノテーション内の identifier を誤検出しないよう直接子だけを見る
        String name = token.filteredChildren.stream()
            .filter(t -> t.parser.getClass() == UBNFParsers.IdentifierParser.class)
            .map(t -> t.source.toString().trim())
            .findFirst()
            .orElse("");

        List<Token> bodyTokens = findDescendants(token, UBNFParsers.ChoiceBodyParser.class);
        RuleBody body = bodyTokens.isEmpty()
            ? new SequenceBody(List.of())
            : toChoiceBody(bodyTokens.get(0));

        return new RuleDecl(annotations, name, body);
    }

    // =========================================================================
    // アノテーション変換
    // =========================================================================

    static List<Annotation> collectAnnotations(Token token) {
        List<Annotation> result = new ArrayList<>();
        for (Token child : token.filteredChildren) {
            if (child.parser.getClass() == UBNFParsers.RootAnnotationParser.class) {
                result.add(new RootAnnotation());
            } else if (child.parser.getClass() == UBNFParsers.MappingAnnotationParser.class) {
                result.add(toMappingAnnotation(child));
            } else if (child.parser.getClass() == UBNFParsers.WhitespaceAnnotationParser.class) {
                result.add(toWhitespaceAnnotation(child));
            } else if (child.parser.getClass() == UBNFParsers.InterleaveAnnotationParser.class) {
                result.add(toInterleaveAnnotation(child));
            } else if (child.parser.getClass() == UBNFParsers.BackrefAnnotationParser.class) {
                result.add(toBackrefAnnotation(child));
            } else if (child.parser.getClass() == UBNFParsers.ScopeTreeAnnotationParser.class) {
                result.add(toScopeTreeAnnotation(child));
            } else if (child.parser.getClass() == UBNFParsers.LeftAssocAnnotationParser.class) {
                result.add(new LeftAssocAnnotation());
            } else if (child.parser.getClass() == UBNFParsers.RightAssocAnnotationParser.class) {
                result.add(new RightAssocAnnotation());
            } else if (child.parser.getClass() == UBNFParsers.PrecedenceAnnotationParser.class) {
                result.add(toPrecedenceAnnotation(child));
            } else if (child.parser.getClass() == UBNFParsers.SimpleAnnotationParser.class) {
                result.add(toSimpleAnnotation(child));
            } else {
                // ZeroOrMore コンテナなど → 中を再帰検索
                result.addAll(collectAnnotations(child));
            }
        }
        return result;
    }

    static MappingAnnotation toMappingAnnotation(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String className = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();

        List<String> paramNames = new ArrayList<>();
        List<Token> paramTokens = findDescendants(token, UBNFParsers.ParameterListParser.class);
        if (false == paramTokens.isEmpty()) {
            findDescendants(paramTokens.get(0), UBNFParsers.IdentifierParser.class)
                .stream()
                .map(t -> t.source.toString().trim())
                .forEach(paramNames::add);
        }
        return new MappingAnnotation(className, List.copyOf(paramNames));
    }

    static WhitespaceAnnotation toWhitespaceAnnotation(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        Optional<String> style = identifiers.isEmpty()
            ? Optional.empty()
            : Optional.of(identifiers.get(0).source.toString().trim());
        return new WhitespaceAnnotation(style);
    }

    static InterleaveAnnotation toInterleaveAnnotation(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String profile = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
        return new InterleaveAnnotation(profile);
    }

    static BackrefAnnotation toBackrefAnnotation(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String name = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
        return new BackrefAnnotation(name);
    }

    static ScopeTreeAnnotation toScopeTreeAnnotation(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String mode = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
        return new ScopeTreeAnnotation(mode);
    }

    static SimpleAnnotation toSimpleAnnotation(Token token) {
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        String name = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
        return new SimpleAnnotation(name);
    }

    static PrecedenceAnnotation toPrecedenceAnnotation(Token token) {
        List<Token> numberTokens = findDescendants(token, UBNFParsers.UnsignedIntegerParser.class);
        int level = numberTokens.isEmpty()
            ? 0
            : Integer.parseInt(numberTokens.get(0).source.toString().trim());
        return new PrecedenceAnnotation(level);
    }

    // =========================================================================
    // ルール本体変換
    // =========================================================================

    static ChoiceBody toChoiceBody(Token token) {
        List<Token> sequenceTokens = findDescendants(token, UBNFParsers.SequenceBodyParser.class);
        List<SequenceBody> alternatives = sequenceTokens.stream()
            .map(UBNFMapper::toSequenceBody)
            .toList();
        return new ChoiceBody(alternatives.isEmpty() ? List.of() : alternatives);
    }

    static SequenceBody toSequenceBody(Token token) {
        List<Token> elementTokens = findDescendants(token, UBNFParsers.AnnotatedElementParser.class);
        List<AnnotatedElement> elements = elementTokens.stream()
            .map(UBNFMapper::toAnnotatedElement)
            .toList();
        // Fix the "typeof" capture name issue:
        // If we have an element with captureName="typeof" followed by a GroupElement,
        // this was incorrectly parsed. We need to reconstruct it as a @typeof constraint
        // on the following element.
        elements = fixTypeofCaptureName(elements);
        return new SequenceBody(elements);
    }

    /**
     * Fix the "typeof" capture name issue that occurs when @typeof(...) is parsed as:
     * - Element 1: something with captureName="typeof"
     * - Element 2: GroupElement(containing the identifier)
     * - Element 3: the actual element with @capture
     *
     * We reconstruct this as:
     * - Element: @typeof(identifier) element @capture
     */
    static List<AnnotatedElement> fixTypeofCaptureName(List<AnnotatedElement> elements) {
        List<AnnotatedElement> result = new ArrayList<>();
        int i = 0;
        while (i < elements.size()) {
            AnnotatedElement current = elements.get(i);
            // Check if current has captureName="typeof" and is followed by GroupElement and RuleRefElement
            if (i + 2 < elements.size() &&
                current.captureName().isPresent() &&
                "typeof".equals(current.captureName().get()) &&
                elements.get(i + 1).element() instanceof GroupElement group &&
                elements.get(i + 2).element() instanceof RuleRefElement ref &&
                elements.get(i + 2).captureName().isPresent()) {

                // Extract the identifier from the GroupElement (should be in the body)
                // The groupElement contains the identifier in parentheses
                String identifier = extractIdentifierFromGroup(group);

                // Remove the "typeof" capture name from current element
                AnnotatedElement fixedCurrent = new AnnotatedElement(
                    current.element(),
                    Optional.empty(),  // Remove the "typeof" capture name
                    current.typeofConstraint()
                );
                result.add(fixedCurrent);

                // Create a new element with @typeof constraint on the RuleRefElement
                AnnotatedElement nextElement = elements.get(i + 2);
                AnnotatedElement fixedNext = new AnnotatedElement(
                    nextElement.element(),
                    nextElement.captureName(),
                    Optional.of(new TypeofElement(identifier))  // Add the @typeof constraint
                );
                result.add(fixedNext);

                // Skip the GroupElement since we've incorporated it
                i += 3;
            } else {
                result.add(current);
                i++;
            }
        }
        return result;
    }

    /**
     * Extract the identifier from a GroupElement body.
     * The GroupElement contains something like (identifier) which parses to a GroupElement with a body.
     */
    static String extractIdentifierFromGroup(GroupElement group) {
        // The group.body() contains a RuleBody with the identifier
        // We need to extract the first identifier from it
        RuleBody body = group.body();
        if (body instanceof SequenceBody seq) {
            for (AnnotatedElement ae : seq.elements()) {
                if (ae.element() instanceof RuleRefElement ref) {
                    return ref.name();
                }
            }
        } else if (body instanceof ChoiceBody choice) {
            for (SequenceBody seq : choice.alternatives()) {
                for (AnnotatedElement ae : seq.elements()) {
                    if (ae.element() instanceof RuleRefElement ref) {
                        return ref.name();
                    }
                }
            }
        }
        return "";
    }

    static AnnotatedElement toAnnotatedElement(Token token) {
        AtomicElement baseElement = toAtomicElement(token);

        // Postfix quantifier: '+' → OneOrMoreElement, '?' → OptionalElement
        List<Token> postfixTokens = findDescendants(token, UBNFParsers.PostfixQuantifierParser.class);
        AtomicElement element;
        if (!postfixTokens.isEmpty()) {
            String postfix = postfixTokens.get(0).source.toString().trim();
            RuleBody wrappedBody = wrapElementInSequenceBody(baseElement);
            element = "+".equals(postfix)
                ? new OneOrMoreElement(wrappedBody)
                : new OptionalElement(wrappedBody);
        } else {
            element = baseElement;
        }

        // キャプチャ名: AnnotatedElementParser の filteredChildren から
        // AtSignParser の直後の IdentifierParser を探す
        Optional<String> captureName = findCaptureNameInAnnotatedElement(token);

        // @typeof 制約: AnnotatedElementParser のプレフィックス TypeofElementParser を探す
        Optional<TypeofElement> typeofConstraint = findTypeofConstraintInAnnotatedElement(token);

        return new AnnotatedElement(element, captureName, typeofConstraint);
    }

    private static RuleBody wrapElementInSequenceBody(AtomicElement element) {
        AnnotatedElement ae = new AnnotatedElement(element, Optional.empty(), Optional.empty());
        return new SequenceBody(List.of(ae));
    }

    static Optional<String> findCaptureNameInAnnotatedElement(Token token) {
        // AnnotatedElementParser 内の直接子トークンを走査して
        // AtSignParser の次にある IdentifierParser を見つける
        boolean foundAtSign = false;
        for (Token child : token.filteredChildren) {
            if (child.parser.getClass() == UBNFParsers.AtSignParser.class) {
                foundAtSign = true;
            } else if (foundAtSign && child.parser.getClass() == UBNFParsers.IdentifierParser.class) {
                return Optional.of(child.source.toString().trim());
            }
        }
        // 直接子に見つからない場合は Optional 子コンテナ内を探す
        for (Token child : token.filteredChildren) {
            if (child.parser.getClass() != UBNFParsers.AtSignParser.class
                && false == isAtomicElementParser(child.parser.getClass())) {
                Optional<String> found = findCaptureNameInAnnotatedElement(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    static Optional<TypeofElement> findTypeofConstraintInAnnotatedElement(Token token) {
        // AnnotatedElementParser のプレフィックス Optional 内の TypeofElementParser を探す
        List<Token> typeofTokens = findDescendants(token, UBNFParsers.TypeofElementParser.class);
        if (typeofTokens.isEmpty()) return Optional.empty();
        Token typeofToken = typeofTokens.get(0);
        List<Token> identifiers = findDescendants(typeofToken, UBNFParsers.IdentifierParser.class);
        String refCapture = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
        return Optional.of(new TypeofElement(refCapture));
    }

    static AtomicElement toAtomicElement(Token token) {
        // GroupElement
        List<Token> groupTokens = findDescendants(token, UBNFParsers.GroupElementParser.class);
        if (false == groupTokens.isEmpty()) {
            List<Token> bodyTokens = findDescendants(groupTokens.get(0), UBNFParsers.ChoiceBodyParser.class);
            RuleBody body = bodyTokens.isEmpty() ? new SequenceBody(List.of()) : toChoiceBody(bodyTokens.get(0));
            return new GroupElement(body);
        }
        // OptionalElement
        List<Token> optTokens = findDescendants(token, UBNFParsers.OptionalElementParser.class);
        if (false == optTokens.isEmpty()) {
            List<Token> bodyTokens = findDescendants(optTokens.get(0), UBNFParsers.ChoiceBodyParser.class);
            RuleBody body = bodyTokens.isEmpty() ? new SequenceBody(List.of()) : toChoiceBody(bodyTokens.get(0));
            return new OptionalElement(body);
        }
        // RepeatElement
        List<Token> repTokens = findDescendants(token, UBNFParsers.RepeatElementParser.class);
        if (false == repTokens.isEmpty()) {
            List<Token> bodyTokens = findDescendants(repTokens.get(0), UBNFParsers.ChoiceBodyParser.class);
            RuleBody body = bodyTokens.isEmpty() ? new SequenceBody(List.of()) : toChoiceBody(bodyTokens.get(0));
            return new RepeatElement(body);
        }
        // TerminalElement
        List<Token> termTokens = findDescendants(token, UBNFParsers.TerminalElementParser.class);
        if (false == termTokens.isEmpty()) {
            List<Token> quotedTokens = findDescendants(
                termTokens.get(0),
                org.unlaxer.parser.elementary.SingleQuotedParser.class
            );
            String value = quotedTokens.isEmpty()
                ? ""
                : stripQuotes(quotedTokens.get(0).source.toString().trim());
            return new TerminalElement(value);
        }
        // RuleRefElement（fallback）
        List<Token> refTokens = findDescendants(token, UBNFParsers.RuleRefElementParser.class);
        if (false == refTokens.isEmpty()) {
            List<Token> identifiers = findDescendants(refTokens.get(0), UBNFParsers.IdentifierParser.class);
            String name = identifiers.isEmpty() ? "" : identifiers.get(0).source.toString().trim();
            return new RuleRefElement(name);
        }
        // AtomicElementParser 直下の IdentifierParser が RuleRef になることもある
        List<Token> identifiers = findDescendants(token, UBNFParsers.IdentifierParser.class);
        if (false == identifiers.isEmpty()) {
            return new RuleRefElement(identifiers.get(0).source.toString().trim());
        }
        return new RuleRefElement("?");
    }

    // =========================================================================
    // ユーティリティ
    // =========================================================================

    /**
     * 指定パーサークラスの子孫 Token を深さ優先で探す。
     * 一致した Token が見つかった場合はそのノード内部には入らない（shallow）。
     */
    static List<Token> findDescendants(Token token, Class<? extends Parser> parserClass) {
        List<Token> results = new ArrayList<>();
        for (Token child : token.filteredChildren) {
            if (child.parser.getClass() == parserClass) {
                results.add(child);
            } else {
                results.addAll(findDescendants(child, parserClass));
            }
        }
        return results;
    }

    /**
     * シングルクォートを除き、UBNF エスケープシーケンスを処理した文字列値を返す。
     * 例: "'hello\\nworld'" → "hello\nworld"（実際の改行文字）
     * サポートするエスケープ: \n \t \r \\ \'
     */
    static String stripQuotes(String quoted) {
        String inner;
        if (quoted.length() >= 2
            && '\'' == quoted.charAt(0)
            && '\'' == quoted.charAt(quoted.length() - 1)) {
            inner = quoted.substring(1, quoted.length() - 1);
        } else {
            inner = quoted;
        }
        return processEscapes(inner);
    }

    /**
     * UBNF リテラル内のエスケープシーケンスを実際の文字に変換する。
     * \n → 改行、\t → タブ、\r → CR、\\ → バックスラッシュ、\' → シングルクォート
     */
    public static String processEscapes(String s) {
        if (!s.contains("\\")) {
            return s; // fast path: エスケープなし
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n'  -> { sb.append('\n'); i++; }
                    case 't'  -> { sb.append('\t'); i++; }
                    case 'r'  -> { sb.append('\r'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '\'' -> { sb.append('\''); i++; }
                    default   -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isAtomicElementParser(Class<?> clazz) {
        return clazz == UBNFParsers.GroupElementParser.class
            || clazz == UBNFParsers.OptionalElementParser.class
            || clazz == UBNFParsers.RepeatElementParser.class
            || clazz == UBNFParsers.TerminalElementParser.class
            || clazz == UBNFParsers.RuleRefElementParser.class;
    }
}
