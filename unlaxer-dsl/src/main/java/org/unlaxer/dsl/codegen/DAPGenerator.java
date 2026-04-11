package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

/**
 * GrammarDecl から {Name}DebugAdapter.java を生成する。
 *
 * 生成されるデバッグアダプターは DAP (Debug Adapter Protocol) over stdio で動作する。
 *
 * stopOnEntry: false (デフォルト)
 *   launch → configurationDone → parse → 結果を Debug Console に出力 → terminated
 *
 * stopOnEntry: true (ステップ実行)
 *   launch → configurationDone → parse → stopped(entry)
 *   → [F10] next → stopped(step) → ... → terminated
 *   → [F5]  continue → terminated
 *   stackTrace: 現在トークンの行/列をエディタで強調
 *   variables:  現在トークンのテキストと parser 名を表示
 */
public class DAPGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String adapterClass = grammarName + "DebugAdapter";
        String parsersClass = grammarName + "Parsers";
        String mapperClass = grammarName + "Mapper";

        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(packageName).append(";\n\n");

        sb.append("import java.io.IOException;\n");
        sb.append("import java.nio.file.Files;\n");
        sb.append("import java.nio.file.Path;\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.HashSet;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n");
        sb.append("import java.util.Set;\n");
        sb.append("import java.util.concurrent.CompletableFuture;\n");
        sb.append("import org.eclipse.lsp4j.debug.*;\n");
        sb.append("import org.eclipse.lsp4j.debug.services.*;\n");
        sb.append("import org.unlaxer.Parsed;\n");
        sb.append("import org.unlaxer.StringSource;\n");
        sb.append("import org.unlaxer.Token;\n");
        sb.append("import org.unlaxer.context.ParseContext;\n");
        sb.append("import org.unlaxer.parser.Parser;\n");
        sb.append("\n");

        sb.append("public abstract class ").append(adapterClass).append(" implements IDebugProtocolServer {\n\n");

        // ----- Fields & connect -----
        DAPProtocolEmitter.emitFieldsAndConnect(sb);

        // ----- DAP protocol methods -----
        DAPProtocolEmitter.emitInitialize(sb);
        DAPProtocolEmitter.emitLaunch(sb);
        DAPProtocolEmitter.emitConfigurationDone(sb);
        DAPProtocolEmitter.emitSetBreakpoints(sb);
        DAPProtocolEmitter.emitNext(sb);
        DAPProtocolEmitter.emitContinue(sb);
        DAPProtocolEmitter.emitThreads(sb);
        DAPProtocolEmitter.emitStackTrace(sb);
        DAPProtocolEmitter.emitScopes(sb);
        DAPProtocolEmitter.emitVariables(sb);
        DAPProtocolEmitter.emitDisconnect(sb);

        // ----- Runtime / stepping methods -----
        DAPRuntimeEmitter.emitParseAndCollectSteps(sb, parsersClass);
        DAPRuntimeEmitter.emitCollectRuntimeProbeVariables(sb);
        DAPRuntimeEmitter.emitCreateRootSourceCompat(sb);
        DAPRuntimeEmitter.emitAstMethods(sb, packageName, grammarName, mapperClass);
        DAPRuntimeEmitter.emitStepHelpers(sb);
        DAPRuntimeEmitter.emitCollectStepPoints(sb);
        DAPRuntimeEmitter.emitBreakpointHelpers(sb);

        // ----- Hook methods -----
        DAPRuntimeEmitter.emitHookMethods(sb);

        // ----- Output utilities -----
        DAPRuntimeEmitter.emitOutputUtilities(sb);

        sb.append("}\n");

        return new GeneratedSource(packageName, adapterClass, sb.toString());
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
