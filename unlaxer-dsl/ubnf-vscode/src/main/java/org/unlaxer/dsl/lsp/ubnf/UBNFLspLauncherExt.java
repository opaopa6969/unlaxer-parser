package org.unlaxer.dsl.lsp.ubnf;

import java.io.IOException;

import org.unlaxer.dsl.bootstrap.generated.UBNFLanguageServer;
import org.unlaxer.dsl.bootstrap.generated.UBNFLspLauncher;

/**
 * リッチ版 UBNF LSP サーバーのエントリポイント (stdio)。
 * shade jar の Main-Class はここを指す。
 */
public final class UBNFLspLauncherExt extends UBNFLspLauncher {

    @Override
    protected UBNFLanguageServer createServer() {
        return new UBNFLanguageServerExt();
    }

    public static void main(String[] args) throws IOException {
        new UBNFLspLauncherExt().launch();
    }
}
