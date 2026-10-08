package org.unlaxer.dsl.lsp.ubnf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.LinkedEditingRangeParams;
import org.eclipse.lsp4j.LinkedEditingRanges;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.generated.UBNFLanguageServer;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.tooling.AuthoringCatalog;

/**
 * UBNF 文法編集向けのリッチ LSP 実装。生成された {@link UBNFLanguageServer} を
 * 拡張し、GrammarValidator をリアルタイム診断として接続する。
 *
 * <p>追加 capability: definition / references / rename / documentSymbol /
 * codeAction (W-TOKEN-UNRESOLVED の FQN quick fix) / foldingRange /
 * linkedEditingRange / signatureHelp (アノテーション引数) / semanticTokens (本実装) /
 * 文脈依存 completion (アノテーション snippet・ルール名・既知パーサー FQN)。
 */
public class UBNFLanguageServerExt extends UBNFLanguageServer {

    // =========================================================================
    // アノテーションのドキュメント (hover / completion / signatureHelp で共用)
    // =========================================================================

    record AnnotationDoc(String label, String snippet, String signature, String doc) {}

    static final List<AnnotationDoc> ANNOTATIONS = List.of(
        new AnnotationDoc("@ubnf", "@ubnf: v2", "@ubnf: v2", "文法形式 v2 を指定します。製品のバージョンとは別です。"),
        new AnnotationDoc("@feature", "@feature: ${1:declarativeTokensV1}", "@feature: name", "この文法に必要な機能契約を記録します。"),
        new AnnotationDoc("@package", "@package: ${1:org.example}", "@package: name", "生成 Java コードのパッケージです。"),
        new AnnotationDoc("@root", "@root", "@root",
            "Marks the grammar entry point. Exactly one rule must be annotated."),
        new AnnotationDoc("@mapping", "@mapping(${1:TypeName}, params=[${2:field}])",
            "@mapping(TypeName, params=[field1, field2, ...])",
            "Generates a Java record for this rule. `params` must match captures (`@name`) in positional order."),
        new AnnotationDoc("@leftAssoc", "@leftAssoc", "@leftAssoc",
            "Left-associative folding for `left { op right }` binary rules."),
        new AnnotationDoc("@rightAssoc", "@rightAssoc", "@rightAssoc",
            "Right-associative folding for binary rules."),
        new AnnotationDoc("@precedence", "@precedence(level=${1:10})", "@precedence(level=N)",
            "Operator precedence level for this rule."),
        new AnnotationDoc("@whitespace", "@whitespace: ${1:javaStyle}", "@whitespace: javaStyle|none|TOKEN|alias.TOKEN",
            "ルールの連接境界で組込みまたは non-nullable な宣言的 token を読み飛ばします。"),
        new AnnotationDoc("@enum", "@enum", "@enum",
            "Generates a Java enum from string-literal alternatives."),
        new AnnotationDoc("@commonField", "@commonField(${1:name})", "@commonField(field)",
            "Lifts a field shared by all @mapping variants into the sealed interface."),
        new AnnotationDoc("@scopeTree", "@scopeTree(mode=${1:lexical})", "@scopeTree(mode=lexical|dynamic)",
            "Marks this rule as a scope boundary (enter/leave scope events)."),
        new AnnotationDoc("@declares", "@declares(symbol=${1:name})", "@declares(symbol=capture)",
            "Registers the captured identifier as a symbol declaration in the current scope."),
        new AnnotationDoc("@backref", "@backref(name=${1:name})", "@backref(name=capture)",
            "Marks the capture as a reference to a previously declared symbol."),
        new AnnotationDoc("@import", "@import ${1:alias} from '${2:path/to/grammar.ubnf}'",
            "@import alias from 'path'",
            "宣言的 token の部品を相対パスから読み込みます。設定より前に置き、alias.TOKEN で参照します。任意のルール import は未対応です。"),
        new AnnotationDoc("@recovery", "@recovery(${1:sync})", "@recovery(sync|auto|skip)",
            "Recovery metadata. Execution support depends on the backend; do not assume recognition implies recovery support."),
        new AnnotationDoc("@skip", "@skip", "@skip",
            "Excludes this rule from AST output."),
        new AnnotationDoc("@interleave", "@interleave(${1:profile})", "@interleave(profile)",
            "Interleave profile (whitespace/comment handling) for this rule."),
        new AnnotationDoc("@typeof", "@typeof(${1:capture})", "@typeof(capture)",
            "Type query element for typed if/ternary support."),
        new AnnotationDoc("@catalog", "@catalog(context=${1:name})", "@catalog(context=...)",
            "Associates the rule with an external symbol catalog context."),
        new AnnotationDoc("@eval", "@eval", "@eval", "Marks the rule for evaluator generation."),
        new AnnotationDoc("@doc", "@doc('${1:description}')", "@doc('text')",
            "Attaches documentation text to the rule."));

