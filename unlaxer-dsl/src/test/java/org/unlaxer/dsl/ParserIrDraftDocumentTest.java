package org.unlaxer.dsl;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class ParserIrDraftDocumentTest {

    @Test
    public void testParserIrDraftContainsCoreSections() throws Exception {
        String markdown = Files.readString(Path.of("docs/PARSER-IR-DRAFT.md"));

        assertTrue(markdown.contains("# Parser IR Draft"));
        assertTrue(markdown.contains("## 2. Placement Rule: BNF Extension vs Annotation"));
        assertTrue(markdown.contains("## 4. Parser Annotation IR (Shared Contract)"));
        assertTrue(markdown.contains("### 4.1 v1 Field Matrix (Draft)"));
        assertTrue(markdown.contains("### 4.3 Versioning Policy (Draft)"));
        assertTrue(markdown.contains("## 5. Non-UBNF Parser Integration"));
        assertTrue(markdown.contains("ParserIrAdapter"));
        assertTrue(markdown.contains("blank source"));
        assertTrue(markdown.contains("define missing kind"));
        assertTrue(markdown.contains("use with forbidden kind"));
        assertTrue(markdown.contains("duplicate related diagnostics"));
        assertTrue(markdown.contains("parent missing child link"));
        assertTrue(markdown.contains("`define` requires `kind`; `use` must not include `kind`."));
    }
}
