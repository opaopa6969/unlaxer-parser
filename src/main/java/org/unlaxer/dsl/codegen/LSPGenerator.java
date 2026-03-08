package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        List<String> keywords = collectKeywords(grammar);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");

        sb.append("import java.util.*;\n");
        sb.append("import java.util.concurrent.CompletableFuture;\n");
        sb.append("import org.eclipse.lsp4j.*;\n");
        sb.append("import org.eclipse.lsp4j.jsonrpc.messages.Either;\n");
        sb.append("import org.eclipse.lsp4j.services.*;\n");
        sb.append("import org.unlaxer.Parsed;\n");
        sb.append("import org.unlaxer.StringSource;\n");
        sb.append("import org.unlaxer.context.ParseContext;\n");
        sb.append("import org.unlaxer.parser.Parser;\n");
        sb.append("\n");

        sb.append("public class ").append(serverClass)
          .append(" implements LanguageServer, LanguageClientAware {\n\n");

        // KEYWORDS field
        sb.append("    private static final List<String> KEYWORDS = List.of(");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(keywords.get(i)).append("\"");
        }
        sb.append(");\n\n");

        sb.append("    private LanguageClient client;\n");
        sb.append("    private final Map<String, DocumentState> documents = new HashMap<>();\n\n");

        // Constructor
        sb.append("    public ").append(serverClass).append("() {}\n\n");

        // initialize()
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {\n");
        sb.append("        ServerCapabilities capabilities = new ServerCapabilities();\n");
        sb.append("        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);\n");
        sb.append("        CompletionOptions completionOptions = new CompletionOptions();\n");
        sb.append("        completionOptions.setResolveProvider(false);\n");
        sb.append("        capabilities.setCompletionProvider(completionOptions);\n");
        sb.append("        capabilities.setHoverProvider(true);\n");
        sb.append("        SemanticTokensWithRegistrationOptions semanticTokensOptions =\n");
        sb.append("            new SemanticTokensWithRegistrationOptions();\n");
        sb.append("        semanticTokensOptions.setFull(true);\n");
        sb.append("        semanticTokensOptions.setLegend(new SemanticTokensLegend(\n");
        sb.append("            List.of(\"valid\", \"invalid\"), List.of()));\n");
        sb.append("        capabilities.setSemanticTokensProvider(semanticTokensOptions);\n");
        sb.append("        return CompletableFuture.completedFuture(new InitializeResult(capabilities));\n");
        sb.append("    }\n\n");

        // shutdown()
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<Object> shutdown() {\n");
        sb.append("        return CompletableFuture.completedFuture(null);\n");
        sb.append("    }\n\n");

        // exit()
        sb.append("    @Override\n");
        sb.append("    public void exit() {}\n\n");

        // setTrace()
        sb.append("    @Override\n");
        sb.append("    public void setTrace(SetTraceParams params) {}\n\n");

        // connect()
        sb.append("    @Override\n");
        sb.append("    public void connect(LanguageClient client) {\n");
        sb.append("        this.client = client;\n");
        sb.append("    }\n\n");

        // getTextDocumentService()
        sb.append("    @Override\n");
        sb.append("    public TextDocumentService getTextDocumentService() {\n");
        sb.append("        return new ").append(serverClass).append("TextDocumentService(this);\n");
        sb.append("    }\n\n");

        // getWorkspaceService()
        sb.append("    @Override\n");
        sb.append("    public WorkspaceService getWorkspaceService() {\n");
        sb.append("        return new ").append(serverClass).append("WorkspaceService();\n");
        sb.append("    }\n\n");

        // parseDocument()
        sb.append("    public ParseResult parseDocument(String uri, String content) {\n");
        sb.append("        Parser parser = ").append(parsersClass).append(".getRootParser();\n");
        sb.append("        ParseContext context = new ParseContext(createRootSourceCompat(content));\n");
        sb.append("        Parsed result = parser.parse(context);\n");
        sb.append("        int consumedLength = 0;\n");
        sb.append("        if (result.isSucceeded()) {\n");
        sb.append("            consumedLength = result.getConsumed().source.sourceAsString().length();\n");
        sb.append("        }\n");
        sb.append("        context.close();\n");
        sb.append("        ParseResult parseResult = new ParseResult(\n");
        sb.append("            result.isSucceeded(), consumedLength, content.length());\n");
        sb.append("        documents.put(uri, new DocumentState(uri, content, parseResult));\n");
        sb.append("        if (client != null) {\n");
        sb.append("            publishDiagnostics(uri, content, parseResult);\n");
        sb.append("        }\n");
        sb.append("        return parseResult;\n");
        sb.append("    }\n\n");

        sb.append("    private static StringSource createRootSourceCompat(String source) {\n");
        sb.append("        try {\n");
        sb.append("            java.lang.reflect.Method m = StringSource.class.getMethod(\"createRootSource\", String.class);\n");
        sb.append("            Object v = m.invoke(null, source);\n");
        sb.append("            if (v instanceof StringSource s) {\n");
        sb.append("                return s;\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        try {\n");
        sb.append("            for (java.lang.reflect.Constructor<?> c : StringSource.class.getDeclaredConstructors()) {\n");
        sb.append("                Class<?>[] types = c.getParameterTypes();\n");
        sb.append("                if (types.length == 0 || types[0] != String.class) {\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                Object[] args = new Object[types.length];\n");
        sb.append("                args[0] = source;\n");
        sb.append("                c.setAccessible(true);\n");
        sb.append("                Object v = c.newInstance(args);\n");
        sb.append("                if (v instanceof StringSource s) {\n");
        sb.append("                    return s;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        throw new IllegalStateException(\"No compatible StringSource initializer found\");\n");
        sb.append("    }\n\n");

        // publishDiagnostics()
        sb.append("    private void publishDiagnostics(String uri, String content, ParseResult result) {\n");
        sb.append("        List<Diagnostic> diagnostics = new ArrayList<>();\n");
        sb.append("        if (result.consumedLength() < result.totalLength()) {\n");
        sb.append("            int errorStart = result.consumedLength();\n");
        sb.append("            Position startPos = offsetToPosition(content, errorStart);\n");
        sb.append("            Position endPos = offsetToPosition(content, result.totalLength());\n");
        sb.append("            Diagnostic diagnostic = new Diagnostic();\n");
        sb.append("            diagnostic.setRange(new Range(startPos, endPos));\n");
        sb.append("            diagnostic.setSeverity(DiagnosticSeverity.Error);\n");
        sb.append("            diagnostic.setMessage(\"Parse error at offset \" + errorStart);\n");
        sb.append("            diagnostics.add(diagnostic);\n");
        sb.append("        }\n");
        sb.append("        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));\n");
        sb.append("    }\n\n");

        // offsetToPosition()
        sb.append("    private Position offsetToPosition(String content, int offset) {\n");
        sb.append("        int line = 0;\n");
        sb.append("        int column = 0;\n");
        sb.append("        for (int i = 0; i < offset && i < content.length(); i++) {\n");
        sb.append("            if (content.charAt(i) == '\\n') {\n");
        sb.append("                line++;\n");
        sb.append("                column = 0;\n");
        sb.append("            } else {\n");
        sb.append("                column++;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return new Position(line, column);\n");
        sb.append("    }\n\n");

        // DocumentState record
        sb.append("    public record DocumentState(String uri, String content, ParseResult parseResult) {}\n\n");

        // ParseResult record
        sb.append("    public record ParseResult(boolean succeeded, int consumedLength, int totalLength) {}\n\n");

        // TextDocumentService inner class
        sb.append("    static class ").append(serverClass).append("TextDocumentService implements TextDocumentService {\n\n");
        sb.append("        private final ").append(serverClass).append(" server;\n\n");
        sb.append("        ").append(serverClass).append("TextDocumentService(").append(serverClass).append(" server) {\n");
        sb.append("            this.server = server;\n");
        sb.append("        }\n\n");

        sb.append("        @Override\n");
        sb.append("        public void didOpen(DidOpenTextDocumentParams params) {\n");
        sb.append("            server.parseDocument(\n");
        sb.append("                params.getTextDocument().getUri(),\n");
        sb.append("                params.getTextDocument().getText());\n");
        sb.append("        }\n\n");

        sb.append("        @Override\n");
        sb.append("        public void didChange(DidChangeTextDocumentParams params) {\n");
        sb.append("            server.parseDocument(\n");
        sb.append("                params.getTextDocument().getUri(),\n");
        sb.append("                params.getContentChanges().get(0).getText());\n");
        sb.append("        }\n\n");

        sb.append("        @Override\n");
        sb.append("        public void didClose(DidCloseTextDocumentParams params) {\n");
        sb.append("            server.documents.remove(params.getTextDocument().getUri());\n");
        sb.append("        }\n\n");

        sb.append("        @Override\n");
        sb.append("        public void didSave(DidSaveTextDocumentParams params) {}\n\n");

        // completion()
        sb.append("        @Override\n");
        sb.append("        public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(\n");
        sb.append("                CompletionParams params) {\n");
        sb.append("            List<CompletionItem> items = new ArrayList<>();\n");
        sb.append("            for (String kw : KEYWORDS) {\n");
        sb.append("                CompletionItem item = new CompletionItem(kw);\n");
        sb.append("                item.setKind(CompletionItemKind.Keyword);\n");
        sb.append("                items.add(item);\n");
        sb.append("            }\n");
        sb.append("            return CompletableFuture.completedFuture(Either.forLeft(items));\n");
        sb.append("        }\n\n");

        // hover()
        sb.append("        @Override\n");
        sb.append("        public CompletableFuture<Hover> hover(HoverParams params) {\n");
        sb.append("            String uri = params.getTextDocument().getUri();\n");
        sb.append("            DocumentState state = server.documents.get(uri);\n");
        sb.append("            if (state == null) {\n");
        sb.append("                return CompletableFuture.completedFuture(null);\n");
        sb.append("            }\n");
        sb.append("            String text;\n");
        sb.append("            if (state.parseResult().succeeded() &&\n");
        sb.append("                    state.parseResult().consumedLength() == state.parseResult().totalLength()) {\n");
        sb.append("                text = \"Valid ").append(grammarName).append("\";\n");
        sb.append("            } else {\n");
        sb.append("                text = \"Parse error at offset \" + state.parseResult().consumedLength();\n");
        sb.append("            }\n");
        sb.append("            MarkupContent content = new MarkupContent();\n");
        sb.append("            content.setKind(\"plaintext\");\n");
        sb.append("            content.setValue(text);\n");
        sb.append("            return CompletableFuture.completedFuture(new Hover(content));\n");
        sb.append("        }\n\n");

        // semanticTokensFull()
        sb.append("        @Override\n");
        sb.append("        public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {\n");
        sb.append("            String uri = params.getTextDocument().getUri();\n");
        sb.append("            DocumentState state = server.documents.get(uri);\n");
        sb.append("            if (state == null) {\n");
        sb.append("                return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));\n");
        sb.append("            }\n");
        sb.append("            return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // WorkspaceService inner class
        sb.append("    static class ").append(serverClass).append("WorkspaceService implements WorkspaceService {\n\n");
        sb.append("        @Override\n");
        sb.append("        public void didChangeConfiguration(org.eclipse.lsp4j.DidChangeConfigurationParams params) {}\n\n");
        sb.append("        @Override\n");
        sb.append("        public void didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}\n");
        sb.append("    }\n");

        sb.append("}\n");

        return new GeneratedSource(packageName, serverClass, sb.toString());
    }

    private List<String> collectKeywords(GrammarDecl grammar) {
        Set<String> kw = new LinkedHashSet<>();
        kw.add("grammar");
        kw.add("token");
        kw.add("@root");
        kw.add("@mapping");
        kw.add("@whitespace");
        kw.add("@interleave");
        kw.add("@backref");
        kw.add("@typeof");
        kw.add("@scopeTree");
        kw.add("@leftAssoc");
        kw.add("@rightAssoc");
        kw.add("@precedence");
        kw.add("params");
        kw.add("level");
        kw.add("profile");
        kw.add("name");
        kw.add("mode");
        for (RuleDecl rule : grammar.rules()) {
            collectFromBody(rule.body(), kw);
        }
        return new ArrayList<>(kw);
    }

    private void collectFromBody(RuleBody body, Set<String> acc) {
        switch (body) {
            case ChoiceBody cb -> {
                for (var alt : cb.alternatives()) collectFromBody(alt, acc);
            }
            case SequenceBody sb -> {
                for (AnnotatedElement ae : sb.elements()) collectFromElement(ae.element(), acc);
            }
        }
    }

    private void collectFromElement(AtomicElement element, Set<String> acc) {
        switch (element) {
            case TerminalElement t -> acc.add(stripQuotes(t.value()));
            case GroupElement g -> collectFromBody(g.body(), acc);
            case OptionalElement o -> collectFromBody(o.body(), acc);
            case RepeatElement r -> collectFromBody(r.body(), acc);
            default -> {}
        }
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String getPackageName(GrammarDecl grammar) {
        return grammar.settings().stream()
            .filter(s -> "package".equals(s.key()))
            .map(s -> s.value() instanceof StringSettingValue sv ? sv.value() : "")
            .findFirst()
            .orElse("generated");
    }
}
