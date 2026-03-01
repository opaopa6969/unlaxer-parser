package org.unlaxer.dsl.bootstrap;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.EqualParser;
import org.unlaxer.parser.ascii.LeftParenthesisParser;
import org.unlaxer.parser.ascii.PointParser;
import org.unlaxer.parser.ascii.RightParenthesisParser;
import org.unlaxer.parser.clang.CPPComment;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.LazyOneOrMore;
import org.unlaxer.parser.combinator.LazyZeroOrMore;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.SingleCharacterParser;
import org.unlaxer.parser.elementary.SingleQuotedParser;
import org.unlaxer.parser.elementary.SpaceDelimitor;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.AlphabetNumericUnderScoreParser;
import org.unlaxer.parser.posix.AlphabetUnderScoreParser;
import org.unlaxer.parser.posix.ColonParser;
import org.unlaxer.parser.posix.CommaParser;
import org.unlaxer.parser.posix.SpaceParser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;
import org.unlaxer.util.cache.SupplierBoundCache;

/**
 * UBNF (Unlaxer BNF) パーサー定義 — Bootstrap 手書き実装 (Phase 1)
 *
 * Grammar (UBNF):
 *
 * UBNFFile      ::= GrammarDecl+
 * GrammarDecl   ::= 'grammar' IDENTIFIER '{' GlobalSetting* TokenDecl* RuleDecl+ '}'
 * GlobalSetting ::= '@' IDENTIFIER ':' SettingValue
 * SettingValue  ::= StringSettingValue | BlockSettingValue
 * TokenDecl     ::= 'token' IDENTIFIER '=' CLASS_NAME
 * RuleDecl      ::= Annotation* IDENTIFIER '::=' RuleBody
 * Annotation    ::= '@root' | '@mapping(...)' | '@whitespace[(...)]'
 *                 | '@leftAssoc' | '@rightAssoc' | '@precedence(level=INTEGER)' | '@' IDENTIFIER
 * RuleBody      ::= ChoiceBody
 * ChoiceBody    ::= SequenceBody { '|' SequenceBody }
 * SequenceBody  ::= AnnotatedElement+
 * AnnotatedElement ::= AtomicElement ['@' IDENTIFIER]
 * AtomicElement ::= GroupElement | OptionalElement | RepeatElement
 *                 | TerminalElement | RuleRefElement
 */
public class UBNFParsers {

    // =========================================================================
    // 追加文字パーサー（stdlib にないもの）
    // =========================================================================

