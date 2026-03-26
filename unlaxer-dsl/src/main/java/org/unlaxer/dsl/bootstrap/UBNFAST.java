package org.unlaxer.dsl.bootstrap;

import java.util.List;
import java.util.Optional;

/**
 * UBNF AST ノード定義。
 *
 * sealed interface + record によって UBNF の全構文要素を型安全に表現する。
 * UBNFMapper によってパースツリー（Token）から生成される。
 */
public sealed interface UBNFAST permits
    UBNFAST.UBNFFile,
    UBNFAST.GrammarDecl,
    UBNFAST.GlobalSetting,
    UBNFAST.SettingValue,
    UBNFAST.KeyValuePair,
    UBNFAST.TokenDecl,
    UBNFAST.RuleDecl,
    UBNFAST.Annotation,
    UBNFAST.RuleBody,
    UBNFAST.AnnotatedElement,
    UBNFAST.AtomicElement,
    UBNFAST.TypeofElement {

    // =========================================================================
    // ファイルレベル
    // =========================================================================

    /**
     * UBNFFile: 1つ以上の GrammarDecl を含むファイル全体
     */
    record UBNFFile(List<GrammarDecl> grammars) implements UBNFAST {}

    /**
     * GrammarDecl: grammar NAME { settings... tokens... rules... }
     */
    record GrammarDecl(
        String name,
        List<GlobalSetting> settings,
        List<TokenDecl> tokens,
        List<RuleDecl> rules
    ) implements UBNFAST {}

    // =========================================================================
    // グローバル設定
    // =========================================================================

    /**
     * GlobalSetting: @key: value
     */
    record GlobalSetting(String key, SettingValue value) implements UBNFAST {}

    /**
     * 設定値の選択肢
     */
    sealed interface SettingValue extends UBNFAST permits
        UBNFAST.StringSettingValue,
        UBNFAST.BlockSettingValue {}

    record StringSettingValue(String value) implements SettingValue {}

    record BlockSettingValue(List<KeyValuePair> entries) implements SettingValue {}

    record KeyValuePair(String key, String value) implements UBNFAST {}

    // =========================================================================
    // トークン宣言
    // =========================================================================

    /**
     * TokenDecl: token NAME = ( ParserClass | UNTIL(terminator) )
     */
    sealed interface TokenDecl extends UBNFAST
        permits TokenDecl.Simple, TokenDecl.Until,
                TokenDecl.Negation, TokenDecl.Lookahead, TokenDecl.NegativeLookahead,
                TokenDecl.Any, TokenDecl.Eof, TokenDecl.Empty,
                TokenDecl.CharRange, TokenDecl.CaseInsensitive,
                TokenDecl.Regex {
        String name();

        /**
         * Returns the parser class name, or null for non-Simple tokens.
         * Use instanceof pattern matching for type-safe dispatch.
         */
        default String parserClass() { return null; }

        /** token NAME = ClassName */
        record Simple(String name, String parserClass) implements TokenDecl {}

        /** token NAME = UNTIL('terminator') — matches until terminator string */
        record Until(String name, String terminator) implements TokenDecl {}

        /** token NAME = NEGATION('chars') — matches single char NOT in excludedChars */
        record Negation(String name, String excludedChars) implements TokenDecl {}

        /** token NAME = LOOKAHEAD('pattern') — asserts pattern present, consumes no input */
        record Lookahead(String name, String pattern) implements TokenDecl {}

        /** token NAME = NEGATIVE_LOOKAHEAD('pattern') — asserts pattern absent, consumes no input */
        record NegativeLookahead(String name, String pattern) implements TokenDecl {}

        /** token NAME = ANY — matches any single character */
        record Any(String name) implements TokenDecl {}

        /** token NAME = EOF — matches end of input */
        record Eof(String name) implements TokenDecl {}

        /** token NAME = EMPTY — matches empty string (always succeeds without consuming) */
        record Empty(String name) implements TokenDecl {}

        /** token NAME = CHAR_RANGE('a','z') — matches a single char in [min,max] range */
        record CharRange(String name, char min, char max) implements TokenDecl {}

        /** token NAME = CI('keyword') — case-insensitive literal match */
        record CaseInsensitive(String name, String word) implements TokenDecl {}

        /** token NAME = REGEX('pattern') — regex-based multi-char token matching */
        record Regex(String name, String pattern) implements TokenDecl {}
    }

    // =========================================================================
    // ルール宣言
    // =========================================================================

    /**
     * RuleDecl: annotations* NAME ::= body ;
     */
    record RuleDecl(
        List<Annotation> annotations,
        String name,
        RuleBody body
    ) implements UBNFAST {}

    // =========================================================================
    // アノテーション
    // =========================================================================

    sealed interface Annotation extends UBNFAST permits
        UBNFAST.RootAnnotation,
        UBNFAST.MappingAnnotation,
        UBNFAST.WhitespaceAnnotation,
        UBNFAST.InterleaveAnnotation,
        UBNFAST.BackrefAnnotation,
        UBNFAST.ScopeTreeAnnotation,
        UBNFAST.DeclaresAnnotation,
        UBNFAST.CatalogAnnotation,
        UBNFAST.LeftAssocAnnotation,
        UBNFAST.RightAssocAnnotation,
        UBNFAST.PrecedenceAnnotation,
        UBNFAST.DocAnnotation,
        UBNFAST.SkipAnnotation,
        UBNFAST.SimpleAnnotation {}

    /** @root */
    record RootAnnotation() implements Annotation {}

    /** @mapping(ClassName, params=[a, b, c]) */
    record MappingAnnotation(
        String className,
        List<String> paramNames
    ) implements Annotation {}

    /** @whitespace または @whitespace(style) */
    record WhitespaceAnnotation(Optional<String> style) implements Annotation {}

    /** @interleave(profile=javaStyle) */
    record InterleaveAnnotation(String profile) implements Annotation {}

    /** @backref(name=identifier) */
    record BackrefAnnotation(String name) implements Annotation {}

    /** @scopeTree(mode=lexical) */
    record ScopeTreeAnnotation(String mode) implements Annotation {}

    /** @declares(symbol=captureName[, description=descCaptureName]) — このルールのパース成功時にキャプチャした識別子をスコープに登録する */
    record DeclaresAnnotation(String symbolCapture, String description) implements Annotation {}

    /** @catalog(context='contextName') — このルールをカタログ変数補完・ホバーの対象とする */
    record CatalogAnnotation(String context) implements Annotation {}

    /** @leftAssoc */
    record LeftAssocAnnotation() implements Annotation {}

    /** @rightAssoc */
    record RightAssocAnnotation() implements Annotation {}

    /** @precedence(level=10) */
    record PrecedenceAnnotation(int level) implements Annotation {}

    /** @doc("description text") — documentation comment, emitted as Java comment */
    record DocAnnotation(String text) implements Annotation {}

    /**
     * @skip — rule is parsed normally but its tokens are excluded from the parent's
     * AST token tree (getNotAstNodeSpecifier returns containsRoot).
     */
    record SkipAnnotation() implements Annotation {}

    /** @name（上記以外の任意アノテーション） */
    record SimpleAnnotation(String name) implements Annotation {}

    // =========================================================================
    // ルール本体
    // =========================================================================

    sealed interface RuleBody extends UBNFAST permits
        UBNFAST.ChoiceBody,
        UBNFAST.SequenceBody {}

    /**
     * ChoiceBody: SequenceBody { '|' SequenceBody }
     * alternatives は最低1要素（1要素の場合は実質 SequenceBody と同じ）
     */
    record ChoiceBody(List<SequenceBody> alternatives) implements RuleBody {}

    /**
     * SequenceBody: AnnotatedElement+
     */
    record SequenceBody(List<AnnotatedElement> elements) implements RuleBody {}

    // =========================================================================
    // 要素
    // =========================================================================

    /**
     * AnnotatedElement: [@typeof(name)] AtomicElement [@captureName]
     */
    record AnnotatedElement(
        AtomicElement element,
        Optional<String> captureName,
        Optional<TypeofElement> typeofConstraint
    ) implements UBNFAST {}

    sealed interface AtomicElement extends UBNFAST permits
        UBNFAST.GroupElement,
        UBNFAST.OptionalElement,
        UBNFAST.RepeatElement,
        UBNFAST.OneOrMoreElement,
        UBNFAST.BoundedRepeatElement,
        UBNFAST.SeparatedElement,
        UBNFAST.TerminalElement,
        UBNFAST.RuleRefElement,
        UBNFAST.ErrorElement {}

    /** ( RuleBody ) */
    record GroupElement(RuleBody body) implements AtomicElement {}

    /** [ RuleBody ] */
    record OptionalElement(RuleBody body) implements AtomicElement {}

    /** { RuleBody } */
    record RepeatElement(RuleBody body) implements AtomicElement {}

    /** element+ — one or more occurrences */
    record OneOrMoreElement(RuleBody body) implements AtomicElement {}

    /**
     * element{n} / element{n,m} / element{n,} — bounded repetition.
     * max == Integer.MAX_VALUE means unbounded (i.e. {n,}).
     */
    record BoundedRepeatElement(RuleBody body, int min, int max) implements AtomicElement {
        /** Sentinel value used to represent an open upper bound ({n,}). */
        public static final int UNBOUNDED = Integer.MAX_VALUE;
    }

    /** 'literal' */
    record TerminalElement(String value) implements AtomicElement {}

    /** RuleRef（非終端記号参照） */
    record RuleRefElement(String name) implements AtomicElement {}

    /**
     * elem % sep — syntactic sugar for elem (sep elem)*.
     * Represents a non-empty separated list.
     */
    record SeparatedElement(AtomicElement element, AtomicElement separator) implements AtomicElement {}

    /**
     * ERROR("message") — 常に失敗するエラーヒント要素。
     * 診断情報として message が表示される。ErrorMessageParser.expected() に対応。
     */
    record ErrorElement(String message) implements AtomicElement {}

    /**
     * @typeof(captureName) — AnnotatedElement のプレフィックス制約。
     * captureName で指定したキャプチャと同じ Choice alternative を持つことを制約する。
     * マッパーの fromToken() 内でランタイム型チェックコードを生成する。
     */
    record TypeofElement(String captureName) implements UBNFAST {}
}
