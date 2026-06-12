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
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.generated.UBNFLanguageServer;
import org.unlaxer.dsl.codegen.GrammarValidator;

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
        new AnnotationDoc("@whitespace", "@whitespace: ${1:javaStyle}", "@whitespace: javaStyle|none",
            "Implicit whitespace skipping between rule elements."),
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
            "Imports rules from another .ubnf grammar."),
        new AnnotationDoc("@recovery", "@recovery(${1:sync})", "@recovery(sync|auto|skip)",
            "Error recovery strategy for this rule."),
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

    static final List<String> CORE_KEYWORDS = List.of(
        "grammar", "token", "params", "from", "level", "mode", "symbol",
        "UNTIL", "NEGATION", "LOOKAHEAD", "NEGATIVE_LOOKAHEAD", "CHAR_RANGE",
        "REGEX", "ANY", "EOF", "EMPTY", "CI");

    record BlockSnippet(String label, String detail, String body) {}

    static final List<BlockSnippet> BLOCK_SNIPPETS = List.of(
        new BlockSnippet("grammar", "grammar block skeleton",
            "grammar ${1:Name} {\n  @package: ${2:org.example}\n\n  token ${3:NUMBER} = org.unlaxer.parser.elementary.NumberParser\n\n  @root\n  ${4:Start} ::= ${5:NUMBER} ;\n}$0"),
        new BlockSnippet("token", "token declaration",
            "token ${1:NAME} = ${2:org.unlaxer.parser.elementary.NumberParser}$0"),
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

        DocumentIndex(String content, List<UBNFAST.GrammarDecl> grammars) {
            this.content = content;
            this.grammars = grammars;
        }
    }

    private final Map<String, DocumentIndex> indexByUri = new LinkedHashMap<>();

    private static final Pattern GRAMMAR_DECL = Pattern.compile("^[ \\t]*grammar[ \\t]+([A-Za-z_]\\w*)", Pattern.MULTILINE);
    private static final Pattern TOKEN_DECL = Pattern.compile("^[ \\t]*token[ \\t]+([A-Za-z_]\\w*)[ \\t]*=[ \\t]*(\\S+)", Pattern.MULTILINE);
    private static final Pattern RULE_DECL = Pattern.compile("^[ \\t]*([A-Za-z_]\\w*)[ \\t]*::=", Pattern.MULTILINE);

    DocumentIndex ensureIndex(String uri, String content) {
        DocumentIndex cached = indexByUri.get(uri);
        if (cached != null && cached.content.equals(content)) {
            return cached;
        }
        List<UBNFAST.GrammarDecl> grammars = null;
        try {
            grammars = UBNFMapper.parse(content).grammars();
        } catch (RuntimeException ignored) {
            // パース失敗時は構文ベース機能のみ提供
        }
        DocumentIndex index = new DocumentIndex(content, grammars);
        indexDecls(content, index);
        if (grammars != null) {
            collectValidationDiagnostics(content, grammars, index);
        }
        indexByUri.put(uri, index);
        return index;
    }

    private void indexDecls(String content, DocumentIndex index) {
        Matcher g = GRAMMAR_DECL.matcher(content);
        while (g.find()) {
            addDecl(index, content, g.start(1), g.group(1), "grammar", null);
        }
        Matcher t = TOKEN_DECL.matcher(content);
        while (t.find()) {
            addDecl(index, content, t.start(1), t.group(1), "token", t.group(2));
        }
        Matcher r = RULE_DECL.matcher(content);
        while (r.find()) {
            String name = r.group(1);
            if ("grammar".equals(name) || "token".equals(name)) {
                continue;
            }
            addDecl(index, content, r.start(1), name, "rule", null);
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

        private String contentOf(String uri) {
            DocumentState state = server.documents.get(uri);
            return state != null ? state.content() : null;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            server.parseDocument(params.getTextDocument().getUri(), params.getTextDocument().getText());
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            server.parseDocumentIncremental(
                params.getTextDocument().getUri(), params.getContentChanges().get(0).getText());
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
            String uri = params.getTextDocument().getUri();
            server.documents.remove(uri);
            server.indexByUri.remove(uri);
        }

        @Override
        public void didSave(DidSaveTextDocumentParams params) {}

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
                    item.setDocumentation(markdown(annotation.doc()));
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

            for (String keyword : CORE_KEYWORDS) {
                CompletionItem item = new CompletionItem(keyword);
                item.setKind(CompletionItemKind.Keyword);
                items.add(item);
            }
            for (BlockSnippet snippet : BLOCK_SNIPPETS) {
                CompletionItem item = new CompletionItem(snippet.label());
                item.setKind(CompletionItemKind.Snippet);
                item.setDetail(snippet.detail());
                item.setInsertTextFormat(InsertTextFormat.Snippet);
                item.setInsertText(snippet.body());
                items.add(item);
            }
            for (DeclSite decl : index.decls.values()) {
                CompletionItem item = new CompletionItem(decl.name());
                item.setKind("token".equals(decl.kind())
                    ? CompletionItemKind.Constant : CompletionItemKind.Class);
                item.setDetail(decl.kind() + (decl.detail() != null ? " = " + decl.detail() : ""));
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
                for (AnnotationDoc annotation : ANNOTATIONS) {
                    if (annotation.label().equals(word)) {
                        return CompletableFuture.completedFuture(new Hover(markdown(
                            "**" + annotation.signature() + "**\n\n" + annotation.doc())));
                    }
                }
            }
            String plain = word != null && word.startsWith("@") ? word.substring(1) : word;
            DeclSite decl = plain == null ? null : index.decls.get(plain);
            if (decl != null) {
                StringBuilder md = new StringBuilder();
                md.append("**").append(decl.kind()).append(" ").append(decl.name()).append("**");
                if (decl.detail() != null) {
                    md.append(" = `").append(decl.detail()).append("`");
                }
                String declLine = lineAt(content, decl.line()).strip();
                md.append("\n\n```ubnf\n").append(declLine).append("\n```");
                int refs = findWordOccurrences(content, decl.name()).size();
                md.append("\n\n").append(refs - 1).append(" reference(s)");
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
            String word = wordAt(content, params.getPosition(), false);
            DeclSite decl = word == null ? null : index.decls.get(word);
            if (decl == null) {
                return CompletableFuture.completedFuture(Either.forLeft(List.of()));
            }
            Range range = new Range(new Position(decl.line(), decl.startChar()),
                new Position(decl.line(), decl.endChar()));
            return CompletableFuture.completedFuture(Either.forLeft(List.of(new Location(uri, range))));
        }

        @Override
        public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            String word = wordAt(content, params.getPosition(), false);
            if (word == null) {
                return CompletableFuture.completedFuture(List.of());
            }
            List<Location> locations = new ArrayList<>();
            for (Range range : findWordOccurrences(content, word)) {
                locations.add(new Location(uri, range));
            }
            return CompletableFuture.completedFuture(locations);
        }

        @Override
        public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
            String uri = params.getTextDocument().getUri();
            String content = contentOf(uri);
            if (content == null) {
                return CompletableFuture.completedFuture(new WorkspaceEdit());
            }
            String word = wordAt(content, params.getPosition(), false);
            if (word == null) {
                return CompletableFuture.completedFuture(new WorkspaceEdit());
            }
            List<TextEdit> edits = new ArrayList<>();
            for (Range range : findWordOccurrences(content, word)) {
                edits.add(new TextEdit(range, params.getNewName()));
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
            String word = wordAt(content, params.getPosition(), false);
            DocumentIndex index = server.ensureIndex(uri, content);
            if (word == null || !index.decls.containsKey(word)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(
                new LinkedEditingRanges(findWordOccurrences(content, word)));
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
            DocumentSymbol currentGrammar = null;
            int totalLines = countLines(content);
            for (DeclSite decl : index.decls.values()) {
                Range range = new Range(new Position(decl.line(), decl.startChar()),
                    new Position(decl.line(), decl.endChar()));
                if ("grammar".equals(decl.kind())) {
                    DocumentSymbol symbol = new DocumentSymbol(decl.name(), SymbolKind.Namespace,
                        new Range(new Position(decl.line(), 0), new Position(totalLines - 1, 0)), range);
                    symbol.setChildren(new ArrayList<>());
                    result.add(Either.forRight(symbol));
                    currentGrammar = symbol;
                    continue;
                }
                SymbolKind kind = "token".equals(decl.kind()) ? SymbolKind.Constant : SymbolKind.Class;
                DocumentSymbol symbol = new DocumentSymbol(decl.name(), kind, range, range);
                if (decl.detail() != null) {
                    symbol.setDetail(decl.detail());
                }
                if (currentGrammar != null) {
                    currentGrammar.getChildren().add(symbol);
                } else {
                    result.add(Either.forRight(symbol));
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

    static int countLines(String content) {
        return content.split("\n", -1).length;
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

    /** 文字列・コメントを除いた word の全出現範囲。 */
    static List<Range> findWordOccurrences(String content, String word) {
        List<Range> occurrences = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            int comment = line.indexOf("//");
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                if (comment >= 0 && matcher.start() >= comment) {
                    break;
                }
                if (insideString(line, matcher.start())) {
                    continue;
                }
                occurrences.add(new Range(new Position(lineNumber, matcher.start()),
                    new Position(lineNumber, matcher.end())));
            }
        }
        return occurrences;
    }

    private static boolean insideString(String line, int index) {
        boolean inString = false;
        for (int i = 0; i < index; i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '\'') {
                inString = !inString;
            }
        }
        return inString;
    }

    static MarkupContent markdown(String value) {
        MarkupContent content = new MarkupContent();
        content.setKind("markdown");
        content.setValue(value);
        return content;
    }
}
