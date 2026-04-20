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

        IndentedWriter w = new IndentedWriter(0);

        w.line("package " + packageName + ";");
        w.blankLine();

        w.line("import java.io.IOException;");
        w.line("import java.nio.file.Files;");
        w.line("import java.nio.file.Path;");
        w.line("import java.util.ArrayList;");
        w.line("import java.util.HashSet;");
        w.line("import java.util.List;");
        w.line("import java.util.Map;");
        w.line("import java.util.Set;");
        w.line("import java.util.concurrent.CompletableFuture;");
        w.line("import org.eclipse.lsp4j.debug.*;");
        w.line("import org.eclipse.lsp4j.debug.services.*;");
        w.line("import org.unlaxer.Parsed;");
        w.line("import org.unlaxer.StringSource;");
        w.line("import org.unlaxer.Token;");
        w.line("import org.unlaxer.context.ParseContext;");
        w.line("import org.unlaxer.parser.Parser;");
        w.blankLine();

        w.line(CodeGenerator.generatedAnnotation("org.unlaxer.dsl.codegen.DAPGenerator").stripTrailing());
        w.line("public abstract class " + adapterClass + " implements IDebugProtocolServer {");
        w.blankLine();

        w.indent();

        // ----- Fields & connect -----
        DAPProtocolEmitter.emitFieldsAndConnect(w);

        // ----- DAP protocol methods -----
        DAPProtocolEmitter.emitInitialize(w);
        DAPProtocolEmitter.emitLaunch(w);
        DAPProtocolEmitter.emitConfigurationDone(w);
        DAPProtocolEmitter.emitSetBreakpoints(w);
        DAPProtocolEmitter.emitNext(w);
        DAPProtocolEmitter.emitContinue(w);
        DAPProtocolEmitter.emitThreads(w);
        DAPProtocolEmitter.emitStackTrace(w);
        DAPProtocolEmitter.emitScopes(w);
        DAPProtocolEmitter.emitVariables(w);
        DAPProtocolEmitter.emitDisconnect(w);

        // ----- Runtime / stepping methods -----
        DAPRuntimeEmitter.emitParseAndCollectSteps(w, parsersClass);
        DAPRuntimeEmitter.emitCollectRuntimeProbeVariables(w);
        DAPRuntimeEmitter.emitCreateRootSourceCompat(w);
        DAPRuntimeEmitter.emitAstMethods(w, packageName, grammarName, mapperClass);
        DAPRuntimeEmitter.emitStepHelpers(w);
        DAPRuntimeEmitter.emitCollectStepPoints(w);
        DAPRuntimeEmitter.emitBreakpointHelpers(w);

        // ----- Hook methods -----
        DAPRuntimeEmitter.emitHookMethods(w);

        // ----- Output utilities -----
        DAPRuntimeEmitter.emitOutputUtilities(w);

        w.dedent();
        w.line("}");

        return new GeneratedSource(packageName, adapterClass, w.build());
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
