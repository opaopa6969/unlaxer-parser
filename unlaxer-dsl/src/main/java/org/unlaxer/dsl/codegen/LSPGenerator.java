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

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");

        // ----- Imports -----
        LSPServerEmitter.emitImports(sb, hasScopeStore);

        sb.append("public abstract class ").append(serverClass)
          .append(" implements LanguageServer, LanguageClientAware {\n\n");

        // ----- Fields & constructor -----
        LSPServerEmitter.emitFieldsAndConstructor(sb, serverClass, keywords, hasCatalog);

        // ----- Lifecycle methods -----
        LSPServerEmitter.emitLifecycleMethods(sb, serverClass, hasCatalog);

        // ----- parseDocument & utilities -----
        LSPServerEmitter.emitParseDocument(sb, parsersClass, hasScopeStore);

        // ----- Hook methods -----
        LSPServerEmitter.emitHookMethods(sb);

        // ----- Catalog methods -----
        if (hasCatalog) {
            LSPServerEmitter.emitCatalogMethods(sb, grammarName);
        }

        // ----- Records -----
        LSPServerEmitter.emitRecords(sb);

        // ----- TextDocumentService inner class -----
        LSPServerEmitter.emitTextDocumentService(sb, serverClass, grammarName);

        // ----- WorkspaceService inner class -----
        LSPServerEmitter.emitWorkspaceService(sb, serverClass);

        // ----- Catalog interfaces -----
        if (hasCatalog) {
            LSPServerEmitter.emitCatalogInterfaces(sb);
        }

        sb.append("}\n");

        return new GeneratedSource(packageName, serverClass, sb.toString());
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