    public static class LeftBraceParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return '{' == target;
        }
    }

    public static class RightBraceParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return '}' == target;
        }
    }

    public static class LeftBracketParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return '[' == target;
        }
    }

    public static class RightBracketParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return ']' == target;
        }
    }

    public static class AtSignParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return '@' == target;
        }
    }

    public static class PipeParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return '|' == target;
        }
    }

    public static class DigitParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return Character.isDigit(target);
        }
    }

    // =========================================================================
    // UBNF 専用 SpaceDelimitor（空白 + // コメントをスキップ）
    // =========================================================================

    /**
     * 空白文字または // コメントを0回以上スキップするデリミタ。
     * UBNF ファイルは // コメントを含むため、通常の SpaceDelimitor の代わりに使用する。
     */
    public static class UBNFSpaceDelimitor extends LazyZeroOrMore {
        private static final long serialVersionUID = 1L;

        @Override
        public Supplier<Parser> getLazyParser() {
            return new SupplierBoundCache<>(() -> new Choice(
                SpaceParser.class,
                CPPComment.class
            ));
        }

        @Override
        public Optional<Parser> getLazyTerminatorParser() {
            return Optional.empty();
        }
    }

    // =========================================================================
    // UBNF 基底チェインクラス（コメント対応空白デリミタ使用）
    // =========================================================================

    /**
     * UBNF パース用の LazyChain 基底クラス。
     * WhiteSpaceDelimitedLazyChain と同等だが UBNFSpaceDelimitor を使用する。
     */
    public static abstract class UBNFLazyChain extends LazyChain {
        private static final long serialVersionUID = 1L;

        private static final UBNFSpaceDelimitor ubnfSpaceDelimitor = createDelimitor();

        private static UBNFSpaceDelimitor createDelimitor() {
            UBNFSpaceDelimitor delimitor = new UBNFSpaceDelimitor();
            delimitor.addTag(NodeKind.notNode.getTag());
            return delimitor;
        }

        @Override
        public void prepareChildren(Parsers childrenContainer) {
            if (false == childrenContainer.isEmpty()) {
                return;
            }
            childrenContainer.add(ubnfSpaceDelimitor);
            for (Parser parser : getLazyParsers()) {
                childrenContainer.add(parser);
                childrenContainer.add(ubnfSpaceDelimitor);
            }
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    // =========================================================================
    // IDENTIFIER パーサー
    // =========================================================================

    /**
     * IDENTIFIER: AlphabetUnderScore { AlphabetNumericUnderScore }
     */
    public static class IdentifierParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(AlphabetUnderScoreParser.class),
                new ZeroOrMore(Parser.get(AlphabetNumericUnderScoreParser.class))
            );
        }
    }

    /**
     * UNSIGNED_INTEGER: Digit+
     */
    public static class UnsignedIntegerParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new OneOrMore(Parser.get(DigitParser.class))
            );
        }
    }

    /**
     * DottedIdentifier: IDENTIFIER { '.' IDENTIFIER }
     * パッケージ名（org.unlaxer.dsl）など、ドット区切り識別子に使用する。
     */
    public static class DottedIdentifierParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(IdentifierParser.class),
                new ZeroOrMore(
                    new UBNFLazyChain() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public Parsers getLazyParsers() {
                            return new Parsers(
                                Parser.get(PointParser.class),
                                Parser.get(IdentifierParser.class)
                            );
                        }
                    }
                )
            );
        }
    }

    // =========================================================================
    // グローバル設定パーサー
    // =========================================================================

    /**
     * KeyValuePair: IDENTIFIER ':' STRING
     */
    public static class KeyValuePairParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(IdentifierParser.class),
                Parser.get(ColonParser.class),
                Parser.get(SingleQuotedParser.class)
            );
        }
    }

    /**
     * StringSettingValue: DottedIdentifier
     * パッケージ名（org.unlaxer.dsl）やスタイル名（javaStyle）を値として受け取る。
     */
    public static class StringSettingValueParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(DottedIdentifierParser.class)
            );
        }
    }

    /**
     * BlockSettingValue: '{' KeyValuePair+ '}'
     */
    public static class BlockSettingValueParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(LeftBraceParser.class),
                new OneOrMore(Parser.get(KeyValuePairParser.class)),
                Parser.get(RightBraceParser.class)
            );
        }
    }

    /**
     * SettingValue: StringSettingValue | BlockSettingValue
     */
    public static class SettingValueParser extends LazyChoice {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(BlockSettingValueParser.class),
                Parser.get(StringSettingValueParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * GlobalSetting: '@' IDENTIFIER ':' SettingValue
     */
    public static class GlobalSettingParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(AtSignParser.class),
                Parser.get(IdentifierParser.class),
                Parser.get(ColonParser.class),
                Parser.get(SettingValueParser.class)
            );
        }
    }

    // =========================================================================
    // トークン宣言パーサー
    // =========================================================================

    /**
     * TokenDecl: 'token' IDENTIFIER '=' CLASS_NAME
     * CLASS_NAME は IDENTIFIER { '.' IDENTIFIER } (完全修飾名も可)
     */
    public static class TokenDeclParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("token"),
                Parser.get(IdentifierParser.class),
                Parser.get(EqualParser.class),
                Parser.get(QualifiedClassNameParser.class)
            );
        }
    }

    /**
     * QualifiedClassNameParser: IDENTIFIER { '.' IDENTIFIER }
     * 完全修飾Javaクラス名（例: org.unlaxer.parser.clang.IdentifierParser）を
     * ドット区切りで解析する。
     */
    public static class QualifiedClassNameParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(IdentifierParser.class),
                new ZeroOrMore(
                    new UBNFLazyChain() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public Parsers getLazyParsers() {
                            return new Parsers(
                                Parser.get(PointParser.class),
                                Parser.get(IdentifierParser.class)
                            );
                        }
                    }
                )
            );
        }
    }

    // =========================================================================
    // アノテーションパーサー
    // =========================================================================

    /**
     * RootAnnotation: '@root'
     */
    public static class RootAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@root")
            );
        }
    }

    /**
     * ParameterList: '[' IDENTIFIER { ',' IDENTIFIER } ']'
     */
    public static class ParameterListParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(LeftBracketParser.class),
                Parser.get(IdentifierParser.class),
                new ZeroOrMore(
                    new UBNFLazyChain() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public Parsers getLazyParsers() {
                            return new Parsers(
                                Parser.get(CommaParser.class),
                                Parser.get(IdentifierParser.class)
                            );
                        }
                    }
                ),
                Parser.get(RightBracketParser.class)
            );
        }
    }

    /**
     * MappingParams: ',' 'params' '=' ParameterList
     */
    public static class MappingParamsParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(CommaParser.class),
                new WordParser("params"),
                Parser.get(EqualParser.class),
                Parser.get(ParameterListParser.class)
            );
        }
    }

    /**
     * MappingAnnotation: '@mapping' '(' CLASS_NAME [',' 'params' '=' '[' IDENTIFIER+ ']'] ')'
     */
    public static class MappingAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@mapping"),
                Parser.get(LeftParenthesisParser.class),
                Parser.get(IdentifierParser.class),
                new org.unlaxer.parser.combinator.Optional(
                    Parser.get(MappingParamsParser.class)
                ),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * WhitespaceAnnotation: '@whitespace' ['(' IDENTIFIER ')']
     */
    public static class WhitespaceAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@whitespace"),
                new org.unlaxer.parser.combinator.Optional(
                    new UBNFLazyChain() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public Parsers getLazyParsers() {
                            return new Parsers(
                                Parser.get(LeftParenthesisParser.class),
                                Parser.get(IdentifierParser.class),
                                Parser.get(RightParenthesisParser.class)
                            );
                        }
                    }
                )
            );
        }
    }

    /**
     * InterleaveAnnotation: '@interleave' '(' 'profile' '=' IDENTIFIER ')'
     */
    public static class InterleaveAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@interleave"),
                Parser.get(LeftParenthesisParser.class),
                new WordParser("profile"),
                Parser.get(EqualParser.class),
                Parser.get(IdentifierParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * BackrefAnnotation: '@backref' '(' 'name' '=' IDENTIFIER ')'
     */
    public static class BackrefAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@backref"),
                Parser.get(LeftParenthesisParser.class),
                new WordParser("name"),
                Parser.get(EqualParser.class),
                Parser.get(IdentifierParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * ScopeTreeAnnotation: '@scopeTree' '(' 'mode' '=' IDENTIFIER ')'
     */
    public static class ScopeTreeAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@scopeTree"),
                Parser.get(LeftParenthesisParser.class),
                new WordParser("mode"),
                Parser.get(EqualParser.class),
                Parser.get(IdentifierParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * LeftAssocAnnotation: '@leftAssoc'
     */
    public static class LeftAssocAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@leftAssoc")
            );
        }
    }

    /**
     * RightAssocAnnotation: '@rightAssoc'
     */
    public static class RightAssocAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@rightAssoc")
            );
        }
    }

    /**
     * PrecedenceAnnotation: '@precedence' '(' 'level' '=' UNSIGNED_INTEGER ')'
     */
    public static class PrecedenceAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("@precedence"),
                Parser.get(LeftParenthesisParser.class),
                new WordParser("level"),
                Parser.get(EqualParser.class),
                Parser.get(UnsignedIntegerParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * SimpleAnnotation: '@' IDENTIFIER
     * （上記の特殊アノテーションにマッチしない場合のフォールバック）
     */
    public static class SimpleAnnotationParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(AtSignParser.class),
                Parser.get(IdentifierParser.class)
            );
        }
    }

    /**
     * Annotation: RootAnnotation | MappingAnnotation | WhitespaceAnnotation
     *           | InterleaveAnnotation | BackrefAnnotation | ScopeTreeAnnotation
     *           | LeftAssocAnnotation | RightAssocAnnotation
     *           | PrecedenceAnnotation | SimpleAnnotation
     */
    public static class AnnotationParser extends LazyChoice {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(RootAnnotationParser.class),
                Parser.get(MappingAnnotationParser.class),
                Parser.get(WhitespaceAnnotationParser.class),
                Parser.get(InterleaveAnnotationParser.class),
                Parser.get(BackrefAnnotationParser.class),
                Parser.get(ScopeTreeAnnotationParser.class),
                Parser.get(LeftAssocAnnotationParser.class),
                Parser.get(RightAssocAnnotationParser.class),
                Parser.get(PrecedenceAnnotationParser.class),
                Parser.get(SimpleAnnotationParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    // =========================================================================
    // ルール本体パーサー（再帰あり → LazyXxx を使う）
    // =========================================================================

    /**
     * TerminalElement: STRING（シングルクォート文字列）
     */
    public static class TerminalElementParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(SingleQuotedParser.class)
            );
        }
    }

    /**
     * RuleRefElement: IDENTIFIER
     */
    public static class RuleRefElementParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(IdentifierParser.class)
            );
        }
    }

    /**
     * GroupElement: '(' RuleBody ')'
     * Circular: references RuleBodyParser
     */
    public static class GroupElementParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(LeftParenthesisParser.class),
                Parser.get(RuleBodyParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * OptionalElement: '[' RuleBody ']'
     * Circular: references RuleBodyParser
     */
    public static class OptionalElementParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(LeftBracketParser.class),
                Parser.get(RuleBodyParser.class),
                Parser.get(RightBracketParser.class)
            );
        }
    }

    /**
     * RepeatElement: '{' RuleBody '}'
     * Circular: references RuleBodyParser
     */
    public static class RepeatElementParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(LeftBraceParser.class),
                Parser.get(RuleBodyParser.class),
                Parser.get(RightBraceParser.class)
            );
        }
    }

    /**
     * AtomicElement: GroupElement | OptionalElement | RepeatElement
     *              | TerminalElement | RuleRefElement
     */
    public static class AtomicElementParser extends LazyChoice {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(GroupElementParser.class),
                Parser.get(OptionalElementParser.class),
                Parser.get(RepeatElementParser.class),
                Parser.get(TerminalElementParser.class),
                Parser.get(RuleRefElementParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * AnnotatedElement: AtomicElement ['@' IDENTIFIER]
     */
    public static class AnnotatedElementParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(AtomicElementParser.class),
                new org.unlaxer.parser.combinator.Optional(
                    new UBNFLazyChain() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public Parsers getLazyParsers() {
                            return new Parsers(
                                Parser.get(AtSignParser.class),
                                Parser.get(IdentifierParser.class)
                            );
                        }
                    }
                )
            );
        }
    }

    /**
     * SequenceBody: AnnotatedElement+
     */
    public static class SequenceBodyParser extends LazyOneOrMore {
        private static final long serialVersionUID = 1L;

        @Override
        public Supplier<Parser> getLazyParser() {
            return new SupplierBoundCache<>(() -> Parser.get(AnnotatedElementParser.class));
        }

        @Override
        public Optional<Parser> getLazyTerminatorParser() {
            return Optional.empty();
        }
    }

    /**
     * ChoiceBody: SequenceBody { '|' SequenceBody }
     * Circular: SequenceBody → AnnotatedElement → AtomicElement → GroupElement → RuleBody → ChoiceBody
     */
    public static class ChoiceBodyParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(SequenceBodyParser.class),
                new ZeroOrMore(
                    new UBNFLazyChain() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public Parsers getLazyParsers() {
                            return new Parsers(
                                Parser.get(PipeParser.class),
                                Parser.get(SequenceBodyParser.class)
                            );
                        }
                    }
                )
            );
        }
    }

    /**
     * RuleBody: ChoiceBody
     * （現状 ChoiceBody が SequenceBody を包含するため直接委譲）
     */
    public static class RuleBodyParser extends LazyChoice {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(ChoiceBodyParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    // =========================================================================
    // ルール宣言パーサー
    // =========================================================================

    /**
     * Semicolon: ';'
     */
    public static class SemicolonParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return ';' == target;
        }
    }

    /**
     * RuleDecl: Annotation* IDENTIFIER '::=' RuleBody ';'
     * ';' でルール末尾を明示してルール境界の曖昧さを排除する。
     */
    public static class RuleDeclParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new ZeroOrMore(Parser.get(AnnotationParser.class)),
                Parser.get(IdentifierParser.class),
                new WordParser("::="),
                Parser.get(RuleBodyParser.class),
                Parser.get(SemicolonParser.class)
            );
        }
    }

    // =========================================================================
    // grammar ブロックパーサー
    // =========================================================================

    /**
     * GrammarDecl: 'grammar' IDENTIFIER '{' GlobalSetting* TokenDecl* RuleDecl+ '}'
     */
    public static class GrammarDeclParser extends UBNFLazyChain {
        private static final long serialVersionUID = 1L;

        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("grammar"),
                Parser.get(IdentifierParser.class),
                Parser.get(LeftBraceParser.class),
                new ZeroOrMore(Parser.get(GlobalSettingParser.class)),
                new ZeroOrMore(Parser.get(TokenDeclParser.class)),
                new OneOrMore(Parser.get(RuleDeclParser.class)),
                Parser.get(RightBraceParser.class)
            );
        }
    }

    // =========================================================================
    // ルートパーサー
    // =========================================================================

    /**
     * UBNFFile: GrammarDecl+
     */
    public static class UBNFFileParser extends LazyOneOrMore {
        private static final long serialVersionUID = 1L;

        @Override
        public Supplier<Parser> getLazyParser() {
            return new SupplierBoundCache<>(() -> Parser.get(GrammarDeclParser.class));
        }

        @Override
        public Optional<Parser> getLazyTerminatorParser() {
            return Optional.empty();
        }
    }

    // =========================================================================
    // ファクトリメソッド
    // =========================================================================

    public static Parser getRootParser() {
        return Parser.get(UBNFFileParser.class);
    }

    public static Parser getGrammarDeclParser() {
        return Parser.get(GrammarDeclParser.class);
    }
}
