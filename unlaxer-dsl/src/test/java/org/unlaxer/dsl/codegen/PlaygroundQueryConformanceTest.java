package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.*;

/** Java/native generators emit the same adapter ABI and execute concrete providers inside real WASM. */
public class PlaygroundQueryConformanceTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    @Test public void generatedRegistryAndProvidersExecuteInWasm() throws Exception {
        assumeTrue("requires -DrustConformance=true and wasm32 target", Boolean.getBoolean("rustConformance"));
        Path repo = Path.of("..").toAbsolutePath().normalize(), embedded=repo.resolve("docs/fixtures/embedded-grammars"), fixtures=repo.resolve("docs/fixtures/playground-queries");
        Path output=temporary.newFolder().toPath(), grammar=embedded.resolve("FormulaInfoPlayground.ubnf");
        var javaFiles=PlaygroundGenerator.generate(grammar);
        for(var entry:javaFiles.entrySet()) { Path file=output.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue()); }
        run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"));
        Path nativeOutput=temporary.getRoot().toPath().resolve("native");
        run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(),"playground","--grammar",grammar.toString(),"--output",nativeOutput.toString()));
        for(var entry:javaFiles.entrySet()) assertEquals(entry.getKey(),entry.getValue(),Files.readString(nativeOutput.resolve(entry.getKey())));
        for(String name:List.of("Java","TinyExpression")) {
            Path file=(name.equals("Java")?fixtures:embedded).resolve(name+".ubnf");
            var declaration=UBNFMapper.parse(Files.readString(file)).grammars().get(0);
            Path module=output.resolve("src/"+(name.equals("Java")?"java":"tiny"));Files.createDirectories(module);
            for(var source:new RustBackend().generate(declaration))Files.writeString(module.resolve(source.relativePath()),source.content());
        }
        Files.copy(fixtures.resolve("query_adapter.rs"),output.resolve("src/query_adapter.rs"),StandardCopyOption.REPLACE_EXISTING);
        run(List.of("node",output.resolve("build.mjs").toString()));
        run(List.of("node",fixtures.resolve("probe.mjs").toString(),output.resolve("public/language.wasm").toString()));
    }
    private void run(List<String> args) throws Exception {
        Path log=temporary.newFile().toPath();var builder=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("CARGO_INCREMENTAL","0");
        var process=builder.start();if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();fail("timeout: "+args);}
        assertEquals(args+"\n"+Files.readString(log),0,process.exitValue());
    }
}
