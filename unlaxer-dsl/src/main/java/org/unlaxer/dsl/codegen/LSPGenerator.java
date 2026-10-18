package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.BackrefAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.CatalogAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.DeclaresAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.ScopeTreeAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;

import java.util.List;

/**
 * GrammarDecl から {Name}LanguageServer.java を生成する。
 */
public class LSPGenerator implements CodeGenerator {

    @Override
    public GeneratedSource generate(GrammarDecl grammar) {
        org.unlaxer.dsl.bootstrap.RegexRetirement.requireValid(grammar);
        String packageName = getPackageName(grammar);
        String grammarName = grammar.name();
        String serverClass = grammarName + "LanguageServer";
        String parsersClass = grammarName + "Parsers";

        List<String> keywords = LSPServerEmitter.collectKeywords(grammar);
        boolean hasCatalog = grammar.rules().stream()
            .anyMatch(r -> r.annotations().stream().anyMatch(a -> a instanceof CatalogAnnotation));
        boolean hasScopeStore = grammar.rules().stream()
            .anyMatch(r -> r.annotations().stream().anyMatch(
                a -> a instanceof DeclaresAnnotation
                  || a instanceof BackrefAnnotation
                  || a instanceof ScopeTreeAnnotation));

        IndentedWriter w = new IndentedWriter(0);
        w.line("package " + packageName + ";");
        w.blankLine();

        // ----- Imports -----
        LSPServerEmitter.emitImports(w, hasScopeStore);

        w.line(CodeGenerator.generatedAnnotation("org.unlaxer.dsl.codegen.LSPGenerator").stripTrailing());
        w.line("public abstract class " + serverClass
              + " implements LanguageServer, LanguageClientAware {");
        w.blankLine();

        w.indent();

        // ----- Fields & constructor -----
        LSPServerEmitter.emitFieldsAndConstructor(w, serverClass, keywords, hasCatalog);

        // ----- Lifecycle methods -----
        LSPServerEmitter.emitLifecycleMethods(w, serverClass, hasCatalog);
        LSPServerEmitter.emitProfileMethods(w, grammar);
        LSPQueryEmitter.emit(w);
        LSPDiagnosticEmitter.emit(w);

        // ----- parseDocument & utilities -----
        LSPServerEmitter.emitParseDocument(w, parsersClass, hasScopeStore);
        w.line("protected boolean parseCacheEnabled() { return " + snapshotCacheable(grammar) + "; }");

        // ----- Hook methods -----
        LSPServerEmitter.emitHookMethods(w);

        // ----- Catalog methods -----
        if (hasCatalog) {
            LSPServerEmitter.emitCatalogMethods(w, grammarName);
        }

        // ----- Records -----
        LSPServerEmitter.emitRecords(w);

        // ----- TextDocumentService inner class -----
        LSPServerEmitter.emitTextDocumentService(w, serverClass, grammarName);

        // ----- WorkspaceService inner class -----
        LSPServerEmitter.emitWorkspaceService(w, serverClass);

        // ----- Catalog interfaces -----
        if (hasCatalog) {
            LSPServerEmitter.emitCatalogInterfaces(w);
        }

        w.dedent();
        w.line("}");

        return new GeneratedSource(packageName, serverClass, w.build());
    }

    /** External parser callbacks need an explicit host contract; retry-safe is not cache-safe. */
    private boolean snapshotCacheable(GrammarDecl grammar) {
        if (!grammar.imports().isEmpty()) return false;
        for (var token : grammar.tokens()) {
            if (token instanceof org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Simple simple) {
                if (!simple.parserClass().contains(".")
                    || !org.unlaxer.dsl.codegen.rust.RustGrammarLowering.supportsSimpleToken(simple.parserClass())) return false;
            } else if (token instanceof org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl.Adapter adapter) {
                if (!org.unlaxer.dsl.bootstrap.TokenAdapterRegistry.isBuiltin(adapter.id(),Integer.parseInt(adapter.version()))) return false;
            }
        }
        return true;
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
