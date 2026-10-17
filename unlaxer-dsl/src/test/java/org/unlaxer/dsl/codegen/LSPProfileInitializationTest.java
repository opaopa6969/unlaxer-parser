package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import javax.tools.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/** Compile and invoke the emitted LSP server, including real JSON initialization options. */
public class LSPProfileInitializationTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    @Test public void generatedServerNegotiatesProfilesAndRejectsInvalidSettings() throws Exception {
        var grammar = UBNFMapper.parse("grammar Java21 { @package: profile.fixture @root CompilationUnit ::= 'hello' '😀'; }").grammars().get(0);
        List<CodeGenerator.GeneratedSource> sources = List.of(new ParserGenerator().generate(grammar), new LSPGenerator().generate(grammar));
        var files = new ArrayList<JavaFileObject>();
        for (var source : sources) files.add(source(source.packageName() + "." + source.className(), source.source()));
        files.add(source("profile.fixture.Server", """
            package profile.fixture;
            public final class Server extends Java21LanguageServer {
                public int failureMode;
                @Override protected void configureAdditionalCapabilities(org.eclipse.lsp4j.ServerCapabilities caps) {
                    if (failureMode == 1) throw new IllegalStateException("configuration hook failed");
                    caps.getSemanticTokensProvider().setLegend(new org.eclipse.lsp4j.SemanticTokensLegend(
                        failureMode == 2 ? java.util.Arrays.asList("valid", null) : java.util.List.of("valid", "invalid"), java.util.List.of("declaration")));
                    caps.setDefinitionProvider(true); caps.setRenameProvider(true);
                    caps.setDocumentFormattingProvider(true); caps.setCodeActionProvider(true);
                    caps.setExecuteCommandProvider(new org.eclipse.lsp4j.ExecuteCommandOptions(java.util.List.of("arbitrary")));
                    caps.setExperimental(java.util.Map.of("existing", "preserved"));
                }
                @Override protected int[] additionalSemanticTokenData(String uri, String content) {
                    return new int[]{0,0,9,1,1,0,1,1,0,0};
                }
            }
            """));
        Path output = temporary.newFolder().toPath(); var compiler = ToolProvider.getSystemJavaCompiler(); var errors = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(errors.toString(), compiler.getTask(new PrintWriter(errors), manager, null,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null, files).call());
        }
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> serverClass = loader.loadClass("profile.fixture.Server");
            LanguageServer server = (LanguageServer) serverClass.getConstructor().newInstance();
            var legacy = server.initialize(new InitializeParams()).join().getCapabilities();
            assertNotNull(legacy.getCompletionProvider()); assertEquals(Boolean.TRUE, legacy.getHoverProvider().getLeft());
            String profile = Files.readString(Path.of("../language-profiles/java/profile.tsv"));
            var params = new InitializeParams();
            params.setInitializationOptions(Map.of("languageProfile", Map.of("tsv", profile, "entry", "CompilationUnit")));
            var selected = configure(serverClass, server, params).join().getCapabilities();
            assertNull(selected.getExecuteCommandProvider()); assertNull(selected.getCompletionProvider()); assertEquals(Boolean.FALSE, selected.getHoverProvider().getLeft());
            assertEquals(Boolean.FALSE, selected.getDefinitionProvider().getLeft()); assertEquals(Boolean.FALSE, selected.getRenameProvider().getLeft());
            assertEquals(Boolean.FALSE, selected.getDocumentFormattingProvider().getLeft()); assertEquals(Boolean.FALSE, selected.getCodeActionProvider().getLeft());
            var extensions = new com.google.gson.Gson().toJsonTree(selected.getExperimental()).getAsJsonObject();
            assertEquals("preserved", extensions.get("existing").getAsString());
            assertEquals("lang/java", extensions.getAsJsonObject("languageProfile").get("package").getAsString());
            assertEquals("CompilationUnit", extensions.getAsJsonObject("languageProfile").get("entry").getAsString());
            assertTrue(server.getTextDocumentService().completion(null).join().getLeft().isEmpty());
            assertNull(server.getTextDocumentService().hover(null).join());
            Object selection = serverClass.getMethod("languageProfile").invoke(server);
            for (Object invalid : List.of(
                    Map.of("tsv", profile, "entry", "Expression"),
                    Map.of("tsv", profile.replace("Java21", "Java22"), "entry", "CompilationUnit"),
                    Map.of("tsv", profile.replace("PARSE\tPARTIAL", "PARSE\tEXTERNAL"), "entry", "CompilationUnit"),
                    Map.of("tsv", profile, "entry", "CompilationUnit", "path", "/unused"),
                    Map.of("tsv", 7, "entry", "CompilationUnit"), "bad")) {
                params.setInitializationOptions(Map.of("languageProfile", invalid));
                CompletionException failure = assertThrows(CompletionException.class, () -> configure(serverClass, server, params).join());
                var protocol = (org.eclipse.lsp4j.jsonrpc.ResponseErrorException) failure.getCause();
                assertEquals(-32602, protocol.getResponseError().getCode());
                assertEquals(selection, serverClass.getMethod("languageProfile").invoke(server));
            }
            // Gson JsonObject is the production JSON-RPC representation; SUPPORTED/PARTIAL may retain existing capability.
            String local = profile.replace("COMPLETION\tUNSUPPORTED", "COMPLETION\tPARTIAL");
            params.setInitializationOptions(new com.google.gson.Gson().toJsonTree(Map.of("languageProfile", Map.of("tsv", local, "entry", "CompilationUnit"))));
            var localCaps = configure(serverClass, server, params).join().getCapabilities(); assertNotNull(localCaps.getCompletionProvider());
            CompletionParams completion = new CompletionParams(new TextDocumentIdentifier("file:///unicode"), new Position(0, 0));
            assertFalse(server.getTextDocumentService().completion(completion).join().getLeft().isEmpty());
            // Explicit profile selection does not change the parser's CP/UTF-16 conversion.
            var parse = serverClass.getMethod("parseDocument", String.class, String.class).invoke(server, "file:///unicode", "hello😀");
            assertEquals(Boolean.TRUE, parse.getClass().getMethod("succeeded").invoke(parse));
            // Fail after profile selection and after client flags change, not just during validation.
            var semantic = new SemanticTokensCapabilities();
            semantic.setMultilineTokenSupport(true); semantic.setOverlappingTokenSupport(true);
            var textCapabilities = new TextDocumentClientCapabilities(); textCapabilities.setSemanticTokens(semantic);
            var clientCapabilities = new ClientCapabilities(); clientCapabilities.setTextDocument(textCapabilities);
            params.setCapabilities(clientCapabilities);
            configure(serverClass, server, params).join();
            Object goodSelection = serverClass.getMethod("languageProfile").invoke(server);
            serverClass.getMethod("parseDocument", String.class, String.class).invoke(server, "file:///unicode", "hello\r\n😀");
            var tokens = new SemanticTokensParams(new TextDocumentIdentifier("file:///unicode"));
            var expectedTokens = List.of(0,0,9,1,1,0,1,1,0,0);
            assertEquals(expectedTokens, server.getTextDocumentService().semanticTokensFull(tokens).join().getData());
            var invalidConstruction = new InitializeParams();
            invalidConstruction.setInitializationOptions(Map.of("languageProfile", Map.of("tsv", profile, "entry", "CompilationUnit")));
            for (int mode : List.of(1, 2)) {
                serverClass.getField("failureMode").setInt(server, mode);
                assertThrows(CompletionException.class, () -> configure(serverClass, server, invalidConstruction).join());
                assertEquals(goodSelection, serverClass.getMethod("languageProfile").invoke(server));
                assertFalse(server.getTextDocumentService().completion(completion).join().getLeft().isEmpty());
                assertEquals(expectedTokens, server.getTextDocumentService().semanticTokensFull(tokens).join().getData());
            }
            serverClass.getField("failureMode").setInt(server, 0);
            configure(serverClass, server, params).join();
        }
    }
    private JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return text; }
        };
    }
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.InitializeResult> configure(
            Class<?> type, Object server, org.eclipse.lsp4j.InitializeParams params) throws Exception {
        return (java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.InitializeResult>)
            type.getMethod("configure", org.eclipse.lsp4j.InitializeParams.class).invoke(server, params);
    }

}
