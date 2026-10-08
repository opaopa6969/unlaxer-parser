package org.unlaxer.editor;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

public class EditorCstTest {
    private static EditorCst parse(String source,String literal,List<String> completions,EditorCst.Options options) {
        Parser parser=new WordParser(literal);
        return EditorCst.parse(source,parser,completions,value->value==parser?"Root":null,value->value==parser?List.of("value"):List.of(),options);
    }
    @Test public void insertedScalarOnlyExposesOriginalCaptureText() {
        EditorCst parsed=parse("😀","😀a",List.of("a"),EditorCst.Options.defaults());
        assertEquals(EditorCst.Status.PARTIAL,parsed.status());assertEquals(EditorCst.Reason.REPAIRED,parsed.reason());
        EditorCst.Capture capture=parsed.nodes().get(0).captures().get(0);
        assertEquals(new EditorCst.Span(0,1),capture.span());assertTrue(capture.synthetic());assertEquals("😀",capture.text());
        assertEquals(new EditorCst.Span(1,1),parsed.defects().get(0).span());assertEquals(List.of("Root"),parsed.defects().get(0).candidateRules());
    }
    @Test public void syntaxAndSearchLimitRemainDistinct() {
        assertEquals(EditorCst.Reason.SYNTAX,parse("h@","hello",List.of("o"),EditorCst.Options.defaults()).reason());
        assertEquals(EditorCst.Reason.LIMIT,parse("hell","hello",List.of("o"),new EditorCst.Options(4,0)).reason());
        assertEquals(EditorCst.Reason.NO_COMPLETION,parse("","hello",List.of(),EditorCst.Options.defaults()).reason());
        assertEquals(EditorCst.Status.COMPLETE,parse("hello","hello",List.of(),EditorCst.Options.defaults()).status());
    }
}