    /** token 宣言の補完候補にする同梱パーサー FQN。 */
    static final List<String> KNOWN_PARSER_CLASSES = List.of(
        "org.unlaxer.parser.elementary.NumberParser",
        "org.unlaxer.parser.elementary.WordParser",
        "org.unlaxer.parser.elementary.SingleQuotedParser",
        "org.unlaxer.parser.elementary.DoubleQuotedParser",
        "org.unlaxer.parser.elementary.EndOfSourceParser",
        "org.unlaxer.parser.elementary.WildCardCharacterParser",
        "org.unlaxer.parser.clang.IdentifierParser",
        "org.unlaxer.parser.posix.DigitParser",
        "org.unlaxer.parser.posix.AlphabetParser",
        "org.unlaxer.parser.posix.SpaceParser",
        "org.unlaxer.parser.posix.CommaParser",
        "org.unlaxer.parser.posix.SemiColonParser");

    static final List<String> CORE_KEYWORDS = AuthoringCatalog.keywords();
    static final List<String> LEXICAL_KEYWORDS = List.of("CHAR_RANGE", "NEGATION", "LOOKAHEAD", "NEGATIVE_LOOKAHEAD",
        "ANY", "EOF", "BOF", "BOL", "EOL", "CAPTURE", "SAME_AS");
    private static final com.google.gson.JsonObject CATALOG = com.google.gson.JsonParser.parseString(AuthoringCatalog.json()).getAsJsonObject();

    record BlockSnippet(String label, String detail, String body) {}

    static final List<BlockSnippet> BLOCK_SNIPPETS = List.of(
        new BlockSnippet("grammar", "grammar block skeleton",
            "grammar ${1:Name} {\n  @ubnf: v2\n  @package: ${2:org.example}\n\n  token ${3:NUMBER} ::= CHAR_RANGE('0', '9')+;\n\n  @root\n  @mapping(Value, params=[text])\n  @doc('数字を入力してください。')\n  ${4:Start} ::= ${3:NUMBER} @text;\n}$0"),
        new BlockSnippet("token", "token declaration",
            "token ${1:NAME} ::= ${2:CHAR_RANGE('0', '9')+};$0"),
        new BlockSnippet("rule", "plain rule",
            "${1:RuleName} ::= ${2:body} ;$0"),
        new BlockSnippet("mapped-rule", "rule with @mapping record",
            "@mapping(${1:TypeName}, params=[${2:left}, ${3:right}])\n${4:RuleName} ::= ${5:Element} @${2:left} ${6:Element} @${3:right} ;$0"),
        new BlockSnippet("enum-rule", "@enum rule from literals",
            "@enum\n${1:Kind} ::= '${2:a}' | '${3:b}' ;$0"),
        new BlockSnippet("binary-rule", "left-assoc binary expression rule",
            "@mapping(${1:BinaryExpr}, params=[left, op, right])\n@leftAssoc\n${2:Expression} ::= ${3:Term} @left { ${4:Op} @op ${3:Term} @right } ;$0"));

    // =========================================================================
    // ドキュメントインデックス
    // =========================================================================

    record DeclSite(String name, String kind, int line, int startChar, int endChar, String detail) {}

    static final class DocumentIndex {
        final String content;
        final List<UBNFAST.GrammarDecl> grammars;
        final Map<String, DeclSite> decls = new LinkedHashMap<>();
        final List<Diagnostic> validation = new ArrayList<>();
        final UbnfEditorIndex editor;

        DocumentIndex(String content, List<UBNFAST.GrammarDecl> grammars, UbnfEditorIndex editor) {
            this.content = content;
            this.grammars = grammars;
            this.editor = editor;
        }
    }

    private final Map<String, DocumentIndex> indexByUri = new LinkedHashMap<>();

