package org.unlaxer.dsl;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.Test;
import org.unlaxer.source.LanguageProfile;

public class LanguageProfileCommandTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    @Test public void fixedProfileCliAndInvalidChoiceHaveExplicitResults() throws Exception {
        Path profile = Path.of("../language-profiles/java/profile.tsv");
        var bytes = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var err = new PrintStream(errors, true, StandardCharsets.UTF_8);
        assertEquals(0, CodegenMain.run(new String[]{"profile", "--file", profile.toString()}, out, err));
        assertEquals(LanguageProfile.parse(Files.readString(profile)).canonicalTsv(), bytes.toString(StandardCharsets.UTF_8));
        assertEquals(2, CodegenMain.run(new String[]{"profile", "--file"}, out, err));
        assertEquals(2, CodegenMain.run(new String[]{"playground", "--profile", profile.toString(), "--grammar", "irrelevant.ubnf", "--output", "unused"}, out, err));
    }
    @Test public void profileGenerationRejectsEmptyAndMismatchedEntryGrammars() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Files.copy(Path.of("../language-profiles/java/profile.tsv"), directory.resolve("profile.tsv"));
        for (String source : new String[]{"", "grammar Java21 { @root CompilationUnit ::= 'ok'; }"}) {
            Files.writeString(directory.resolve("Java21.ubnf"), source);
            assertThrows(IllegalArgumentException.class, () -> org.unlaxer.dsl.codegen.rust.PlaygroundGenerator.generateProfile(directory.resolve("profile.tsv")));
        }
    }

}
