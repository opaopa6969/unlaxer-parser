package org.unlaxer.dsl.codegen;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Debug utility test — prints generated parser content for inspection.
 * Not part of the regular test suite; kept for developer use.
 */
public class PrintGeneratedTest {
    @Test
    public void printLines() throws Exception {
        String grammarSource = Files.readString(Path.of("grammar/ubnf.ubnf"));
        GrammarDecl grammar = UBNFMapper.parse(grammarSource).grammars().get(0);
        ParserGenerator gen = new ParserGenerator();
        String src = gen.generate(grammar).source();
        String[] lines = src.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("TypeofElement") || lines[i].contains("typeof")) {
                int start = Math.max(0, i-2);
                int end = Math.min(lines.length, i+5);
                for (int j = start; j < end; j++) {
                    System.out.println((j+1) + ": " + lines[j]);
                }
                System.out.println("---");
            }
        }
    }
}
