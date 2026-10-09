package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import javax.tools.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.semantic.*;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.source.*;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.source.SegmentSourceMap.*;

/** Real generated parser + semantic rule provider + generated LSP, including non-BMP host positions. */
public class LSPQueryConsumerTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    @Test public void actualProviderCompletionsUseHostSnapshotAndUtf16() throws Exception {
        Path corpus = Path.of("../spec-corpus/semantic-rules");
        var grammar = UBNFMapper.parse(Files.readString(corpus.resolve("model.ubnf")).replace("grammar TypedModel {", "grammar TypedModel { @package: query.fixture")).grammars().get(0);
        var program = SemanticRulesLoader.load(Files.readString(corpus.resolve("rules.json")), grammar);
        var inventory = SemanticRulesLoader.inventory(grammar);
        var sources = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator : List.of(new ASTGenerator(), new ParserGenerator(), new MapperGenerator(), new LSPGenerator())) {
            var output = generator.generate(grammar); sources.add(source(output.packageName() + "." + output.className(), output.source()));
        }
        sources.add(source("query.fixture.Server", """
            package query.fixture;
            public class Server extends TypedModelLanguageServer {
                public java.util.function.Function<org.unlaxer.source.DocumentSnapshot, org.unlaxer.source.LanguageQueries> binding;
                @Override protected org.unlaxer.source.LanguageQueries languageQueries(org.unlaxer.source.DocumentSnapshot host) { return binding.apply(host); }
                @Override protected java.util.Set<org.unlaxer.source.LanguageRegions.Operation> languageQueryCapabilities() {
                    return java.util.EnumSet.allOf(org.unlaxer.source.LanguageRegions.Operation.class);
                }
            }
            """));
        Path output = temporary.newFolder().toPath(); var compiler = ToolProvider.getSystemJavaCompiler(); var errors = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(errors.toString(), compiler.getTask(new PrintWriter(errors), manager, null,
                List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null, sources).call());
        }
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("query.fixture.Server");
            LanguageServer server = (LanguageServer) type.getConstructor().newInstance();
            var parse = loader.loadClass("query.fixture.TypedModelMapper").getMethod("parseEditorCst", String.class, List.class, EditorCst.Options.class);
            var language = new Language("typed", "example/typed", "1", "TypedModel", "Document");
            Function<DocumentSnapshot, LanguageQueries> binding = host -> {
                boolean closed = host.text().endsWith("}H");
                String body = host.text().substring(3, host.text().length() - (closed ? 2 : 0));
                EditorCst cst;
                try { cst = (EditorCst) parse.invoke(null, body, List.of("?", ")", ";", "}", "a", ":", "{"), EditorCst.Options.defaults()); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                var inner = new DocumentSnapshot("virtual:typed", host.version(), body);
                var map = new SegmentSourceMap(inner, List.of(new Segment(new Span(0, inner.length()), Kind.COPY, new SegmentSourceMap.Location(host, new Span(2, 2 + inner.length())))));
                var region = new Region("typed", null, language, new Span(0, host.length()), new Span(2, 2 + inner.length()), map, State.valueOf(cst.status().name()));
                var tree = new LanguageRegions(host, List.of(region), closed ? Set.of() : Set.of("typed"));
                var project = new LanguageQueries.Project("p", 3, Map.of(host.uri(), host), Map.of());
                return new LanguageQueries(tree, project, Map.of(language, new SemanticRuleQueryProvider(program, inventory, language, "p", 3, request -> cst)));
            };
            type.getField("binding").set(server, binding);
            String profile = Files.readString(Path.of("../language-profiles/java/profile.tsv"))
                .replace("Java21", "TypedModel").replace("CompilationUnit", "Document").replace("COMPLETION\tUNSUPPORTED", "COMPLETION\tEXTERNAL");
            var initialize = new InitializeParams();
            initialize.setInitializationOptions(Map.of("languageProfile", Map.of("tsv", profile, "entry", "Document")));
            assertNotNull(server.initialize(initialize).join().getCapabilities().getCompletionProvider());
            String body = "/*😀*/ builtin Int{} interface Accessor{} record Db:Accessor{} record Wrong{} let db:Db; let wrong:Wrong; fn process(Accessor):Int; call process(";
            String host = "😀{" + body;
            var service = server.getTextDocumentService();
            service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem("file:///host", "typed", 7, host)));
            var position = new Position(0, host.length());
            var request = new CompletionParams(new TextDocumentIdentifier("file:///host"), position);
            var items = service.completion(request).join().getLeft();
            assertEquals(List.of("db"), items.stream().map(CompletionItem::getLabel).toList());
            var edit = items.get(0).getTextEdit().getLeft();
            assertEquals("db", edit.getNewText()); assertEquals(position, edit.getRange().getStart()); assertEquals(position, edit.getRange().getEnd());
            assertEquals("7", ((Map<?,?>)items.get(0).getData()).get("version"));
            assertEquals("PARTIAL", ((Map<?,?>)items.get(0).getData()).get("state"));
            // Cursor between surrogate halves is rejected; it must not fall back to grammar keywords.
            request.setPosition(new Position(0, 1)); assertTrue(service.completion(request).join().getLeft().isEmpty());
            request.setPosition(new Position(0, host.length()));
            // Exact closed-body endpoint belongs to the delimiter, regardless of the child's PARTIAL state.
            String closed = host + "}H";
            service.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier("file:///host", 8), List.of(new TextDocumentContentChangeEvent(closed))));
            assertTrue(service.completion(request).join().getLeft().isEmpty());
            // A cached provider for an old document version is rejected, never served as fresh candidates.
            var stale = binding.apply(new DocumentSnapshot("file:///host", 7, host));
            type.getField("binding").set(server, (Function<DocumentSnapshot, LanguageQueries>) ignored -> stale);
            assertTrue(service.completion(request).join().getLeft().isEmpty());
            // Merely selecting external completion without a provider does not create candidates.
            type.getField("binding").set(server, (Function<DocumentSnapshot, LanguageQueries>) current -> {
                var original = binding.apply(current);
                return new LanguageQueries(new LanguageRegions(current, List.of()), original.project(), Map.of());
            });
            request.setPosition(new Position(0, 4)); assertTrue(service.completion(request).join().getLeft().isEmpty());
            type.getField("binding").set(server, (Function<DocumentSnapshot, LanguageQueries>) ignored -> null);
            assertTrue(service.completion(request).join().getLeft().isEmpty());
            // The same consumer routes a different concrete provider and keeps foreign-document coordinates.
            type.getField("binding").set(server, (Function<DocumentSnapshot, LanguageQueries>) LSPQueryConsumerTest::projectBinding);
            var capabilities = server.initialize(new InitializeParams()).join().getCapabilities();
            assertEquals(Boolean.TRUE, capabilities.getDefinitionProvider().getLeft());
            var table = new com.google.gson.Gson().toJsonTree(capabilities.getExperimental()).getAsJsonObject().getAsJsonObject("languageQueryConsumer").getAsJsonObject("operations");
            for (String operation : List.of("RENAME", "FORMAT", "CODE_ACTION")) {
                var value = table.getAsJsonObject(operation);
                assertTrue(value.get("providerRegistered").getAsBoolean());
                assertTrue(value.get("profileAllowed").getAsBoolean());
                assertFalse(value.get("transport").getAsBoolean());
                assertFalse(value.get("available").getAsBoolean());
            }
            assertNull(capabilities.getRenameProvider());assertNull(capabilities.getDocumentFormattingProvider());assertNull(capabilities.getCodeActionProvider());
            service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem("file:///symbols", "typed", 1, "😀{foo far foo f }H")));
            var local = service.definition(new DefinitionParams(new TextDocumentIdentifier("file:///symbols"), new Position(0, 12))).join().getLeft();
            assertEquals(1, local.size()); assertEquals("file:///symbols", local.get(0).getUri());
            assertEquals(new Range(new Position(0, 3), new Position(0, 6)), local.get(0).getRange());
            var foreign = service.definition(new DefinitionParams(new TextDocumentIdentifier("file:///symbols"), new Position(0, 8))).join().getLeft();
            assertEquals(1, foreign.size()); assertEquals("file:///library", foreign.get(0).getUri());
            assertEquals(new Range(new Position(0, 2), new Position(0, 5)), foreign.get(0).getRange());
            var hover = service.hover(new HoverParams(new TextDocumentIdentifier("file:///symbols"), new Position(0, 8))).join();
            assertEquals("far: T", hover.getContents().getRight().getValue());
            var queryRows = Files.readAllLines(Path.of("../docs/fixtures/classic-lsp/providers.jsonl"));
            assertEquals(6, queryRows.size());
            var gson = new com.google.gson.Gson();
            for (String row : queryRows) {
                var oracle = com.google.gson.JsonParser.parseString(row).getAsJsonObject();
                String method = oracle.get("method").getAsString();
                var cursor = new Position(0, oracle.get("character").getAsInt());
                var document = new TextDocumentIdentifier("file:///symbols");
                com.google.gson.JsonElement observed;
                if (method.endsWith("completion")) {
                    var values = service.completion(new CompletionParams(document, cursor)).join().getLeft();
                    var observedItems = new com.google.gson.JsonArray();
                    for (var item : values) {
                        var value = new com.google.gson.JsonObject(); value.addProperty("label", item.getLabel());
                        value.add("range", gson.toJsonTree(item.getTextEdit().getLeft().getRange())); observedItems.add(value);
                    }
                    observed = observedItems;
                } else if (method.endsWith("hover")) {
                    observed = new com.google.gson.JsonPrimitive(service.hover(new HoverParams(document, cursor)).join().getContents().getRight().getValue());
                } else {
                    observed = gson.toJsonTree(service.definition(new DefinitionParams(document, cursor)).join().getLeft());
                }
                assertEquals(method + "@" + cursor, oracle.get("expected"), observed);
            }

        }
    }
    private static LanguageQueries projectBinding(DocumentSnapshot host) {
        var inner = new DocumentSnapshot("virtual:symbols", host.version(), "foo far foo f ");
        var library = new DocumentSnapshot("file:///library", 2, "😀bar");
        var localModel = symbolModel(inner, "local", "foo", 0, 3);
        var foreignModel = symbolModel(library, "export", "bar", 1, 4);
        var imports = List.of(new ProjectSymbolIndex.Import("far", new ProjectSymbolIndex.ModuleRef("", "library"), "bar", "root", new SemanticModel.Span(0, 0), 0));
        var index = new ProjectSymbolIndex("symbols", 4, List.of(
            new ProjectSymbolIndex.Module("inner", localModel, Set.of(), imports),
            new ProjectSymbolIndex.Module("library", foreignModel, Set.of("export"), List.of())), List.of());
        var language = new Language("typed", "example/typed", "1", "TypedModel", "Document");
        var map = new SegmentSourceMap(inner, List.of(new Segment(new Span(0, inner.length()), Kind.COPY, new SegmentSourceMap.Location(host, new Span(2, 2 + inner.length())))));
        var region = new Region("symbols", null, language, new Span(0, host.length()), new Span(2, 2 + inner.length()), map, State.COMPLETE);
        var project = new LanguageQueries.Project("symbols", 4, Map.of(host.uri(), host, library.uri(), library), Map.of());
        return new LanguageQueries(new LanguageRegions(host, List.of(region)), project, Map.of(language, new ProjectQueryProvider(index)));
    }
    private static SemanticModel symbolModel(DocumentSnapshot source, String id, String name, int start, int end) {
        return new SemanticModel(source.uri(), source.version(), source.text(),
            List.of(new SemanticModel.Type("T", SemanticModel.TypeKind.BUILTIN, List.of(), List.of(), new SemanticModel.Span(0, 0))),
            List.of(new SemanticModel.Scope("root", null, new SemanticModel.Span(0, source.length()))),
            List.of(new SemanticModel.Symbol(id, name, "T", "root", new SemanticModel.Span(start, end), end)), List.of(), List.of());
    }
    private static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return text; }
        };
    }
}
