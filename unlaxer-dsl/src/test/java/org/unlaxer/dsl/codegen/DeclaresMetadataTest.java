package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

public class DeclaresMetadataTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void compiledQueriesRetainSymbolDescriptionAbsenceAndDeclarationOrder() throws Exception {
        var parsed = UBNFMapper.parse("""
            grammar G {
              @root Root ::= A B;
              @declares(symbol=name, description=doc) A ::= 'a' @name;
              @declares(symbol=other) B ::= 'b' @other;
            }
            """).grammars().get(0);
        var rules = new ArrayList<>(parsed.rules());
        String description = "doc\"\\\n😀";
        rules.set(1, new RuleDecl(List.of(new DeclaresAnnotation("name", description)),
            rules.get(1).name(), rules.get(1).body()));
        var grammar = new GrammarDecl(parsed.name(), parsed.settings(), parsed.tokens(), rules);
        String code = "public class Metadata {" + ParserMetadataEmitter.generateAdvancedAnnotationMetadata(grammar) + "}";
        var unit = new SimpleJavaFileObject(URI.create("string:///Metadata.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-d", output.toString()), null, List.of(unit)).call());
        }
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            var metadata = loader.loadClass("Metadata");
            var lookup = metadata.getMethod("getDeclaresSpec", String.class);
            var first = ((Optional<?>) lookup.invoke(null, "A")).orElseThrow();
            assertEquals("A", first.getClass().getMethod("ruleName").invoke(first));
            assertEquals("name", first.getClass().getMethod("symbolCapture").invoke(first));
            assertEquals(Optional.of(description), first.getClass().getMethod("description").invoke(first));
            var second = ((Optional<?>) lookup.invoke(null, "B")).orElseThrow();
            assertEquals(Optional.empty(), second.getClass().getMethod("description").invoke(second));
            assertEquals(Optional.empty(), lookup.invoke(null, "Root"));
            assertEquals(Optional.empty(), lookup.invoke(null, "unknown"));
            assertEquals(List.of(first, second), metadata.getMethod("getDeclaresSpecs").invoke(null));
        }
    }

    @Test public void unannotatedGrammarsDoNotGainMetadataOutput() {
        var grammar = UBNFMapper.parse("grammar G { @root Root ::= 'a'; }").grammars().get(0);
        assertEquals("", ParserMetadataEmitter.generateAdvancedAnnotationMetadata(grammar));
    }
}
