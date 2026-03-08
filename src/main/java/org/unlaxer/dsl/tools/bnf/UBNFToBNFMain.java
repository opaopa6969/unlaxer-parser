package org.unlaxer.dsl.tools.bnf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST;

/**
 * UBNF ファイルを EBNF 形式に変換するコマンドラインツール。
 *
 * 使用方法:
 * <pre>
 *   java UBNFToBNFMain input.ubnf [--keep-annotations]
 * </pre>
 */
public final class UBNFToBNFMain {

    private UBNFToBNFMain() {}

    /**
     * エントリーポイント。
     *
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String inputFilePath = args[0];
        boolean keepAnnotations = false;

        if (args.length > 1) {
            if ("--keep-annotations".equals(args[1])) {
                keepAnnotations = true;
            }
        }

        try {
            String ubnfSource = readFile(inputFilePath);
            UBNFAST.UBNFFile ast = UBNFMapper.parse(ubnfSource);
            String ebnfOutput = UBNFToBNFConverter.convert(ast, keepAnnotations);
            System.out.println(ebnfOutput);
        } catch (IOException ioException) {
            System.err.println("エラー: ファイル読み込み失敗: " + inputFilePath);
            ioException.printStackTrace();
            System.exit(2);
        } catch (IllegalArgumentException parseException) {
            System.err.println("エラー: UBNF パース失敗");
            parseException.printStackTrace();
            System.exit(3);
        } catch (Exception exception) {
            System.err.println("エラー: 予期しない例外");
            exception.printStackTrace();
            System.exit(4);
        }
    }

    /**
     * ファイルの全内容を読み込む。
     *
     * @param filePath ファイルパス
     * @return ファイルの文字列内容
     * @throws IOException ファイル読み込み失敗時
     */
    private static String readFile(String filePath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(filePath)));
    }

    /**
     * 使用方法を表示する。
     */
    private static void printUsage() {
        System.err.println("使用方法: java UBNFToBNFMain <input.ubnf> [--keep-annotations]");
        System.err.println("");
        System.err.println("オプション:");
        System.err.println("  --keep-annotations  アノテーションをコメントとして保持する");
    }
}
