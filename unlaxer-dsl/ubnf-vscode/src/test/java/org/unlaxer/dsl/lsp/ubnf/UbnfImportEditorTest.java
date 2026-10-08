package org.unlaxer.dsl.lsp.ubnf;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UbnfImportEditorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final UBNFLanguageServerExt server = new UBNFLanguageServerExt();
    private final TextDocumentService service = server.getTextDocumentService();
    private static final String NUMBERS = "grammar Numbers { @ubnf: v2 token DIGIT ::= CHAR_RANGE('0','9'); token NUMBER ::= DIGIT+; }";
    private static final String MAIN = "grammar Main {\n @import num from 'numbers.ubnf'\n @ubnf: v2\n @root @mapping(Value, params=[value])\n Start ::= num.NUMBER @value;\n}";
    private Path file(String name, String text) throws Exception { Path path = temporary.getRoot().toPath().resolve(name); Files.writeString(path, text); return path; }
    private void open(Path file, String text) { service.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(file.toUri().toString(), "ubnf", 1, text))); }
    private Position at(String text, String needle) { return UBNFLanguageServerExt.positionAt(text, text.indexOf(needle)); }
    private TextDocumentIdentifier id(Path path) { return new TextDocumentIdentifier(path.toUri().toString()); }
    private List<CompletionItem> completion(Path path, String text, String marker) throws Exception {
        return service.completion(new CompletionParams(id(path), UBNFLanguageServerExt.positionAt(text, text.indexOf(marker) + marker.length()))).get().getLeft();
    }

    @Test public void importsResolveCompletionDefinitionHoverAndReferences() throws Exception {
        Path module = file("numbers.ubnf", NUMBERS), main = file("main.ubnf", MAIN); open(main, MAIN);
        assertTrue(server.ensureIndex(main.toUri().toString(), MAIN).validation.toString(), server.ensureIndex(main.toUri().toString(), MAIN).validation.isEmpty());
        var items = completion(main, MAIN, "num.");
        var number = items.stream().filter(item -> item.getLabel().equals("num.NUMBER")).findFirst().orElseThrow();
        assertEquals("num.NUMBER", number.getTextEdit().getLeft().getNewText());
        assertEquals(at(MAIN, "num.NUMBER"), number.getTextEdit().getLeft().getRange().getStart());
        var position = at(MAIN, "num.NUMBER");
        var definitions = service.definition(new DefinitionParams(id(main), position)).get().getLeft();
        assertEquals(1, definitions.size()); assertEquals(module.toUri().toString(), definitions.get(0).getUri());
        assertEquals(at(NUMBERS, "NUMBER ::="), definitions.get(0).getRange().getStart());
        assertTrue(service.hover(new HoverParams(id(main), position)).get().getContents().getRight().getValue().contains("DIGIT+"));
        var references = service.references(new ReferenceParams(id(main), position, new ReferenceContext(false))).get();
        assertEquals(1, references.size());
        assertEquals(2, service.references(new ReferenceParams(id(main), position, new ReferenceContext(true))).get().size());
    }

    @Test public void partialQualifiedCompletionUsesSavedImportsAndNeverAuthorizesRename() throws Exception {
        file("numbers.ubnf", NUMBERS);
        String text = MAIN.replace("num.NUMBER", "num.");
        Path main = file("main.ubnf", text); open(main, text);
        assertTrue(completion(main, text, "num.").stream().anyMatch(item -> item.getLabel().equals("num.NUMBER")));
        rejectRename(main, text, "Start", "Entry");
    }

    @Test public void openModuleEditsAndCloseInvalidateImportResults() throws Exception {
        Path module = file("numbers.ubnf", NUMBERS), main = file("main.ubnf", MAIN); open(main, MAIN);
        String changed = NUMBERS.replace("NUMBER", "INTEGER"); open(module, changed);
        assertTrue(completion(main, MAIN, "num.").stream().anyMatch(item -> item.getLabel().equals("num.INTEGER")));
        assertTrue(server.ensureIndex(main.toUri().toString(), MAIN).validation.stream().anyMatch(item -> "E-MODULE".equals(item.getCode().getLeft())));
        service.didClose(new DidCloseTextDocumentParams(id(module)));
        assertTrue(server.ensureIndex(main.toUri().toString(), MAIN).validation.isEmpty());
        assertTrue(completion(main, MAIN, "num.").stream().anyMatch(item -> item.getLabel().equals("num.NUMBER")));
        Files.writeString(module, changed);
        assertTrue(server.ensureIndex(main.toUri().toString(), MAIN).validation.stream().anyMatch(item -> "E-MODULE".equals(item.getCode().getLeft())));
    }

    @Test public void missingCyclicAndNonTokenModulesHaveExplicitDiagnostics() throws Exception {
        Path main = file("main.ubnf", MAIN);
        assertModuleError(main, MAIN, "numbers.ubnf");
        file("numbers.ubnf", "grammar Numbers { @import self from 'numbers.ubnf' @ubnf: v2 token NUMBER ::= 'n'; }");
        assertModuleError(main, MAIN, "cyclic import");
        Files.writeString(temporary.getRoot().toPath().resolve("numbers.ubnf"), "grammar Numbers { @root Start ::= 'n'; }");
        assertModuleError(main, MAIN, "only declarative token modules");
    }

    private void assertModuleError(Path path, String text, String part) {
        var issues = server.ensureIndex(path.toUri().toString(), text).validation;
        assertTrue(issues.toString(), issues.stream().anyMatch(item -> "E-MODULE".equals(item.getCode().getLeft()) && item.getMessage().contains(part)));
    }

    @Test public void renameIsAstBoundNotTextReplacement() throws Exception {
        String text = """
            grammar Safe {
              @ubnf: v2
              token DIGIT ::= CHAR_RANGE('0','9');
              token TEXT ::= CAPTURE(DIGIT, DIGIT+) SAME_AS(DIGIT);
              // DIGIT is a token, not this comment
              @root @mapping(Value, params=[DIGIT]) @doc('DIGIT stays here')
              Start ::= TEXT @DIGIT 'DIGIT';
            }
            """;
        Path main = file("safe.ubnf", text); open(main, text);
        var edit = service.rename(new RenameParams(id(main), at(text, "DIGIT ::="), "DECIMAL")).get();
        var edits = edit.getChanges().get(main.toUri().toString());
        assertEquals(2, edits.size());
        assertEquals(at(text, "DIGIT+"), edits.get(1).getRange().getStart());
        assertTrue(service.definition(new DefinitionParams(id(main), at(text, "DIGIT stays"))).get().getLeft().isEmpty());
        rejectRename(main, text, "@DIGIT", "Other");
        rejectRename(main, text, "DIGIT ::=", "TEXT");
        rejectRename(main, text, "DIGIT ::=", "ANY");
        rejectRename(main, text, "DIGIT ::=", "bad-name");
    }

    @Test public void grammarScopesAndUtf16OriginsRemainDistinct() throws Exception {
        String text = "grammar A { @ubnf: v2 token EMOJI ::= '😀'; token N ::= 'a'; @root Start ::= N; }\r\n"
            + "grammar B { @ubnf: v2 token N ::= 'b'; @root Start ::= N; }";
        Path main = file("scopes.ubnf", text); open(main, text);
        var editor = server.ensureIndex(main.toUri().toString(), text).editor;
        assertTrue(editor.safe);
        var first = service.rename(new RenameParams(id(main), at(text, "N ::= 'a'"), "LETTER")).get();
        assertEquals(2, first.getChanges().get(main.toUri().toString()).size());
        assertTrue(first.getChanges().get(main.toUri().toString()).stream().allMatch(edit -> edit.getRange().getStart().getLine() == 0));
        var definition = service.definition(new DefinitionParams(id(main), UBNFLanguageServerExt.positionAt(text, text.lastIndexOf("N;")))).get().getLeft();
        assertEquals(1, definition.get(0).getRange().getStart().getLine());
        assertEquals(at(text, "N ::= 'a'"), first.getChanges().get(main.toUri().toString()).get(0).getRange().getStart());
        var outline = service.documentSymbol(new DocumentSymbolParams(id(main))).get();
        assertEquals(2, outline.size());
        assertEquals(2, outline.get(1).getRight().getChildren().size());
    }

    @Test public void importedAndExportedTokenRenameAreRejected() throws Exception {
        Path module = file("numbers.ubnf", NUMBERS), main = file("main.ubnf", MAIN); open(main, MAIN); open(module, NUMBERS);
        rejectRename(main, MAIN, "num.NUMBER", "INTEGER");
        rejectRename(module, NUMBERS, "NUMBER ::=", "INTEGER");
        assertTrue(server.ensureIndex(module.toUri().toString(), NUMBERS).validation.isEmpty());
    }

    @Test public void nestedElementsKeepRealReferenceOrigins() throws Exception {
        String text = "grammar G { @ubnf: v2 token N ::= 'n'; @root Start ::= (N | 'x') [N] {N} N+ N? N* N{2,3} N % ','; }";
        Path main = file("nested.ubnf", text); open(main, text);
        var editor = server.ensureIndex(main.toUri().toString(), text).editor;
        assertTrue(editor.safe);
        assertEquals(9, service.rename(new RenameParams(id(main), at(text, "N ::="), "LETTER")).get().getChanges().get(main.toUri().toString()).size());
    }

    @Test public void declarativeCompletionDoesNotOfferLegacyOrRuleOnlyWords() throws Exception {
        String text = "grammar G { @ubnf: v2 token N ::= CHAR_RANGE('0', '9')+; @root Start ::= N; }";
        Path main = file("completion.ubnf", text); open(main, text);
        var labels = completion(main, text, "N ::= ").stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(UBNFLanguageServerExt.LEXICAL_KEYWORDS));
        assertTrue(UBNFLanguageServerExt.CORE_KEYWORDS.containsAll(UBNFLanguageServerExt.LEXICAL_KEYWORDS));
        assertFalse(labels.contains("REGEX")); assertFalse(labels.contains("Start")); assertFalse(labels.contains("ERROR"));
    }

    @Test public void duplicateDeclarationsDoNotAuthorizeRename() throws Exception {
        String text = "grammar G { @ubnf: v2 token N ::= 'n'; token N ::= 'x'; @root Start ::= N; }";
        Path main = file("duplicate.ubnf", text); open(main, text);
        rejectRename(main, text, "N ::=", "LETTER");
    }

    @Test public void quotedAndCommentedFakeDeclarationsAreNotIndexed() throws Exception {
        String text = "grammar Safe {\n @ubnf: v2\n // token FAKE ::= 'a';\n token WORD ::= 'token FAKE ::= xyz';\n @root Start ::= WORD;\n}";
        Path main = file("safe.ubnf", text); open(main, text);
        assertFalse(server.ensureIndex(main.toUri().toString(), text).decls.containsKey("FAKE"));
        assertTrue(service.definition(new DefinitionParams(id(main), at(text, "FAKE"))).get().getLeft().isEmpty());
    }

    private void rejectRename(Path main, String text, String marker, String next) throws Exception {
        try { service.rename(new RenameParams(id(main), at(text, marker), next)).get(); fail("unsafe rename must fail: " + marker); }
        catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("安全に名前変更")); }
    }
}