    DocumentIndex ensureIndex(String uri, String content) {
        DocumentIndex cached = indexByUri.get(uri);
        if (cached != null && cached.content.equals(content) && !content.contains("@import")) {
            return cached;
        }
        var editor = new UbnfEditorIndex(uri, content, path -> {
            for (var state : documents.values()) {
                try {
                    if (java.nio.file.Path.of(java.net.URI.create(state.uri())).toAbsolutePath().normalize().equals(path)) return state.content();
                } catch (IllegalArgumentException ignored) { /* Non-file editor. */ }
            }
            return java.nio.file.Files.readString(path);
        });
        var grammars = editor.snapshot == null ? null : editor.snapshot.ast().grammars();
        DocumentIndex index = new DocumentIndex(content, grammars, editor);
        for (var symbol : editor.declarations) {
            addDecl(index, content, symbol.start(), symbol.name(), symbol.kind(), symbol.kind().equals("token") ? tokenDetail(symbol.detail()) : null);
        }
        if (editor.linked != null && editor.problems.isEmpty()) collectValidationDiagnostics(content, editor.linked, index);
        for (var problem : editor.problems) {
            var diagnostic = new Diagnostic(problem.range(), problem.message(), DiagnosticSeverity.Error, "ubnf-module");
            diagnostic.setCode("E-MODULE"); index.validation.add(diagnostic);
        }
        indexByUri.put(uri, index);
        return index;
    }

    private static String tokenDetail(String declaration) {
        var matcher = Pattern.compile("(?s)\\btoken\\s+\\w+\\s*(?:::=|=)\\s*(.*)").matcher(declaration);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private void refreshImporters(String changedUri) {
        indexByUri.clear();
        // Re-publish dependent diagnostics using current open buffers. No external file writes.
        for (var state : new ArrayList<>(documents.values())) {
            if (!state.uri().equals(changedUri) && state.content().contains("@import")) parseDocument(state.uri(), state.content());
        }
    }

    private void addDecl(DocumentIndex index, String content, int offset, String name, String kind, String detail) {
        Position pos = positionAt(content, offset);
        index.decls.putIfAbsent(name,
            new DeclSite(name, kind, pos.getLine(), pos.getCharacter(), pos.getCharacter() + name.length(), detail));
    }

    private void collectValidationDiagnostics(
            String content, List<UBNFAST.GrammarDecl> grammars, DocumentIndex index) {
        for (UBNFAST.GrammarDecl grammar : grammars) {
            List<GrammarValidator.ValidationIssue> issues =
                new ArrayList<>(GrammarValidator.validate(grammar));
            issues.addAll(GrammarValidator.detectLeftRecursionIssues(grammar));
            for (GrammarValidator.ValidationIssue issue : issues) {
                if (grammar.rules().isEmpty() && grammar.tokens().stream().allMatch(token -> token instanceof UBNFAST.TokenDecl.Declarative)
                        && "W-GENERAL-NO-ROOT".equals(issue.code())) continue; // Token libraries intentionally have no entry rule.
                Diagnostic diagnostic = new Diagnostic();
                diagnostic.setCode(issue.code());
                diagnostic.setSource("ubnf-validator");
                diagnostic.setSeverity("WARNING".equals(issue.severity())
                    ? DiagnosticSeverity.Warning : DiagnosticSeverity.Error);
                String hint = issue.hint() == null ? "" : " — " + issue.hint();
                diagnostic.setMessage(issue.message() + hint);
                diagnostic.setRange(rangeForIssue(content, index, issue));
                index.validation.add(diagnostic);
            }
        }
    }

    private Range rangeForIssue(String content, DocumentIndex index, GrammarValidator.ValidationIssue issue) {
        // W-TOKEN-UNRESOLVED: 未解決クラス名そのものを指す
        Matcher unresolved = Pattern.compile("token (\\w+) references unresolved parser class: (\\S+)")
            .matcher(issue.message());
        if (unresolved.find()) {
            DeclSite token = index.decls.get(unresolved.group(1));
            if (token != null && token.detail() != null) {
                String line = lineAt(content, token.line());
                int col = line.indexOf(token.detail());
                if (col >= 0) {
                    return new Range(new Position(token.line(), col),
                        new Position(token.line(), col + token.detail().length()));
                }
            }
        }
        // rule が特定できる issue はルール名を指す
        if (issue.rule() != null) {
            DeclSite rule = index.decls.get(issue.rule());
            if (rule != null) {
                return new Range(new Position(rule.line(), rule.startChar()),
                    new Position(rule.line(), rule.endChar()));
            }
        }
        return new Range(new Position(0, 0), new Position(0, 1));
    }

    // ベース実装の publishDiagnostics から呼ばれる hook
    @Override
    protected List<Diagnostic> additionalDiagnostics(String uri, String documentContent) {
        return ensureIndex(uri, documentContent).validation;
    }

    /** テスト用: 文書を登録せず検証診断だけ計算する。 */
    public List<Diagnostic> validationDiagnostics(String content) {
        return ensureIndex("synthetic://validation", content).validation;
    }

    // =========================================================================
    // capability 設定
    // =========================================================================

    static final List<String> TOKEN_TYPES = List.of(
        "keyword", "type", "property", "macro", "string", "number",
        "operator", "comment", "function", "namespace");
    static final List<String> TOKEN_MODIFIERS = List.of("declaration");

    @Override
    protected void configureAdditionalCapabilities(ServerCapabilities capabilities) {
        CompletionOptions completion = new CompletionOptions();
        completion.setResolveProvider(false);
        completion.setTriggerCharacters(List.of("@", "=", "."));
        capabilities.setCompletionProvider(completion);

        capabilities.setDefinitionProvider(true);
        capabilities.setReferencesProvider(true);
        capabilities.setRenameProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setCodeActionProvider(true);
        capabilities.setFoldingRangeProvider(true);
        capabilities.setLinkedEditingRangeProvider(true);

        SignatureHelpOptions signatureHelp = new SignatureHelpOptions();
        signatureHelp.setTriggerCharacters(List.of("(", ","));
        capabilities.setSignatureHelpProvider(signatureHelp);

        SemanticTokensWithRegistrationOptions semanticTokens =
            new SemanticTokensWithRegistrationOptions();
        semanticTokens.setFull(true);
        semanticTokens.setLegend(new SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS));
        capabilities.setSemanticTokensProvider(semanticTokens);
    }

