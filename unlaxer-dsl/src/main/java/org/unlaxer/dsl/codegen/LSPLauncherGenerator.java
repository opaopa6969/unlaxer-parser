package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

/**
 * GrammarDecl から {Name}LspLauncher.java を生成する。
 */
public class LSPLauncherGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String launcherClass = grammarName + "LspLauncher";
        String serverClass = grammarName + "LanguageServer";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");

        sb.append("import java.io.IOException;\n");
        sb.append("import org.eclipse.lsp4j.launch.LSPLauncher;\n");
        sb.append("import org.eclipse.lsp4j.services.LanguageClient;\n");
        sb.append("import org.eclipse.lsp4j.jsonrpc.Launcher;\n");
        sb.append("\n");

        sb.append("public class ").append(launcherClass).append(" {\n");
        sb.append("    public static void main(String[] args) throws IOException {\n");
        sb.append("        ").append(serverClass).append(" server = new ").append(serverClass).append("();\n");
        sb.append("        Launcher<LanguageClient> launcher =\n");
        sb.append("            LSPLauncher.createServerLauncher(server, System.in, System.out);\n");
        sb.append("        server.connect(launcher.getRemoteProxy());\n");
        sb.append("        launcher.startListening();\n");
        sb.append("    }\n");
        sb.append("}\n");

        return new GeneratedSource(packageName, launcherClass, sb.toString());
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
