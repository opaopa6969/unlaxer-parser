package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

public class CodegenSnapshotTest {

    @Test
    public void testParserGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new ParserGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/parser_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testMapperGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new MapperGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/mapper_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testRightAssocParserGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.RIGHT_ASSOC_SNAPSHOT_GRAMMAR);
        String actual = new ParserGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/parser_right_assoc_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testRightAssocMapperGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.RIGHT_ASSOC_SNAPSHOT_GRAMMAR);
        String actual = new MapperGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/mapper_right_assoc_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testLspGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new LSPGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/lsp_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testDapGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new DAPGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/dap_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testLspLauncherGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new LSPLauncherGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/lsp_launcher_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testDapLauncherGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new DAPLauncherGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/dap_launcher_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testAstGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new ASTGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/ast_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    @Test
    public void testEvaluatorGeneratorSnapshot() throws Exception {
        GrammarDecl grammar = parseGrammar(SnapshotFixtureData.SNAPSHOT_GRAMMAR);
        String actual = new EvaluatorGenerator().generate(grammar).source();
        String expected = Files.readString(Path.of("src/test/resources/golden/evaluator_snapshot.java.txt"));
        assertEquals(normalize(expected), normalize(actual));
    }

    private GrammarDecl parseGrammar(String source) {
        return UBNFMapper.parse(source).grammars().get(0);
    }

    private String normalize(String s) {
        return s.replace("\r\n", "\n");
    }
}
