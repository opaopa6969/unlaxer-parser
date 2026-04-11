package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.BeforeClass;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * ubnf.ubnf から全 codegen パイプライン (AST/Parser/Mapper/LSP) を走らせる検証。
 *
 * <p>Issue #4 Phase 3 の前提確認: ubnf.ubnf が完全な文法として
 * 各 generator に処理可能であることを証明する。</p>
 *
 * <p>各 generator が例外なく生成を完了し、非空の出力を返すことを検証する。
 * 生成内容の意味論的正しさは SelfHostingRoundTripTest で確認済み。</p>
 */
public class UbnfCodegenPipelineTest {

    private static GrammarDecl grammar;

    @BeforeClass
    public static void parseGrammar() throws Exception {
        String source = Files.readString(Path.of("grammar/ubnf.ubnf"));
        grammar = UBNFMapper.parse(source).grammars().get(0);
        assertNotNull("UBNF grammar should parse successfully", grammar);
    }

    @Test
    public void testParserGenerationProducesOutput() {
        CodeGenerator.GeneratedSource result = new ParserGenerator().generate(grammar);
        assertNotNull(result);
        assertNotNull(result.source());
        assertFalse("Parser source should be non-empty", result.source().isEmpty());
        assertTrue("Parser source should contain UBNFParsers class",
            result.source().contains("class UBNFParsers"));
    }

    @Test
    public void testASTGenerationProducesOutput() {
        CodeGenerator.GeneratedSource result = new ASTGenerator().generate(grammar);
        assertNotNull(result);
        assertNotNull(result.source());
        assertFalse("AST source should be non-empty", result.source().isEmpty());
    }

    @Test
    public void testMapperGenerationProducesOutput() {
        CodeGenerator.GeneratedSource result = new MapperGenerator().generate(grammar);
        assertNotNull(result);
        assertNotNull(result.source());
        assertFalse("Mapper source should be non-empty", result.source().isEmpty());
    }

    @Test
    public void testLSPGenerationProducesOutput() {
        CodeGenerator.GeneratedSource result = new LSPGenerator().generate(grammar);
        assertNotNull(result);
        assertNotNull(result.source());
        assertFalse("LSP source should be non-empty", result.source().isEmpty());
        // @declares/@backref があるため LSP サーバーには定義/参照機能が含まれる
        assertTrue("LSP source should contain ScopeStore integration for @declares",
            result.source().contains("ScopeStore"));
    }
}
