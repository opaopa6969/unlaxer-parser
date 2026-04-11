package org.unlaxer.dsl.codegen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IndentedWriterTest {

    @Test
    public void lineAppendsTextWithNewline() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.line("hello");
        assertEquals("hello\n", writer.build());
    }

    @Test
    public void lineAppendsMultipleLines() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.line("first");
        writer.line("second");
        assertEquals("first\nsecond\n", writer.build());
    }

    @Test
    public void indentAddsOneLevel() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.indent();
        writer.line("indented");
        assertEquals("    indented\n", writer.build());
    }

    @Test
    public void dedentRemovesOneLevel() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.indent();
        writer.indent();
        writer.line("two levels");
        writer.dedent();
        writer.line("one level");
        assertEquals("        two levels\n    one level\n", writer.build());
    }

    @Test
    public void indentAndDedentProduceCorrectNesting() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.line("class Foo {");
        writer.indent();
        writer.line("void bar() {");
        writer.indent();
        writer.line("doSomething();");
        writer.dedent();
        writer.line("}");
        writer.dedent();
        writer.line("}");

        String expected =
            "class Foo {\n"
                + "    void bar() {\n"
                + "        doSomething();\n"
                + "    }\n"
                + "}\n";
        assertEquals(expected, writer.build());
    }

    @Test
    public void blankLineAppendsEmptyLine() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.line("before");
        writer.blankLine();
        writer.line("after");
        assertEquals("before\n\nafter\n", writer.build());
    }

    @Test
    public void blankLineIgnoresCurrentIndent() {
        IndentedWriter writer = new IndentedWriter(2);
        writer.blankLine();
        assertEquals("\n", writer.build());
    }

    @Test
    public void rawAppendsWithoutIndentOrNewline() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.raw("no-indent");
        assertEquals("no-indent", writer.build());
    }

    @Test
    public void rawDoesNotAffectSubsequentLine() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.raw("prefix");
        writer.line("suffix");
        assertEquals("prefixsuffix\n", writer.build());
    }

    @Test
    public void buildReturnsEmptyStringForNewWriter() {
        IndentedWriter writer = new IndentedWriter(0);
        assertEquals("", writer.build());
    }

    @Test
    public void constructorBaseIndentIsApplied() {
        IndentedWriter writer = new IndentedWriter(2);
        writer.line("deep");
        assertEquals("        deep\n", writer.build());
    }

    @Test
    public void constructorBaseIndentCombinesWithIndent() {
        IndentedWriter writer = new IndentedWriter(1);
        writer.indent();
        writer.line("combined");
        assertEquals("        combined\n", writer.build());
    }

    @Test
    public void nestedMultipleLevelsProducesCorrectSpaces() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.indent();
        writer.indent();
        writer.indent();
        writer.line("three levels");
        assertEquals("            three levels\n", writer.build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void dedentBelowZeroThrowsOnLine() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.dedent();
        writer.line("negative indent");
    }

    @Test
    public void fluentChainingWorks() {
        String result = new IndentedWriter(0)
            .line("a")
            .indent()
            .line("b")
            .dedent()
            .line("c")
            .build();

        String expected =
            "a\n"
                + "    b\n"
                + "c\n";
        assertEquals(expected, result);
    }

    @Test
    public void mixedRawAndLineOutput() {
        IndentedWriter writer = new IndentedWriter(1);
        writer.line("int x = 0;");
        writer.raw("    // raw comment");
        writer.line(" continues");

        String expected =
            "    int x = 0;\n"
                + "    // raw comment     continues\n";
        assertEquals(expected, writer.build());
    }

    @Test
    public void multipleBlankLinesInSuccession() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.line("start");
        writer.blankLine();
        writer.blankLine();
        writer.line("end");
        assertEquals("start\n\n\nend\n", writer.build());
    }

    @Test
    public void buildCanBeCalledMultipleTimes() {
        IndentedWriter writer = new IndentedWriter(0);
        writer.line("hello");
        String firstBuild = writer.build();
        String secondBuild = writer.build();
        assertEquals(firstBuild, secondBuild);
    }
}
