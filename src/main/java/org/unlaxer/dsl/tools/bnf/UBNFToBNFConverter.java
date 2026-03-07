package org.unlaxer.dsl.tools.bnf;

import java.util.List;
import java.util.Optional;

import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.Annotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.KeyValuePair;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ErrorElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.SettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;

/**
 * UBNF AST から EBNF テキスト形式への変換ツール。
 *
 * UBNF パースツリー（UBNFAST）を EBNF 標準記法に変換する。
 * - grammar ブロックを展開
 * - アノテーションを削除（keepAnnotations=true の場合は保持）
 * - '::=' を '=' に変換
 * - キーワードのシングルクォートをダブルクォートに変換
 * - 連接の空白をカンマに変換
 */
public final class UBNFToBNFConverter {

    private UBNFToBNFConverter() {}

    /**
     * UBNF AST を EBNF テキスト形式に変換する。
     *
     * @param file パース済みの UBNFFile AST
     * @param keepAnnotations true の場合、アノテーションをコメントとして保持
     * @return EBNF 形式のテキスト
     */
    public static String convert(UBNFAST.UBNFFile file, boolean keepAnnotations) {
        StringBuilder builder = new StringBuilder();
        ConversionContext context = new ConversionContext(keepAnnotations);

        List<GrammarDecl> grammars = file.grammars();

        for (int i = 0; i < grammars.size(); i++) {
            GrammarDecl grammar = grammars.get(i);
            convertGrammarDecl(grammar, builder, context);

            if (false == (i == grammars.size() - 1)) {
                builder.append("\n\n");
            }
        }

        return builder.toString();
    }

    private static void convertGrammarDecl(
        GrammarDecl grammar,
        StringBuilder builder,
        ConversionContext context
    ) {
        List<GlobalSetting> settings = grammar.settings();
        List<TokenDecl> tokens = grammar.tokens();
        List<RuleDecl> rules = grammar.rules();

        if (false == settings.isEmpty()) {
            for (GlobalSetting setting : settings) {
                convertGlobalSetting(setting, builder, context);
                builder.append("\n");
            }
            builder.append("\n");
        }

        if (false == tokens.isEmpty()) {
            for (TokenDecl token : tokens) {
                convertTokenDecl(token, builder, context);
                builder.append("\n");
            }
            builder.append("\n");
        }

        for (int i = 0; i < rules.size(); i++) {
            RuleDecl rule = rules.get(i);
            convertRuleDecl(rule, builder, context);

            if (false == (i == rules.size() - 1)) {
                builder.append("\n\n");
            }
        }
    }

    private static void convertGlobalSetting(
        GlobalSetting setting,
        StringBuilder builder,
        ConversionContext context
    ) {
        String key = setting.key();
        SettingValue value = setting.value();

        builder.append("(* @");
        builder.append(key);
        builder.append(": ");

        if (value instanceof StringSettingValue stringValue) {
            builder.append(stringValue.value());
        } else if (value instanceof BlockSettingValue blockValue) {
            builder.append("{ ");
            List<KeyValuePair> entries = blockValue.entries();
            for (int i = 0; i < entries.size(); i++) {
                KeyValuePair entry = entries.get(i);
                builder.append(entry.key());
                builder.append(": \"");
                builder.append(entry.value());
                builder.append("\"");

                if (false == (i == entries.size() - 1)) {
                    builder.append(", ");
                }
            }
            builder.append(" }");
        }

        builder.append(" *)");
    }

