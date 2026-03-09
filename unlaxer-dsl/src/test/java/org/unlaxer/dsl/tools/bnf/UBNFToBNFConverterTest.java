package org.unlaxer.dsl.tools.bnf;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST;

/**
 * UBNFToBNFConverter のテストクラス。
 *
 * grammar/ubnf.ubnf を入力として使用し、EBNF 変換が正しく行われることを検証する。
 */
public class UBNFToBNFConverterTest {

    /**
     * UBNF ファイルを読み込んで EBNF に変換し、
     * アノテーション除外（デフォルト動作）で期待される構文が含まれていることを検証。
     */
    @Test
    public void testConvertWithoutAnnotations() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("変換結果が空でないこと", false == result.isEmpty());

        assertTrue("UBNFFile ルール定義が含まれていること", result.contains("UBNFFile"));
        assertTrue("EBNF の '=' 記号が含まれていること", result.contains(" = "));
        assertTrue("ルール終端の ';' が含まれていること", result.contains(";"));
    }

    /**
     * アノテーション除外時、@mapping などの UBNF アノテーションが
     * EBNF 出力に含まれないことを検証。
     */
    @Test
    public void testAnnotationsAreStripped() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertFalse("@root アノテーションがコメント形式で削除されていること（keepAnnotations=false）", result.contains("(* @root *)"));
    }

    /**
     * keepAnnotations=true で、アノテーションがコメント形式で保持されることを検証。
     */
    @Test
    public void testAnnotationsArePreserved() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, true);

        assertTrue("アノテーションがコメント形式で保持されていること", result.contains("(* @root"));
        assertTrue("@mapping アノテーションがコメント形式で保持されていること", result.contains("(* @mapping"));
    }

    /**
     * キーワード（シングルクォート）がダブルクォートに変換されることを検証。
     */
    @Test
    public void testKeywordQuotesAreConverted() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("キーワード 'grammar' がダブルクォートに変換されていること", result.contains("\"grammar\""));
        assertTrue("括弧 '{' がダブルクォートで囲まれていること", result.contains("\"{\""));
        assertTrue("括弧 '}' がダブルクォートで囲まれていること", result.contains("\"}\""));
    }

    /**
     * ルール本体の連接がカンマ区切りで出力されることを検証。
     */
    @Test
    public void testSequenceUseCommaDelimiter() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("連接がカンマ区切りで出力されていること", result.contains(" , "));
    }

    /**
     * トークン宣言がコメント形式で出力されることを検証。
     */
    @Test
    public void testTokenDeclsAsComments() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("トークン宣言がコメント形式で出力されていること", result.contains("(* token:"));
    }

    /**
     * グローバル設定がコメント形式で出力されることを検証。
     */
    @Test
    public void testGlobalSettingsAsComments() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("グローバル設定がコメント形式で出力されていること", result.contains("(* @package:"));
    }

    /**
     * 選択（|）が正しく保持されることを検証。
     */
    @Test
    public void testChoicesArePreserved() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("選択演算子が保持されていること", result.contains(" | "));
    }

    /**
     * オプション要素 [X] が正しく保持されることを検証。
     */
    @Test
    public void testOptionalElementsArePreserved() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("オプション要素の開き括弧が保持されていること", result.contains("[ "));
        assertTrue("オプション要素の閉じ括弧が保持されていること", result.contains(" ]"));
    }

    /**
     * 繰り返し要素 {X} が正しく保持されることを検証。
     */
    @Test
    public void testRepeatElementsArePreserved() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("繰り返し要素の開き括弧が保持されていること", result.contains("{ "));
        assertTrue("繰り返し要素の閉じ括弧が保持されていること", result.contains(" }"));
    }

    /**
     * グループ要素 (X) が正しく保持されることを検証。
     */
    @Test
    public void testGroupElementsArePreserved() throws IOException {
        String ubnfSource = readUbnfGrammarFile();
        UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
        String result = UBNFToBNFConverter.convert(ast, false);

        assertTrue("グループ要素が保持されていること", result.contains("(") && result.contains(")"));
    }

    /**
     * UBNF 文法ファイルを読み込む。
     *
     * @return ubnf.ubnf ファイルの内容
     * @throws IOException ファイル読み込み失敗時
     */
    private String readUbnfGrammarFile() throws IOException {
        String filePath = "grammar/ubnf.ubnf";
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }
}