    public record PackageSourceRequest(String uri) {}
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("ubnf/packageSource")
    public CompletableFuture<String> packageSource(PackageSourceRequest params) {
        return new ExtTextDocumentService(this).packageSource(params);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new ExtTextDocumentService(this);
    }

    // =========================================================================
    // TextDocumentService
    // =========================================================================

    static class ExtTextDocumentService implements TextDocumentService {

        private final UBNFLanguageServerExt server;

        ExtTextDocumentService(UBNFLanguageServerExt server) {
            this.server = server;
        }

        CompletableFuture<String> packageSource(PackageSourceRequest params) {
            String uri = params == null ? null : params.uri();
            // Only snapshots previously verified while indexing an importing document are exposed.
            if (uri == null || !uri.startsWith("ubnf-package:/")) return CompletableFuture.completedFuture(null);
            for (var index : server.indexByUri.values()) {
                String source = index.editor.virtualSources.get(uri);
                if (source != null) return CompletableFuture.completedFuture(source);
            }
            return CompletableFuture.completedFuture(null);
        }

        private String contentOf(String uri) {
            DocumentState state = server.documents.get(uri);
            return state != null ? state.content() : null;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            server.parseDocument(params.getTextDocument().getUri(), params.getTextDocument().getText());
            server.refreshImporters(params.getTextDocument().getUri());
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            server.parseDocumentIncremental(
                params.getTextDocument().getUri(), params.getContentChanges().get(0).getText());
            server.refreshImporters(params.getTextDocument().getUri());
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
            String uri = params.getTextDocument().getUri();
            server.documents.remove(uri);
            server.indexByUri.remove(uri);
            server.refreshImporters(uri);
        }

        @Override
        public void didSave(DidSaveTextDocumentParams params) { server.refreshImporters(params.getTextDocument().getUri()); }

        // ---------------------------------------------------------------- completion

        @Override
        public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            List<CompletionItem> items = new ArrayList<>();
            if (content == null) {
                return CompletableFuture.completedFuture(Either.forLeft(items));
            }
            DocumentIndex index = server.ensureIndex(uri, content);
            String line = lineAt(content, params.getPosition().getLine());
            String prefix = line.substring(0, Math.min(params.getPosition().getCharacter(), line.length()));

            if (prefix.matches(".*@\\w*$")) {
                for (AnnotationDoc annotation : ANNOTATIONS) {
                    CompletionItem item = new CompletionItem(annotation.label());
                    item.setKind(CompletionItemKind.Event);
                    item.setDetail(annotation.signature());
                    String help = catalogHelp(annotation.label().substring(1));
                    item.setDocumentation(markdown(help == null ? annotation.doc() : help));
                    item.setInsertTextFormat(InsertTextFormat.Snippet);
                    // 行内の '@' から補完が始まるので '@' を除いた snippet を挿入
                    item.setInsertText(annotation.snippet().substring(1));
                    item.setFilterText(annotation.label().substring(1));
                    items.add(item);
                }
                return CompletableFuture.completedFuture(Either.forLeft(items));
            }