    private static void convertTokenDecl(
        TokenDecl token,
        StringBuilder builder,
        ConversionContext context
    ) {
        String name = token.name();
        String tokenValue = switch (token) {
            case UBNFAST.TokenDecl.Simple s              -> s.parserClass();
            case UBNFAST.TokenDecl.Until u               -> "UNTIL('" + u.terminator() + "')";
            case UBNFAST.TokenDecl.Negation n            -> "NEGATION('" + n.excludedChars() + "')";
            case UBNFAST.TokenDecl.Lookahead la          -> "LOOKAHEAD('" + la.pattern() + "')";
            case UBNFAST.TokenDecl.NegativeLookahead nla -> "NEGATIVE_LOOKAHEAD('" + nla.pattern() + "')";
            case UBNFAST.TokenDecl.Any a                 -> "ANY";
            case UBNFAST.TokenDecl.Eof e                 -> "EOF";
            case UBNFAST.TokenDecl.Empty em              -> "EMPTY";
            case UBNFAST.TokenDecl.CharRange cr          -> "CHAR_RANGE('" + cr.min() + "','" + cr.max() + "')";
            case UBNFAST.TokenDecl.CaseInsensitive ci    -> "CI('" + ci.word() + "')";
            case UBNFAST.TokenDecl.Regex rx              -> "REGEX('" + rx.pattern() + "')";
        };

        builder.append("(* token: ");
        builder.append(name);
        builder.append(" = ");
        builder.append(tokenValue);
        builder.append(" *)");
    }

    private static void convertRuleDecl(
        RuleDecl rule,
        StringBuilder builder,
        ConversionContext context
    ) {
        List<Annotation> annotations = rule.annotations();

        if (context.keepAnnotations && false == annotations.isEmpty()) {
            builder.append("(* ");
            for (int i = 0; i < annotations.size(); i++) {
                Annotation annotation = annotations.get(i);
                convertAnnotation(annotation, builder, context);

                if (false == (i == annotations.size() - 1)) {
                    builder.append(" ");
                }
            }
            builder.append(" *)\n");
        }

        String name = rule.name();
        RuleBody body = rule.body();

        builder.append(name);
        builder.append(" = ");
        convertRuleBody(body, builder, context);
        builder.append(" ;");
    }

    private static void convertAnnotation(
        Annotation annotation,
        StringBuilder builder,
        ConversionContext context
    ) {
        if (annotation instanceof UBNFAST.RootAnnotation) {
            builder.append("@root");
        } else if (annotation instanceof UBNFAST.MappingAnnotation mappingAnnotation) {
            builder.append("@mapping(");
            builder.append(mappingAnnotation.className());

            List<String> paramNames = mappingAnnotation.paramNames();
            if (false == paramNames.isEmpty()) {
                builder.append(", params=[");
                for (int i = 0; i < paramNames.size(); i++) {
                    builder.append(paramNames.get(i));
                    if (false == (i == paramNames.size() - 1)) {
                        builder.append(", ");
                    }
                }
                builder.append("]");
            }

            builder.append(")");
        } else if (annotation instanceof UBNFAST.WhitespaceAnnotation whitespaceAnnotation) {
            builder.append("@whitespace");
            Optional<String> style = whitespaceAnnotation.style();
            if (style.isPresent()) {
                builder.append("(");
                builder.append(style.get());
                builder.append(")");
            }
        } else if (annotation instanceof UBNFAST.InterleaveAnnotation interleaveAnnotation) {
            builder.append("@interleave(profile=");
            builder.append(interleaveAnnotation.profile());
            builder.append(")");
        } else if (annotation instanceof UBNFAST.BackrefAnnotation backrefAnnotation) {
            builder.append("@backref(name=");
            builder.append(backrefAnnotation.name());
            builder.append(")");
        } else if (annotation instanceof UBNFAST.ScopeTreeAnnotation scopeTreeAnnotation) {
            builder.append("@scopeTree(mode=");
            builder.append(scopeTreeAnnotation.mode());
            builder.append(")");
        } else if (annotation instanceof UBNFAST.DeclaresAnnotation declaresAnnotation) {
            builder.append("@declares(symbol=");
            builder.append(declaresAnnotation.symbolCapture());
            builder.append(")");
        } else if (annotation instanceof UBNFAST.LeftAssocAnnotation) {
            builder.append("@leftAssoc");
        } else if (annotation instanceof UBNFAST.RightAssocAnnotation) {
            builder.append("@rightAssoc");
        } else if (annotation instanceof UBNFAST.PrecedenceAnnotation precedenceAnnotation) {
            builder.append("@precedence(level=");
            builder.append(precedenceAnnotation.level());
            builder.append(")");
        } else if (annotation instanceof UBNFAST.DocAnnotation docAnnotation) {
            builder.append("@doc('");
            builder.append(docAnnotation.text().replace("'", "\\'"));
            builder.append("')");
        } else if (annotation instanceof UBNFAST.SkipAnnotation) {
            builder.append("@skip");
        } else if (annotation instanceof UBNFAST.SimpleAnnotation simpleAnnotation) {
            builder.append("@");
            builder.append(simpleAnnotation.name());
        }
    }

