package org.unlaxer.dsl.codegen;

import java.nio.file.Files;
import java.nio.file.Path;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * Utility entry point to refresh golden snapshot fixtures.
 *
 * <p>Run from project root after test classes are compiled:</p>
 *
 * <pre>
 *   mvn -q -DskipTests test-compile
 *   CP="target/classes:target/test-classes:$(mvn -q -DincludeScope=test -Dmdep.outputFile=/tmp/unlaxer-dsl-test-cp.txt dependency:build-classpath >/dev/null && cat /tmp/unlaxer-dsl-test-cp.txt)"
 *   java --enable-preview -cp "$CP" org.unlaxer.dsl.codegen.SnapshotFixtureWriter
 * </pre>
 */
public final class SnapshotFixtureWriter {

    private SnapshotFixtureWriter() {}

    public static void main(String[] args) throws Exception {
        Path outputDir = resolveOutputDir(args);
        Files.createDirectories(outputDir);

        GrammarDecl snapshot = UBNFMapper.parse(SnapshotFixtureData.SNAPSHOT_GRAMMAR).grammars().get(0);
        GrammarDecl rightAssoc = UBNFMapper.parse(SnapshotFixtureData.RIGHT_ASSOC_SNAPSHOT_GRAMMAR).grammars().get(0);

        Files.writeString(outputDir.resolve("ast_snapshot.java.txt"),
            new ASTGenerator().generate(snapshot).source());
        Files.writeString(outputDir.resolve("evaluator_snapshot.java.txt"),
            new EvaluatorGenerator().generate(snapshot).source());
        Files.writeString(outputDir.resolve("parser_snapshot.java.txt"),
            new ParserGenerator().generate(snapshot).source());
        Files.writeString(outputDir.resolve("mapper_snapshot.java.txt"),
            new MapperGenerator().generate(snapshot).source());
        Files.writeString(outputDir.resolve("lsp_snapshot.java.txt"),
            new LSPGenerator().generate(snapshot).source());
        Files.writeString(outputDir.resolve("lsp_launcher_snapshot.java.txt"),
            new LSPLauncherGenerator().generate(snapshot).source());
        Files.writeString(outputDir.resolve("dap_snapshot.java.txt"),
            new DAPGenerator().generate(snapshot).source());
        Files.writeString(outputDir.resolve("dap_launcher_snapshot.java.txt"),
            new DAPLauncherGenerator().generate(snapshot).source());

        Files.writeString(outputDir.resolve("parser_right_assoc_snapshot.java.txt"),
            new ParserGenerator().generate(rightAssoc).source());
        Files.writeString(outputDir.resolve("mapper_right_assoc_snapshot.java.txt"),
            new MapperGenerator().generate(rightAssoc).source());
    }

    private static Path resolveOutputDir(String[] args) {
        if (args == null || args.length == 0) {
            return Path.of("src/test/resources/golden");
        }
        if (args.length == 2 && "--output-dir".equals(args[0])) {
            return Path.of(args[1]);
        }
        throw new IllegalArgumentException(
            "Usage: SnapshotFixtureWriter [--output-dir <path>]"
        );
    }
}
