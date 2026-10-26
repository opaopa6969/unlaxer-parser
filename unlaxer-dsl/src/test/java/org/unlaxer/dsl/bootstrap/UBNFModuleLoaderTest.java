package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.runtime.LexicalTokenParser;

public class UBNFModuleLoaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path corpus = Path.of("../spec-corpus/lexical-modules");

    @Test public void fileApiResolvesButStringApiDoesNotReadImports() throws Exception {
        Path root = corpus.resolve("root.ubnf");
        assertFalse(PortabilityCheck.check(Files.readString(root)).portable());
        assertTrue(PortabilityCheck.checkFile(root).portable());
        var grammar = UBNFModuleLoader.load(root).grammars().get(0);
        assertTrue(grammar.imports().isEmpty());
        assertEquals(2, grammar.settings().size());
        var parser = new LexicalTokenParser("ALL", LexicalCompiler.compile(grammar).get("ALL"));
        try (var context = new ParseContext(StringSource.createRootSource("a12:😀😀a"))) {
            assertTrue(parser.parse(context).isSucceeded());
            assertEquals(7, context.position());
            assertEquals(7, context.matchedPosition());
        }
    }

    @Test public void invalidModuleGraphsAreRejected() throws Exception {
        var fixtures = JsonParser.parseString(Files.readString(corpus.resolve("invalid.json"))).getAsJsonArray();
        for (var value : fixtures) {
            var fixture = value.getAsJsonObject();
            Path directory = temporary.newFolder().toPath();
            for (var file : fixture.getAsJsonObject("files").entrySet())
                Files.writeString(directory.resolve(file.getKey()), file.getValue().getAsString());
            assertThrows(fixture.get("name").getAsString(), Exception.class, () -> {
                var grammar = UBNFModuleLoader.load(directory.resolve("root.ubnf")).grammars().get(0);
                LexicalCompiler.compile(grammar);
                new org.unlaxer.dsl.codegen.ParserGenerator().generate(grammar);
            });
            assertFalse(fixture.get("name").getAsString(), PortabilityCheck.checkFile(directory.resolve("root.ubnf")).portable());
        }
    }

    @Test public void linkingCopiesRootOriginsWithoutMutatingTheOriginalSnapshot() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("main.ubnf");
        Files.writeString(root.resolveSibling("part.ubnf"),
            "grammar Part { @ubnf: v2 token X ::= 'x'; }");
        String source = "// 😀\r\ngrammar Main { @import part from 'part.ubnf' @ubnf: v2 "
            + "@root @mapping(Value,params=[text]) Root ::= part.X @text; }";
        var original = UBNFMapper.parseWithSource(source);
        var linked = UBNFModuleLoader.resolveWithSource(original, root, Files::readString);
        var grammar = linked.ast().grammars().get(0);
        var rule = grammar.rules().get(0);
        var element = ((UBNFAST.ChoiceBody) rule.body()).alternatives().get(0).elements().get(0);
        var oldRule = original.ast().grammars().get(0).rules().get(0);
        var oldElement = ((UBNFAST.ChoiceBody) oldRule.body()).alternatives().get(0).elements().get(0);
        assertEquals(original.spanOf(original.ast()), linked.spanOf(linked.ast()));
        assertEquals(original.spanOf(oldRule), linked.spanOf(rule));
        assertEquals(original.captureSpan(oldElement), linked.captureSpan(element));
        assertEquals("part.X", linked.sourceOf(element.element()).orElseThrow());
        assertTrue(linked.spanOf(grammar.tokens().get(0)).isEmpty());
        assertTrue(original.spanOf(grammar).isEmpty());
        assertEquals(1, original.ast().grammars().get(0).imports().size());
        assertTrue(original.ast().grammars().get(0).tokens().isEmpty());
        assertEquals(source, linked.source());
    }
}