            if (prefix.matches("\\s*token\\s+\\w+\\s*=\\s*\\S*$")) {
                for (String fqn : KNOWN_PARSER_CLASSES) {
                    CompletionItem item = new CompletionItem(fqn);
                    item.setKind(CompletionItemKind.Class);
                    item.setDetail("bundled unlaxer parser");
                    item.setSortText("0" + fqn);
                    items.add(item);
                }
                return CompletableFuture.completedFuture(Either.forLeft(items));
            }

            int offset = UbnfEditorIndex.offset(content, params.getPosition());
            boolean whitespace = prefix.matches(".*@whitespace\\s*(?::|\\()\\s*[A-Za-z_0-9.]*$");
            if (whitespace) for (String style : List.of("javaStyle", "none")) {
                CompletionItem item = new CompletionItem(style); item.setKind(CompletionItemKind.Constant);
                item.setDetail(style.equals("none") ? "自動読み飛ばしなし" : "組込みの空白・行/block コメント"); items.add(item);
            }
            int assignment = index.editor.code.lastIndexOf("::=", offset);
            boolean lexical = assignment >= 0 && index.editor.code.substring(0, assignment).matches("(?s).*\\btoken\\s+\\w+\\s*")
                && index.editor.code.substring(assignment, offset).indexOf(';') < 0;
            var qualifier = Pattern.compile("([A-Za-z_]\\w*\\.)\\w*$").matcher(prefix);
            boolean qualified = qualifier.find();
            for (String keyword : qualified || whitespace ? List.<String>of() : lexical ? LEXICAL_KEYWORDS : CORE_KEYWORDS) {
                CompletionItem item = new CompletionItem(keyword);
                item.setKind(CompletionItemKind.Keyword);
                String help = catalogHelp(keyword);
                if (help != null) item.setDocumentation(markdown(help));
                items.add(item);
            }
            for (BlockSnippet snippet : lexical || qualified || whitespace ? List.<BlockSnippet>of() : BLOCK_SNIPPETS) {
                CompletionItem item = new CompletionItem(snippet.label());
                item.setKind(CompletionItemKind.Snippet);
                item.setDetail(snippet.detail());
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                item.setInsertText(snippet.body());
                items.add(item);
            }
            var scope = index.editor.scopeAt(offset);
            if (scope != null) for (var entry : scope.symbols.entrySet()) {
                if (entry.getValue().size() != 1) continue;
                var symbol = entry.getValue().get(0);
                if ((lexical || whitespace) && !symbol.kind().equals("token")) continue;
                if (whitespace && (!symbol.detail().matches("(?s).*\\btoken\\s+\\w+\\s*::=.*")
                        || entry.getKey().equalsIgnoreCase("none") || entry.getKey().equalsIgnoreCase("javaStyle"))) continue;
                if (qualified && !entry.getKey().startsWith(qualifier.group(1))) continue;
                CompletionItem item = new CompletionItem(entry.getKey());
                item.setKind("token".equals(symbol.kind()) ? CompletionItemKind.Constant : CompletionItemKind.Class);
                item.setDetail(symbol.kind() + " · " + symbol.origin());
                item.setDocumentation(markdown("```ubnf\n" + symbol.detail() + "\n```"));
                int start = offset;
                while (start > 0 && (Character.isLetterOrDigit(content.charAt(start - 1)) || "_.".indexOf(content.charAt(start - 1)) >= 0)) start--;
                item.setTextEdit(Either.forLeft(new TextEdit(UbnfEditorIndex.rangeOf(content, start, offset), entry.getKey())));
                items.add(item);
            }
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        // ---------------------------------------------------------------- hover

