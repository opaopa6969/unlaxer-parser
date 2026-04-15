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
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LSP サーバークラスの各メソッド・内部クラスのコード生成を担当する。
 */
class LSPServerEmitter {

    private LSPServerEmitter() {}

    /** サーバークラスの import 文を出力する。 */
    static void emitImports(IndentedWriter w, boolean hasScopeStore) {
        w.line("import java.util.*;");
        w.line("import java.util.concurrent.CompletableFuture;");
        w.line("import org.eclipse.lsp4j.*;");
        w.line("import org.eclipse.lsp4j.jsonrpc.messages.Either;");
        w.line("import org.eclipse.lsp4j.services.*;");
        w.line("import org.unlaxer.Parsed;");
        w.line("import org.unlaxer.StringSource;");
        w.line("import org.unlaxer.context.ParseContext;");
        w.line("import org.unlaxer.parser.Parser;");
        w.line("import org.unlaxer.parser.incremental.IncrementalParseCache;");
        if (hasScopeStore) {
            w.line("import org.unlaxer.dsl.runtime.ScopeStore;");
        }
        w.blankLine();
    }

    /** KEYWORDS フィールド、client フィールド、documents フィールドを出力する。 */
    static void emitFieldsAndConstructor(IndentedWriter w, String serverClass, List<String> keywords,
            boolean hasCatalog) {
        // KEYWORDS field
        StringBuilder kwBuf = new StringBuilder("private static final List<String> KEYWORDS = List.of(");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) kwBuf.append(", ");
            kwBuf.append("\"").append(keywords.get(i)).append("\"");
        }
        kwBuf.append(");");
        w.line(kwBuf.toString());
        w.blankLine();

        w.line("protected LanguageClient client;");
        w.line("protected final Map<String, DocumentState> documents = new HashMap<>();");
        w.blankLine();

        // Catalog infrastructure fields (when @catalog annotation present)
        if (hasCatalog) {
            w.line("protected volatile CatalogResolver catalogResolver = null;");
            w.blankLine();
            w.line("protected void setCatalogResolver(CatalogResolver r) { this.catalogResolver = r; }");
            w.blankLine();
        }

        // Constructor
        w.line("public " + serverClass + "() {}");
        w.blankLine();
    }

    /** initialize(), shutdown(), exit(), setTrace(), connect() を出力する。 */
    static void emitLifecycleMethods(IndentedWriter w, String serverClass, boolean hasCatalog) {
        // initialize()
        w.line("@Override");
        w.line("public CompletableFuture<InitializeResult> initialize(InitializeParams params) {");
        w.indent();
        if (hasCatalog) {
            w.line("initCatalogResolver(params);");
        }
        w.line("ServerCapabilities capabilities = new ServerCapabilities();");
        w.line("capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);");
        w.line("CompletionOptions completionOptions = new CompletionOptions();");
        w.line("completionOptions.setResolveProvider(false);");
        w.line("capabilities.setCompletionProvider(completionOptions);");
        w.line("capabilities.setHoverProvider(true);");
        w.line("SemanticTokensWithRegistrationOptions semanticTokensOptions =");
        w.line("    new SemanticTokensWithRegistrationOptions();");
        w.line("semanticTokensOptions.setFull(true);");
        w.line("semanticTokensOptions.setLegend(new SemanticTokensLegend(");
        w.line("    List.of(\"valid\", \"invalid\"), List.of()));");
        w.line("capabilities.setSemanticTokensProvider(semanticTokensOptions);");
        w.line("configureAdditionalCapabilities(capabilities);");
        w.line("return CompletableFuture.completedFuture(new InitializeResult(capabilities));");
        w.dedent();
        w.line("}");
        w.blankLine();

        // shutdown()
        w.line("@Override");
        w.line("public CompletableFuture<Object> shutdown() {");
        w.indent();
        w.line("return CompletableFuture.completedFuture(null);");
        w.dedent();
        w.line("}");
        w.blankLine();

        // exit()
        w.line("@Override");
        w.line("public void exit() {}");
        w.blankLine();

        // setTrace()
        w.line("@Override");
        w.line("public void setTrace(SetTraceParams params) {}");
        w.blankLine();

        // connect()
        w.line("@Override");
        w.line("public void connect(LanguageClient client) {");
        w.indent();
        w.line("this.client = client;");
        w.dedent();
        w.line("}");
        w.blankLine();

        // getTextDocumentService()
        w.line("@Override");
        w.line("public TextDocumentService getTextDocumentService() {");
        w.indent();
        w.line("return new " + serverClass + "TextDocumentService(this);");
        w.dedent();
        w.line("}");
        w.blankLine();

        // getWorkspaceService()
        w.line("@Override");
        w.line("public WorkspaceService getWorkspaceService() {");
        w.indent();
        w.line("return new " + serverClass + "WorkspaceService();");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** parseDocument() および関連ユーティリティメソッドを出力する。 */
    static void emitParseDocument(IndentedWriter w, String parsersClass, boolean hasScopeStore) {
        // doParse() — パースコアロジック（副作用なし）
        w.line("private ParseResult doParse(String content) {");
        w.indent();
        w.line("Parser parser = " + parsersClass + ".getRootParser();");
        w.line("ParseContext context = new ParseContext(createRootSourceCompat(content));");
        if (hasScopeStore) {
            w.line("ScopeStore.registerDispatcher(context);");
        }
        w.line("Parsed result = parser.parse(context);");
        w.line("int consumedLength = 0;");
        w.line("if (result.isSucceeded()) {");
        w.indent();
        w.line("consumedLength = result.getConsumed().source.sourceAsString().length();");
        w.dedent();
        w.line("}");
        w.line("context.close();");
        w.line("return new ParseResult(result.isSucceeded(), consumedLength, content.length());");
        w.dedent();
        w.line("}");
        w.blankLine();

        // parseDocument() — フルパース（didOpen 用）
        w.line("public ParseResult parseDocument(String uri, String content) {");
        w.indent();
        w.line("ParseResult parseResult = doParse(content);");
        w.line("documents.put(uri, new DocumentState(uri, content, parseResult, new IncrementalParseCache()));");
        w.line("if (client != null) {");
        w.indent();
        w.line("publishDiagnostics(uri, content, parseResult);");
        w.dedent();
        w.line("}");
        w.line("return parseResult;");
        w.dedent();
        w.line("}");
        w.blankLine();

        // parseDocumentIncremental() — チャンクキャッシュを活用（didChange 用）
        w.line("public ParseResult parseDocumentIncremental(String uri, String content) {");
        w.indent();
        w.line("DocumentState previous = documents.get(uri);");
        w.line("if (previous != null && previous.content().equals(content)) {");
        w.indent();
        w.line("return previous.parseResult();");
        w.dedent();
        w.line("}");
        w.line("IncrementalParseCache cache = previous != null ? previous.cache() : new IncrementalParseCache();");
        w.line("List<String> chunks = cache.splitIntoChunks(content, \";\");");
        w.line("boolean anyChanged = chunks.stream().anyMatch(c -> !cache.isCached(c));");
        w.line("if (!anyChanged && previous != null) {");
        w.indent();
        w.line("return previous.parseResult();");
        w.dedent();
        w.line("}");
        w.line("ParseResult parseResult = doParse(content);");
        w.line("int offset = 0;");
        w.line("for (String chunk : chunks) {");
        w.indent();
        w.line("cache.put(chunk, null, offset);");
        w.line("offset += chunk.length();");
        w.dedent();
        w.line("}");
        w.line("documents.put(uri, new DocumentState(uri, content, parseResult, cache));");
        w.line("if (client != null) {");
        w.indent();
        w.line("publishDiagnostics(uri, content, parseResult);");
        w.dedent();
        w.line("}");
        w.line("return parseResult;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private static StringSource createRootSourceCompat(String source) {");
        w.indent();
        w.line("try {");
        w.indent();
        w.line("java.lang.reflect.Method m = StringSource.class.getMethod(\"createRootSource\", String.class);");
        w.line("Object v = m.invoke(null, source);");
        w.line("if (v instanceof StringSource s) {");
        w.indent();
        w.line("return s;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("try {");
        w.indent();
        w.line("for (java.lang.reflect.Constructor<?> c : StringSource.class.getDeclaredConstructors()) {");
        w.indent();
        w.line("Class<?>[] types = c.getParameterTypes();");
        w.line("if (types.length == 0 || types[0] != String.class) {");
        w.indent();
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("Object[] args = new Object[types.length];");
        w.line("args[0] = source;");
        w.line("c.setAccessible(true);");
        w.line("Object v = c.newInstance(args);");
        w.line("if (v instanceof StringSource s) {");
        w.indent();
        w.line("return s;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("throw new IllegalStateException(\"No compatible StringSource initializer found\");");
        w.dedent();
        w.line("}");
        w.blankLine();

        // publishDiagnostics()
        w.line("private void publishDiagnostics(String uri, String content, ParseResult result) {");
        w.indent();
        w.line("List<Diagnostic> diagnostics = new ArrayList<>();");
        w.line("if (result.consumedLength() < result.totalLength()) {");
        w.indent();
        w.line("int errorStart = result.consumedLength();");
        w.line("Position startPos = offsetToPosition(content, errorStart);");
        w.line("Position endPos = offsetToPosition(content, result.totalLength());");
        w.line("Diagnostic diagnostic = new Diagnostic();");
        w.line("diagnostic.setRange(new Range(startPos, endPos));");
        w.line("diagnostic.setSeverity(DiagnosticSeverity.Error);");
        w.line("diagnostic.setMessage(\"Parse error at offset \" + errorStart);");
        w.line("diagnostics.add(diagnostic);");
        w.dedent();
        w.line("}");
        w.line("java.util.List<Diagnostic> additional = additionalDiagnostics(uri, content);");
        w.line("if (additional != null && !additional.isEmpty()) {");
        w.indent();
        w.line("diagnostics.addAll(additional);");
        w.dedent();
        w.line("}");
        w.line("client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));");
        w.dedent();
        w.line("}");
        w.blankLine();

        // offsetToPosition()
        w.line("private Position offsetToPosition(String content, int offset) {");
        w.indent();
        w.line("int line = 0;");
        w.line("int column = 0;");
        w.line("for (int i = 0; i < offset && i < content.length(); i++) {");
        w.indent();
        w.line("if (content.charAt(i) == '\\n') {");
        w.indent();
        w.line("line++;");
        w.line("column = 0;");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("column++;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return new Position(line, column);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** GGP フックメソッドを出力する。 */
    static void emitHookMethods(IndentedWriter w) {
        w.line("// Hook: additional completion items (for metadata, language-specific keywords)");
        w.line("protected java.util.List<CompletionItem> additionalCompletionItems(");
        w.line("        CompletionParams params, String documentContent) {");
        w.indent();
        w.line("return java.util.Collections.emptyList();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Hook: additional diagnostics (for semantic validation)");
        w.line("protected java.util.List<Diagnostic> additionalDiagnostics(");
        w.line("        String uri, String documentContent) {");
        w.indent();
        w.line("return java.util.Collections.emptyList();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Hook: custom definition (for cross-references like dependsOn)");
        w.line("protected org.eclipse.lsp4j.LocationLink customDefinition(");
        w.line("        DefinitionParams params, String documentContent) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Hook: additional semantic tokens (for embedded languages like Java code blocks)");
        w.line("protected int[] additionalSemanticTokenData(String uri, String documentContent) {");
        w.indent();
        w.line("return new int[0];");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Hook: configure additional server capabilities");
        w.line("protected void configureAdditionalCapabilities(");
        w.line("        ServerCapabilities capabilities) {");
        w.indent();
        w.line("// Override to add capabilities");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** @catalog アノテーション関連のインフラメソッドを出力する。 */
    static void emitCatalogMethods(IndentedWriter w, String grammarName) {
        String lowerName = grammarName.toLowerCase();
        String upperName = grammarName.toUpperCase();
        w.line("protected void initCatalogResolver(InitializeParams params) {");
        w.indent();
        w.line("if (catalogResolver != null) return;");
        w.line("String path = null;");
        w.line("Object initOptions = params.getInitializationOptions();");
        w.line("if (initOptions instanceof Map<?,?> map) {");
        w.indent();
        w.line("Object v = map.get(\"catalogPath\");");
        w.line("if (v instanceof String s && !s.isBlank()) path = s;");
        w.dedent();
        w.line("}");
        w.line("if (path == null) path = System.getProperty(\"" + lowerName + ".catalog.path\");");
        w.line("if (path == null) path = System.getenv(\"" + upperName + "_CATALOG_PATH\");");
        w.line("if (path != null && !path.isBlank()) catalogResolver = new FileCatalogResolver(path);");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("protected List<CompletionItem> catalogCompletion(String prefix) {");
        w.indent();
        w.line("if (catalogResolver == null || catalogResolver.isEmpty()) return List.of();");
        w.line("List<CompletionItem> items = new ArrayList<>();");
        w.line("for (CatalogEntry entry : catalogResolver.listAll()) {");
        w.indent();
        w.line("if (prefix.isEmpty() || entry.name().startsWith(prefix)) {");
        w.indent();
        w.line("CompletionItem item = new CompletionItem(entry.name());");
        w.line("item.setKind(CompletionItemKind.Variable);");
        w.line("if (entry.description() != null && !entry.description().isBlank()) {");
        w.indent();
        w.line("item.setDetail(entry.description());");
        w.dedent();
        w.line("}");
        w.line("items.add(item);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return items;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("protected String catalogHover(String variableName) {");
        w.indent();
        w.line("if (catalogResolver == null) return null;");
        w.line("CatalogEntry entry = catalogResolver.lookup(variableName);");
        w.line("if (entry == null) return null;");
        w.line("return entry.description() != null ? entry.description() : entry.name();");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** DocumentState/ParseResult レコードを出力する。 */
    static void emitRecords(IndentedWriter w) {
        w.line("public static class DocumentState {");
        w.indent();
        w.line("private final String uri;");
        w.line("private final String content;");
        w.line("private final ParseResult parseResult;");
        w.line("private final IncrementalParseCache cache;");
        w.blankLine();
        w.line("public DocumentState(String uri, String content, ParseResult parseResult, IncrementalParseCache cache) {");
        w.indent();
        w.line("this.uri = uri;");
        w.line("this.content = content;");
        w.line("this.parseResult = parseResult;");
        w.line("this.cache = cache;");
        w.dedent();
        w.line("}");
        w.blankLine();
        w.line("public String uri() { return uri; }");
        w.line("public String content() { return content; }");
        w.line("public ParseResult parseResult() { return parseResult; }");
        w.line("public IncrementalParseCache cache() { return cache; }");
        w.dedent();
        w.line("}");
        w.blankLine();
        w.line("public record ParseResult(boolean succeeded, int consumedLength, int totalLength) {}");
        w.blankLine();
    }

    /** TextDocumentService 内部クラスを出力する。 */
    static void emitTextDocumentService(IndentedWriter w, String serverClass, String grammarName) {
        w.line("static class " + serverClass + "TextDocumentService implements TextDocumentService {");
        w.blankLine();
        w.indent();
        w.line("private final " + serverClass + " server;");
        w.blankLine();
        w.line(serverClass + "TextDocumentService(" + serverClass + " server) {");
        w.indent();
        w.line("this.server = server;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("@Override");
        w.line("public void didOpen(DidOpenTextDocumentParams params) {");
        w.indent();
        w.line("server.parseDocument(");
        w.line("    params.getTextDocument().getUri(),");
        w.line("    params.getTextDocument().getText());");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("@Override");
        w.line("public void didChange(DidChangeTextDocumentParams params) {");
        w.indent();
        w.line("server.parseDocumentIncremental(");
        w.line("    params.getTextDocument().getUri(),");
        w.line("    params.getContentChanges().get(0).getText());");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("@Override");
        w.line("public void didClose(DidCloseTextDocumentParams params) {");
        w.indent();
        w.line("server.documents.remove(params.getTextDocument().getUri());");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("@Override");
        w.line("public void didSave(DidSaveTextDocumentParams params) {}");
        w.blankLine();

        // completion()
        w.line("@Override");
        w.line("public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(");
        w.line("        CompletionParams params) {");
        w.indent();
        w.line("List<CompletionItem> items = new ArrayList<>();");
        w.line("for (String kw : KEYWORDS) {");
        w.indent();
        w.line("CompletionItem item = new CompletionItem(kw);");
        w.line("item.setKind(CompletionItemKind.Keyword);");
        w.line("items.add(item);");
        w.dedent();
        w.line("}");
        w.line("String uri = params.getTextDocument().getUri();");
        w.line("DocumentState state = server.documents.get(uri);");
        w.line("String content = state != null ? state.content() : \"\";");
        w.line("java.util.List<CompletionItem> additional = server.additionalCompletionItems(params, content);");
        w.line("if (additional != null && !additional.isEmpty()) {");
        w.indent();
        w.line("items.addAll(additional);");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(Either.forLeft(items));");
        w.dedent();
        w.line("}");
        w.blankLine();

        // hover()
        w.line("@Override");
        w.line("public CompletableFuture<Hover> hover(HoverParams params) {");
        w.indent();
        w.line("String uri = params.getTextDocument().getUri();");
        w.line("DocumentState state = server.documents.get(uri);");
        w.line("if (state == null) {");
        w.indent();
        w.line("return CompletableFuture.completedFuture(null);");
        w.dedent();
        w.line("}");
        w.line("String text;");
        w.line("if (state.parseResult().succeeded() &&");
        w.line("        state.parseResult().consumedLength() == state.parseResult().totalLength()) {");
        w.indent();
        w.line("text = \"Valid " + grammarName + "\";");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("text = \"Parse error at offset \" + state.parseResult().consumedLength();");
        w.dedent();
        w.line("}");
        w.line("MarkupContent content = new MarkupContent();");
        w.line("content.setKind(\"plaintext\");");
        w.line("content.setValue(text);");
        w.line("return CompletableFuture.completedFuture(new Hover(content));");
        w.dedent();
        w.line("}");
        w.blankLine();

        // semanticTokensFull()
        w.line("@Override");
        w.line("public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {");
        w.indent();
        w.line("String uri = params.getTextDocument().getUri();");
        w.line("DocumentState state = server.documents.get(uri);");
        w.line("if (state == null) {");
        w.indent();
        w.line("return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));");
        w.dedent();
        w.line("}");
        w.line("int[] tokenData = server.additionalSemanticTokenData(uri, state.content());");
        w.line("if (tokenData != null && tokenData.length > 0) {");
        w.indent();
        w.line("List<Integer> data = new ArrayList<>();");
        w.line("for (int v : tokenData) { data.add(v); }");
        w.line("return CompletableFuture.completedFuture(new SemanticTokens(data));");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** WorkspaceService 内部クラスを出力する。 */
    static void emitWorkspaceService(IndentedWriter w, String serverClass) {
        w.line("static class " + serverClass + "WorkspaceService implements WorkspaceService {");
        w.blankLine();
        w.indent();
        w.line("@Override");
        w.line("public void didChangeConfiguration(org.eclipse.lsp4j.DidChangeConfigurationParams params) {}");
        w.blankLine();
        w.line("@Override");
        w.line("public void didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}");
        w.dedent();
        w.line("}");
    }

    /** カタログインターフェースと FileCatalogResolver を出力する。 */
    static void emitCatalogInterfaces(IndentedWriter w) {
        w.blankLine();
        w.line("public interface CatalogResolver {");
        w.indent();
        w.line("List<CatalogEntry> listAll();");
        w.line("CatalogEntry lookup(String name);");
        w.line("default boolean isEmpty() { return listAll().isEmpty(); }");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("public record CatalogEntry(String name, String description, String context, String sourcePath) {}");
        w.blankLine();

        w.line("public static class FileCatalogResolver implements CatalogResolver {");
        w.indent();
        w.line("private final List<CatalogEntry> entries = new ArrayList<>();");
        w.blankLine();
        w.line("public FileCatalogResolver(String pathSpec) {");
        w.indent();
        w.line("for (String path : pathSpec.split(java.io.File.pathSeparator)) {");
        w.indent();
        w.line("java.io.File f = new java.io.File(path.trim());");
        w.line("if (!f.exists()) continue;");
        w.line("if (f.isDirectory()) {");
        w.indent();
        w.line("java.io.File[] files = f.listFiles(ff -> ff.getName().endsWith(\".tecatalog\"));");
        w.line("if (files != null) for (java.io.File cf : files) loadFile(cf);");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("loadFile(f);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.blankLine();
        w.line("private void loadFile(java.io.File f) {");
        w.indent();
        w.line("try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f, java.nio.charset.StandardCharsets.UTF_8))) {");
        w.indent();
        w.line("String line;");
        w.line("boolean canonical = false;");
        w.line("boolean firstLine = true;");
        w.line("while ((line = br.readLine()) != null) {");
        w.indent();
        w.line("if (firstLine) { firstLine = false; canonical = line.startsWith(\"tinyexpression-catalog-v\"); continue; }");
        w.line("if (line.isBlank() || line.startsWith(\"#\")) continue;");
        w.line("if (canonical) {");
        w.indent();
        w.line("String[] parts = line.split(\"\\\\|\", -1);");
        w.line("if (parts.length >= 3) {");
        w.indent();
        w.line("String name = parts.length > 1 ? parts[1].trim() : \"\";");
        w.line("String desc = parts.length > 2 ? parts[2].trim() : \"\";");
        w.line("String ctx  = parts.length > 3 ? parts[3].trim() : \"\";");
        w.line("entries.add(new CatalogEntry(name, desc, ctx, f.getPath()));");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("String[] parts = line.split(\"\\\\|\", 2);");
        w.line("String name = parts[0].trim();");
        w.line("String desc = parts.length > 1 ? parts[1].trim() : \"\";");
        w.line("entries.add(new CatalogEntry(name, desc, \"\", f.getPath()));");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (java.io.IOException ignored) {}");
        w.dedent();
        w.line("}");
        w.blankLine();
        w.line("@Override public List<CatalogEntry> listAll() { return Collections.unmodifiableList(entries); }");
        w.line("@Override public CatalogEntry lookup(String name) {");
        w.indent();
        w.line("return entries.stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
    }

    /** 文法からキーワードを収集する。 */
    static List<String> collectKeywords(GrammarDecl grammar) {
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
        kw.add("@declares");
        kw.add("@catalog");
        kw.add("params");
        kw.add("level");
        kw.add("profile");
        kw.add("name");
        kw.add("mode");
        kw.add("symbol");
        kw.add("context");
        kw.add("description");
        for (RuleDecl rule : grammar.rules()) {
            collectFromBody(rule.body(), kw);
        }
        return new ArrayList<>(kw);
    }

    private static void collectFromBody(RuleBody body, Set<String> acc) {
        switch (body) {
            case ChoiceBody cb -> {
                for (var alt : cb.alternatives()) collectFromBody(alt, acc);
            }
            case SequenceBody sb -> {
                for (AnnotatedElement ae : sb.elements()) collectFromElement(ae.element(), acc);
            }
        }
    }

    private static void collectFromElement(AtomicElement element, Set<String> acc) {
        switch (element) {
            case TerminalElement t -> acc.add(stripQuotes(t.value()));
            case GroupElement g -> collectFromBody(g.body(), acc);
            case OptionalElement o -> collectFromBody(o.body(), acc);
            case RepeatElement r -> collectFromBody(r.body(), acc);
            default -> {}
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
