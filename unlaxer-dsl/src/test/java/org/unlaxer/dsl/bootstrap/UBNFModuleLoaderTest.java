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
}
