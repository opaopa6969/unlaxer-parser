package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;

import org.junit.Test;

public class ArgsHashUtilTest {

    @Test
    public void testHashDeterministicForSameConfig() {
        var options = new CodegenCliParser.CliOptions(
            "g.ubnf",
            "out",
            List.of("AST"),
            false,
            false,
            false,
            false,
            false,
            false,
            "json",
            "r1.json",
            "m1.json",
            null,
            null,
            "json",
            1,
            false,
            false,
            "always",
            "conflict",
            -1
        );
        assertEquals(ArgsHashUtil.fromOptions(options), ArgsHashUtil.fromOptions(options));
    }

    @Test
    public void testHashChangesWhenSemanticOptionChanges() {
        var a = new CodegenCliParser.CliOptions(
            "g.ubnf",
            "out",
            List.of("AST"),
            false,
            false,
            false,
            false,
            false,
            false,
            "json",
            "r1.json",
            "m1.json",
            null,
            null,
            "json",
            1,
            false,
            false,
            "always",
            "conflict",
            -1
        );
        var b = new CodegenCliParser.CliOptions(
            "g.ubnf",
            "out",
            List.of("AST"),
            false,
            false,
            false,
            false,
            false,
            false,
            "json",
            "r1.json",
            "m1.json",
            null,
            null,
            "json",
            1,
            false,
            false,
            "always",
            "skipped",
            -1
        );
        assertFalse(ArgsHashUtil.fromOptions(a).equals(ArgsHashUtil.fromOptions(b)));
    }

    @Test
    public void testHashIgnoresDestinationOnlyFields() {
        var a = new CodegenCliParser.CliOptions(
            "g.ubnf",
            "out",
            List.of("AST"),
            false,
            false,
            false,
            false,
            false,
            false,
            "json",
            "report-a.json",
            "manifest-a.json",
            null,
            null,
            "json",
            1,
            false,
            false,
            "always",
            "conflict",
            -1
        );
        var b = new CodegenCliParser.CliOptions(
            "g.ubnf",
            "out",
            List.of("AST"),
            false,
            false,
            false,
            false,
            true,
            true,
            "json",
            "report-b.json",
            "manifest-b.json",
            null,
            null,
            "json",
            1,
            false,
            false,
            "always",
            "conflict",
            -1
        );
        assertEquals(ArgsHashUtil.fromOptions(a), ArgsHashUtil.fromOptions(b));
    }
}