        @Override
        public CompletableFuture<Hover> hover(HoverParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(null);
            }
            DocumentIndex index = server.ensureIndex(uri, content);
            String word = wordAt(content, params.getPosition(), true);
            if (word != null && word.startsWith("@")) {
                String help = catalogHelp(word.substring(1));
                if (help != null) return CompletableFuture.completedFuture(new Hover(markdown(help)));
                for (AnnotationDoc annotation : ANNOTATIONS) {
                    if (annotation.label().equals(word)) {
                        return CompletableFuture.completedFuture(new Hover(markdown(
                            "**" + annotation.signature() + "**\n\n" + annotation.doc())));
                    }
                }
            }
            String plain = word != null && word.startsWith("@") ? word.substring(1) : word;
            String help = plain == null ? null : catalogHelp(plain);
            if (help != null && (word.startsWith("@") || CORE_KEYWORDS.contains(plain))) {
                return CompletableFuture.completedFuture(new Hover(markdown(help)));
            }
            var use = index.editor.useAt(UbnfEditorIndex.offset(content, params.getPosition()));
            var decl = index.editor.target(use);
            if (decl != null) {
                StringBuilder md = new StringBuilder();
                md.append("**").append(decl.kind()).append(" ").append(decl.name()).append("**");
                md.append("\n\n```ubnf\n").append(decl.detail()).append("\n```\n\n").append(decl.origin());
                long refs = index.editor.references(use).stream().filter(item -> !item.declaration()).count();
                md.append("\n\n").append(refs).append(" reference(s) in this document");
                return CompletableFuture.completedFuture(new Hover(markdown(md.toString())));
            }
            DocumentState state = server.documents.get(uri);
            if (state != null) {
                String text = state.parseResult().succeeded()
                    && state.parseResult().consumedLength() == state.parseResult().totalLength()
                    ? "Valid UBNF" : "Parse error at offset " + state.parseResult().consumedLength();
                return CompletableFuture.completedFuture(new Hover(markdown(text)));
            }
            return CompletableFuture.completedFuture(null);
        }