    private static void convertRuleBody(
        RuleBody body,
        StringBuilder builder,
        ConversionContext context
    ) {
        if (body instanceof ChoiceBody choiceBody) {
            convertChoiceBody(choiceBody, builder, context);
        } else if (body instanceof SequenceBody sequenceBody) {
            convertSequenceBody(sequenceBody, builder, context);
        }
    }

    private static void convertChoiceBody(
        ChoiceBody choiceBody,
        StringBuilder builder,
        ConversionContext context
    ) {
        List<SequenceBody> alternatives = choiceBody.alternatives();

        for (int i = 0; i < alternatives.size(); i++) {
            SequenceBody alternative = alternatives.get(i);
            convertSequenceBody(alternative, builder, context);

            if (false == (i == alternatives.size() - 1)) {
                builder.append(" | ");
            }
        }
    }

    private static void convertSequenceBody(
        SequenceBody sequenceBody,
        StringBuilder builder,
        ConversionContext context
    ) {
        List<AnnotatedElement> elements = sequenceBody.elements();

        for (int i = 0; i < elements.size(); i++) {
            AnnotatedElement element = elements.get(i);
            convertAnnotatedElement(element, builder, context);

            if (false == (i == elements.size() - 1)) {
                builder.append(" , ");
            }
        }
    }

    private static void convertAnnotatedElement(
        AnnotatedElement element,
        StringBuilder builder,
        ConversionContext context
    ) {
        AtomicElement atomicElement = element.element();
        Optional<String> captureName = element.captureName();

        convertAtomicElement(atomicElement, builder, context);

        if (captureName.isPresent()) {
            builder.append(" @");
            builder.append(captureName.get());
        }
    }

    private static void convertAtomicElement(
        AtomicElement element,
        StringBuilder builder,
        ConversionContext context
    ) {
        if (element instanceof GroupElement groupElement) {
            builder.append("( ");
            convertRuleBody(groupElement.body(), builder, context);
            builder.append(" )");
        } else if (element instanceof OptionalElement optionalElement) {
            builder.append("[ ");
            convertRuleBody(optionalElement.body(), builder, context);
            builder.append(" ]");
        } else if (element instanceof RepeatElement repeatElement) {
            builder.append("{ ");
            convertRuleBody(repeatElement.body(), builder, context);
            builder.append(" }");
        } else if (element instanceof OneOrMoreElement oneOrMoreElement) {
            convertRuleBody(oneOrMoreElement.body(), builder, context);
            builder.append("+");
        } else if (element instanceof BoundedRepeatElement boundedRepeatElement) {
            convertRuleBody(boundedRepeatElement.body(), builder, context);
            builder.append("{");
            builder.append(boundedRepeatElement.min());
            if (boundedRepeatElement.max() != boundedRepeatElement.min()) {
                builder.append(",");
                if (boundedRepeatElement.max() != BoundedRepeatElement.UNBOUNDED) {
                    builder.append(boundedRepeatElement.max());
                }
            }
            builder.append("}");
        } else if (element instanceof TerminalElement terminalElement) {
            builder.append("\"");
            builder.append(terminalElement.value());
            builder.append("\"");
        } else if (element instanceof RuleRefElement ruleRefElement) {
            builder.append(ruleRefElement.name());
        } else if (element instanceof SeparatedElement separatedElement) {
            convertAtomicElement(separatedElement.element(), builder, context);
            builder.append(" % ");
            convertAtomicElement(separatedElement.separator(), builder, context);
        } else if (element instanceof ErrorElement errorElement) {
            builder.append("ERROR('");
            builder.append(errorElement.message().replace("'", "\\'"));
            builder.append("')");
        }
    }

    /**
     * 変換コンテキスト
     */
    private static final class ConversionContext {
        boolean keepAnnotations;

        ConversionContext(boolean keepAnnotations) {
            this.keepAnnotations = keepAnnotations;
        }
    }
}
