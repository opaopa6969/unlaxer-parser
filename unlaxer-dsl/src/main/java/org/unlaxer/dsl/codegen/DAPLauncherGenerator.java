package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

/**
 * GrammarDecl から {Name}DapLauncher.java を生成する。
 *
 * stdio 経由で DAP サーバーを起動する main クラス。
 * 起動方法: java --enable-preview -cp server.jar {package}.{Name}DapLauncher
 */
public class DAPLauncherGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String launcherClass = grammarName + "DapLauncher";
        String adapterClass = grammarName + "DebugAdapter";

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");

        sb.append("import java.io.IOException;\n");
        sb.append("import org.eclipse.lsp4j.debug.launch.DSPLauncher;\n");
        sb.append("import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;\n");
        sb.append("import org.eclipse.lsp4j.jsonrpc.Launcher;\n");
        sb.append("\n");

        sb.append("public class ").append(launcherClass).append(" {\n");
        sb.append("    public static void main(String[] args) throws IOException {\n");
        sb.append("        ").append(adapterClass).append(" adapter = new ").append(adapterClass).append("();\n");
        sb.append("        Launcher<IDebugProtocolClient> launcher =\n");
        sb.append("            DSPLauncher.createServerLauncher(adapter, System.in, System.out);\n");
        sb.append("        adapter.connect(launcher.getRemoteProxy());\n");
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
