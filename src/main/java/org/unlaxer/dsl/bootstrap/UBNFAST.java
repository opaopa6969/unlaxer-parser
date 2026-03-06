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
    sealed interface TokenDecl extends UBNFAST permits TokenDecl.Simple, TokenDecl.Until {
        String name();

        /**
         * Returns the parser class name, or null for UNTIL tokens.
         * Use instanceof pattern matching for type-safe dispatch.
         */
        default String parserClass() { return null; }

        /** token NAME = ClassName */
        record Simple(String name, String parserClass) implements TokenDecl {}

        /** token NAME = UNTIL('terminator') */
        record Until(String name, String terminator) implements TokenDecl {}
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
        UBNFAST.LeftAssocAnnotation,
        UBNFAST.RightAssocAnnotation,
        UBNFAST.PrecedenceAnnotation,
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

    /** @leftAssoc */
    record LeftAssocAnnotation() implements Annotation {}

    /** @rightAssoc */
    record RightAssocAnnotation() implements Annotation {}

    /** @precedence(level=10) */
    record PrecedenceAnnotation(int level) implements Annotation {}

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
        UBNFAST.TerminalElement,
        UBNFAST.RuleRefElement {}

    /** ( RuleBody ) */
    record GroupElement(RuleBody body) implements AtomicElement {}

    /** [ RuleBody ] */
    record OptionalElement(RuleBody body) implements AtomicElement {}

    /** { RuleBody } */
    record RepeatElement(RuleBody body) implements AtomicElement {}

    /** 'literal' */
    record TerminalElement(String value) implements AtomicElement {}

    /** RuleRef（非終端記号参照） */
    record RuleRefElement(String name) implements AtomicElement {}

    /**
     * @typeof(captureName) — AnnotatedElement のプレフィックス制約。
     * captureName で指定したキャプチャと同じ Choice alternative を持つことを制約する。
     * マッパーの fromToken() 内でランタイム型チェックコードを生成する。
     */
    record TypeofElement(String captureName) implements UBNFAST {}
}
