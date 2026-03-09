package org.unlaxer.tinycalc.generated;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

/**
 * TinyCalc パースツリー（Token）を TinyCalcAST ノードに変換するマッパー。
 */
public class TinyCalcMapper {

    private TinyCalcMapper() {}

    // =========================================================================
    // エントリーポイント
    // =========================================================================

    /**
     * TinyCalc ソース文字列をパースして AST に変換する。
     *
     * @param source TinyCalc ソース文字列
     * @return パース済み TinyCalcProgram
     * @throws IllegalArgumentException パースに失敗した場合
     */
    public static TinyCalcAST.TinyCalcProgram parse(String source) {
        StringSource stringSource = StringSource.createRootSource(source);
        try (ParseContext context = new ParseContext(stringSource)) {
            Parser rootParser = TinyCalcParsers.getRootParser();
            Parsed parsed = rootParser.parse(context);
            if (!parsed.isSucceeded()) {
                throw new IllegalArgumentException("TinyCalc パース失敗: " + source);
            }
            return toTinyCalcProgram(parsed.getRootToken());
        }
    }

    // =========================================================================
    // トークン → AST 変換
    // =========================================================================

    /**
     * TinyCalcParser トークン → TinyCalcProgram
     * filteredChildren: [ZeroOrMore(VariableDeclaration)-token, ExpressionParser-token]
     */
    static TinyCalcAST.TinyCalcProgram toTinyCalcProgram(Token token) {
        List<Token> children = token.filteredChildren;
        // children[0] = ZeroOrMore(VariableDeclarationParser) token
        Token varDeclListToken = children.get(0);
        List<TinyCalcAST.VarDecl> declarations = varDeclListToken.filteredChildren
            .stream()
            .map(TinyCalcMapper::toVarDecl)
            .toList();

        // children[1] = ExpressionParser token
        Token exprToken = children.get(1);
        TinyCalcAST expression = toExprOrLeaf(exprToken);

        return new TinyCalcAST.TinyCalcProgram(declarations, expression);
    }

    /**
     * VariableDeclarationParser トークン → VarDecl
     * filteredChildren: [Group0(keyword), IdentifierParser(name), Optional(Opt0), WordParser(";")]
     */
    static TinyCalcAST.VarDecl toVarDecl(Token token) {
        List<Token> children = token.filteredChildren;
        String keyword = children.get(0).source.toString().trim();
        String name = children.get(1).source.toString().trim();

        Token optToken = children.get(2); // Optional(VariableDeclarationOpt0Parser)
        Optional<TinyCalcAST> init = Optional.empty();
        if (!optToken.filteredChildren.isEmpty()) {
            // opt0Children: [WordParser("set"), ExpressionParser]
            Token opt0Token = optToken.filteredChildren.get(0);
            List<Token> opt0Children = opt0Token.filteredChildren;
            Token initExprToken = opt0Children.get(1); // ExpressionParser
            init = Optional.of(toExprOrLeaf(initExprToken));
        }

        return new TinyCalcAST.VarDecl(keyword, name, init);
    }

    /**
     * ExpressionParser トークン → BinaryExpr or leaf
     * filteredChildren: [TermParser-token, ZeroOrMore(ExpressionRepeat0Parser)-token]
     */
    static TinyCalcAST toExprOrLeaf(Token token) {
        List<Token> children = token.filteredChildren;
        Token leftTermToken = children.get(0);
        TinyCalcAST left = toTermOrLeaf(leftTermToken);

        Token repeatsToken = children.get(1); // ZeroOrMore(ExpressionRepeat0Parser)
        List<Token> repeats = repeatsToken.filteredChildren;

        if (repeats.isEmpty()) {
            return left;
        }

        List<String> ops = new ArrayList<>();
        List<TinyCalcAST> rights = new ArrayList<>();
        for (Token repeat : repeats) {
            // ExpressionRepeat0Parser filteredChildren: [ExpressionGroup0Parser-token, TermParser-token]
            List<Token> repeatChildren = repeat.filteredChildren;
            ops.add(repeatChildren.get(0).source.toString().trim());
            rights.add(toTermOrLeaf(repeatChildren.get(1)));
        }

        return new TinyCalcAST.BinaryExpr(left, ops, rights);
    }

    /**
     * TermParser トークン → BinaryExpr or leaf
     * filteredChildren: [FactorParser-token, ZeroOrMore(TermRepeat0Parser)-token]
     */
    static TinyCalcAST toTermOrLeaf(Token token) {
        List<Token> children = token.filteredChildren;
        Token leftFactorToken = children.get(0);
        TinyCalcAST left = toFactor(leftFactorToken);

        Token repeatsToken = children.get(1); // ZeroOrMore(TermRepeat0Parser)
        List<Token> repeats = repeatsToken.filteredChildren;

        if (repeats.isEmpty()) {
            return left;
        }

        List<String> ops = new ArrayList<>();
        List<TinyCalcAST> rights = new ArrayList<>();
        for (Token repeat : repeats) {
            // TermRepeat0Parser filteredChildren: [TermGroup0Parser-token, FactorParser-token]
            List<Token> repeatChildren = repeat.filteredChildren;
            ops.add(repeatChildren.get(0).source.toString().trim());
            rights.add(toFactor(repeatChildren.get(1)));
        }

        return new TinyCalcAST.BinaryExpr(left, ops, rights);
    }

    /**
     * FactorParser トークン（LazyChoice）→ TinyCalcAST leaf または括弧式
     *
     * <p>選択肢:
     * <ol>
     *   <li>'(' Expression ')' → toExprOrLeaf(ExpressionParser 子)</li>
     *   <li>NumberParser → NumberLiteral</li>
     *   <li>IdentifierParser → VariableRef</li>
     * </ol>
     */
    static TinyCalcAST toFactor(Token factorToken) {
        // 括弧式 '(' Expression ')' かどうか: ExpressionParser の子孫があるかで判定
        List<Token> exprChildren = findDescendants(factorToken, TinyCalcParsers.ExpressionParser.class);
        if (!exprChildren.isEmpty()) {
            return toExprOrLeaf(exprChildren.get(0));
        }

        // 数値リテラル
        List<Token> numChildren = findDescendants(factorToken, org.unlaxer.parser.elementary.NumberParser.class);
        if (!numChildren.isEmpty()) {
            double value = Double.parseDouble(numChildren.get(0).source.toString().trim());
            return new TinyCalcAST.NumberLiteral(value);
        }

        // 識別子（変数参照）
        List<Token> idChildren = findDescendants(factorToken, org.unlaxer.parser.clang.IdentifierParser.class);
        if (!idChildren.isEmpty()) {
            String name = idChildren.get(0).source.toString().trim();
            return new TinyCalcAST.VariableRef(name);
        }

        // フォールバック: ソーステキストをそのまま数値として試みる
        try {
            double value = Double.parseDouble(factorToken.source.toString().trim());
            return new TinyCalcAST.NumberLiteral(value);
        } catch (NumberFormatException e) {
            return new TinyCalcAST.VariableRef(factorToken.source.toString().trim());
        }
    }

    // =========================================================================
    // ユーティリティ
    // =========================================================================

    /**
     * token の filteredChildren を再帰的に探索し、指定したパーサークラスにマッチする
     * トークンを返す。マッチしたトークンの子孫は探索しない（浅い探索）。
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
}