        // ---------------------------------------------------------------- navigation

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
                DefinitionParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(Either.forLeft(List.of()));
            }
            DocumentIndex index = server.ensureIndex(uri, content);
            var decl = index.editor.target(index.editor.useAt(UbnfEditorIndex.offset(content, params.getPosition())));
            if (decl == null) {
                return CompletableFuture.completedFuture(Either.forLeft(List.of()));
            }
            return CompletableFuture.completedFuture(Either.forLeft(List.of(decl.location())));
        }

        @Override
        public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            var editor = server.ensureIndex(uri, content).editor;
            var use = editor.useAt(UbnfEditorIndex.offset(content, params.getPosition()));
            List<Location> locations = new ArrayList<>();
            for (var reference : editor.references(use)) {
                if (!reference.declaration() || params.getContext().isIncludeDeclaration())
                    locations.add(new Location(uri, UbnfEditorIndex.rangeOf(content, reference.start(), reference.end())));
            }
            var target = editor.target(use);
            if (target != null && !uri.equals(target.uri()) && params.getContext().isIncludeDeclaration()) locations.add(target.location());
            return CompletableFuture.completedFuture(locations);
        }

        @Override
        public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(new WorkspaceEdit());
            }
            var editor = server.ensureIndex(uri, content).editor;
            var use = editor.useAt(UbnfEditorIndex.offset(content, params.getPosition()));
            String name = params.getNewName();
            if (!editor.editable(use) || !name.matches("[A-Za-z_]\\w*") || CORE_KEYWORDS.contains(name)
                    || use.scope().symbols.containsKey(name)) {
                return CompletableFuture.failedFuture(new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidParams,
                        "安全に名前変更できません。完成した文法のローカル宣言を選び、重複・予約語を避けてください。外部 module の token / import alias / capture は一括 rename の対象外です。", null)));
            }
            List<TextEdit> edits = new ArrayList<>();
            for (var reference : editor.references(use)) {
                edits.add(new TextEdit(UbnfEditorIndex.rangeOf(content, reference.start(), reference.end()), name));
            }
            WorkspaceEdit edit = new WorkspaceEdit();
            edit.setChanges(Map.of(uri, edits));
            return CompletableFuture.completedFuture(edit);
        }

        @Override
        public CompletableFuture<LinkedEditingRanges> linkedEditingRange(LinkedEditingRangeParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(null);
            }
            var editor = server.ensureIndex(uri, content).editor;
            var use = editor.useAt(UbnfEditorIndex.offset(content, params.getPosition()));
            if (!editor.editable(use)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(
                new LinkedEditingRanges(editor.references(use).stream().map(ref -> UbnfEditorIndex.rangeOf(content, ref.start(), ref.end())).toList()));
        }

        // ---------------------------------------------------------------- structure

        @Override
        public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
                DocumentSymbolParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
            if (content == null) {
                return CompletableFuture.completedFuture(result);
            }
            DocumentIndex index = server.ensureIndex(uri, content);
            for (var scope : index.editor.scopes) {
                DocumentSymbol currentGrammar = null;
                for (var decl : index.editor.declarations) {
                    if (decl.start() < scope.start || decl.end() > scope.end) continue;
                    Range range = decl.range();
                    if ("grammar".equals(decl.kind())) {
                        var symbol = new DocumentSymbol(decl.name(), SymbolKind.Namespace,
                            UbnfEditorIndex.rangeOf(content, scope.start, scope.end), range);
                        symbol.setChildren(new ArrayList<>()); result.add(Either.forRight(symbol)); currentGrammar = symbol;
                    } else {
                        var symbol = new DocumentSymbol(decl.name(), "token".equals(decl.kind()) ? SymbolKind.Constant : SymbolKind.Class, range, range);
                        if (currentGrammar != null) currentGrammar.getChildren().add(symbol); else result.add(Either.forRight(symbol));
                    }
                }
            }
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
            String content = contentOf(params.getTextDocument().getUri());
            List<FoldingRange> ranges = new ArrayList<>();
            if (content == null) {
                return CompletableFuture.completedFuture(ranges);
            }
            String[] lines = content.split("\n", -1);
            // grammar ブロック ({ ... }) と複数行ルール (::= ... ;) を折り畳む
            int braceStart = -1;
            int ruleStart = -1;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (braceStart < 0 && line.contains("{") && line.matches("\\s*grammar\\b.*")) {
                    braceStart = i;
                }
                if (braceStart >= 0 && line.strip().equals("}")) {
                    if (i > braceStart) {
                        ranges.add(new FoldingRange(braceStart, i));
                    }
                    braceStart = -1;
                }
                if (ruleStart < 0 && line.contains("::=") && !line.contains(";")) {
                    ruleStart = i;
                } else if (ruleStart >= 0 && line.contains(";")) {
                    if (i > ruleStart) {
                        FoldingRange range = new FoldingRange(ruleStart, i);
                        range.setKind(FoldingRangeKind.Region);
                        ranges.add(range);
                    }
                    ruleStart = -1;
                }
            }
            return CompletableFuture.completedFuture(ranges);
        }

        // ---------------------------------------------------------------- code action

        @Override
        public CompletableFuture<List<Either<org.eclipse.lsp4j.Command, CodeAction>>> codeAction(
                CodeActionParams params) {
            List<Either<org.eclipse.lsp4j.Command, CodeAction>> actions = new ArrayList<>();
            String uri = params.getTextDocument().getUri();
            for (Diagnostic diagnostic : params.getContext().getDiagnostics()) {
                Object code = diagnostic.getCode() != null && diagnostic.getCode().isLeft()
                    ? diagnostic.getCode().getLeft() : null;
                if (!"W-TOKEN-UNRESOLVED".equals(code)) {
                    continue;
                }
                Matcher candidates = Pattern.compile("'((?:\\w+\\.)+\\w+)'").matcher(diagnostic.getMessage());
                while (candidates.find()) {
                    String fqn = candidates.group(1);
                    CodeAction action = new CodeAction("Replace with '" + fqn + "'");
                    action.setKind(CodeActionKind.QuickFix);
                    action.setDiagnostics(List.of(diagnostic));
                    WorkspaceEdit edit = new WorkspaceEdit();
                    edit.setChanges(Map.of(uri, List.of(new TextEdit(diagnostic.getRange(), fqn))));
                    action.setEdit(edit);
                    actions.add(Either.forRight(action));
                }
            }
            return CompletableFuture.completedFuture(actions);
        }

        // ---------------------------------------------------------------- signature help

        @Override
        public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
            String content = contentOf(params.getTextDocument().getUri());
            if (content == null) {
                return CompletableFuture.completedFuture(new SignatureHelp(List.of(), 0, 0));
            }
            String line = lineAt(content, params.getPosition().getLine());
            String prefix = line.substring(0, Math.min(params.getPosition().getCharacter(), line.length()));
            Matcher annotationCall = Pattern.compile("(@\\w+)\\s*\\([^)]*$").matcher(prefix);
            if (!annotationCall.find()) {
                return CompletableFuture.completedFuture(new SignatureHelp(List.of(), 0, 0));
            }
            String name = annotationCall.group(1);
            for (AnnotationDoc annotation : ANNOTATIONS) {
                if (annotation.label().equals(name)) {
                    SignatureInformation signature = new SignatureInformation(annotation.signature());
                    signature.setDocumentation(markdown(annotation.doc()));
                    Matcher args = Pattern.compile("\\(([^)]*)\\)").matcher(annotation.signature());
                    if (args.find()) {
                        List<ParameterInformation> parameters = new ArrayList<>();
                        for (String parameter : args.group(1).split(",")) {
                            parameters.add(new ParameterInformation(parameter.strip()));
                        }
                        signature.setParameters(parameters);
                    }
                    int commas = (int) prefix.chars().filter(c -> c == ',').count();
                    return CompletableFuture.completedFuture(
                        new SignatureHelp(List.of(signature), 0, commas));
                }
            }
            return CompletableFuture.completedFuture(new SignatureHelp(List.of(), 0, 0));
        }

        // ---------------------------------------------------------------- semantic tokens

        @Override
        public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
            String content = contentOf(params.getTextDocument().getUri());
            if (content == null) {
                return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
            }
            return CompletableFuture.completedFuture(new SemanticTokens(tokenize(content)));
        }
    }

    private static String catalogHelp(String id) {
        for (var item : CATALOG.getAsJsonArray("entries")) {
            var entry = item.getAsJsonObject();
            if (!entry.get("id").getAsString().equals(id)) continue;
            StringBuilder text = new StringBuilder(entry.get("title").getAsString());
            text.append("\n\n").append(entry.get("summary").getAsString());
            text.append("\n\n```ubnf\n").append(entry.get("syntax").getAsString()).append("\n```");
            for (var detail : entry.getAsJsonArray("details")) text.append("\n\n").append(detail.getAsString());
            return text.toString();
        }
        return null;
    }

    // =========================================================================
    // semantic tokenizer (行ベース、parse 失敗時も機能する)
    // =========================================================================

    private static final Pattern WORD = Pattern.compile("@?[A-Za-z_][\\w.]*|'(?:\\\\.|[^'])*'|//.*|\\d+|::=|[|;{}()\\[\\]=+*?%,]");

    static List<Integer> tokenize(String content) {
        List<Integer> data = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int previousLine = 0;
        int previousChar = 0;
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            Matcher matcher = WORD.matcher(line);
            boolean inComment = false;
            while (matcher.find() && !inComment) {
                String text = matcher.group();
                int type = classify(text, line, matcher.start());
                if (type < 0) {
                    continue;
                }
                if (text.startsWith("//")) {
                    inComment = true;
                }
                int deltaLine = lineNumber - previousLine;
                int deltaChar = deltaLine == 0 ? matcher.start() - previousChar : matcher.start();
                data.add(deltaLine);
                data.add(deltaChar);
                data.add(text.length());
                data.add(type);
                data.add(0);
                previousLine = lineNumber;
                previousChar = matcher.start();
            }
        }
        return data;
    }

    private static int classify(String text, String line, int start) {
        if (text.startsWith("//")) return TOKEN_TYPES.indexOf("comment");
        if (text.startsWith("'")) return TOKEN_TYPES.indexOf("string");
        if (text.matches("\\d+")) return TOKEN_TYPES.indexOf("number");
        if (text.startsWith("@")) return TOKEN_TYPES.indexOf("macro");
        if (text.matches("::=|[|;{}()\\[\\]=+*?%,]")) return TOKEN_TYPES.indexOf("operator");
        if (CORE_KEYWORDS.contains(text)) {
            return text.matches("[A-Z_]+")
                ? TOKEN_TYPES.indexOf("function") : TOKEN_TYPES.indexOf("keyword");
        }
        if (text.contains(".")) return TOKEN_TYPES.indexOf("namespace");
        if (text.matches("[A-Z][A-Z0-9_]*")) return TOKEN_TYPES.indexOf("property");
        if (text.matches("[A-Z]\\w*")) return TOKEN_TYPES.indexOf("type");
        return -1;
    }

    // =========================================================================
    // テキストユーティリティ
    // =========================================================================

    static Position positionAt(String content, int offset) {
        int line = 0;
        int character = 0;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new Position(line, character);
    }

    static String lineAt(String content, int line) {
        String[] lines = content.split("\n", -1);
        return line >= 0 && line < lines.length ? lines[line] : "";
    }

    /** カーソル位置の単語。includeAt が true なら直前の '@' も含める。 */
    static String wordAt(String content, Position position, boolean includeAt) {
        String line = lineAt(content, position.getLine());
        int character = Math.min(position.getCharacter(), line.length());
        int start = character;
        while (start > 0 && (Character.isLetterOrDigit(line.charAt(start - 1)) || line.charAt(start - 1) == '_')) {
            start--;
        }
        int end = character;
        while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
            end++;
        }
        if (start == end) {
            return null;
        }
        String word = line.substring(start, end);
        if (includeAt && start > 0 && line.charAt(start - 1) == '@') {
            return "@" + word;
        }
        return word;
    }


    static MarkupContent markdown(String value) {
        MarkupContent content = new MarkupContent();
        content.setKind("markdown");
        content.setValue(value);
        return content;
    }
}
