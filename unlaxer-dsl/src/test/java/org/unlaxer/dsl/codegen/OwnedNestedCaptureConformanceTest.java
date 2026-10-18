package org.unlaxer.dsl.codegen;

/** Owned bindings retain typed nested values and preferred CST candidates. */
public class OwnedNestedCaptureConformanceTest extends NestedMappedCaptureConformanceTest {
    @Override protected String corpusPath() { return "spec-corpus/owned-nested-capture/cases.json"; }
    @Override protected String reportName() { return "owned-nested-capture.tsv"; }
    @Override protected java.nio.file.Path nativeTarget() { return java.nio.file.Path.of("target/owned-nested-native").toAbsolutePath(); }

    @org.junit.Test public void ownedWrappersPreserveNestedRejectionsAndTraversalLimits() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        success(run(java.util.List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        for (String body : java.util.List.of(
                "CAPTURE_SCOPE([ 'a' @value ])",
                "CAPTURE(word, ('a' @value)) @value",
                "CAPTURE_SCOPE(".repeat(33) + "'a' @value" + ")".repeat(33))) {
            String text = "grammar InvalidOwnedNested { @root @nestedCaptures @mapping(Root,params=[value]) Root ::= " + body + "; }";
            var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(text).grammars().get(0);
            for (CodeGenerator generator : java.util.List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator())) {
                org.junit.Assert.assertTrue(org.junit.Assert.assertThrows(IllegalArgumentException.class,()->generator.generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
            }
            org.junit.Assert.assertTrue(org.junit.Assert.assertThrows(IllegalArgumentException.class,()->new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
            var directory=temporary.newFolder().toPath(); var input=directory.resolve("input.ubnf");var output=directory.resolve("output");
            java.nio.file.Files.writeString(input,text);
            var nativeResult=run(java.util.List.of(nativeTarget().resolve("debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",output.toString()),"",true);
            org.junit.Assert.assertNotEquals(nativeResult.output(),0,nativeResult.code());
            org.junit.Assert.assertTrue(nativeResult.output(),nativeResult.output().contains("E-NESTED-SHAPE"));
            org.junit.Assert.assertFalse(java.nio.file.Files.exists(output));
        }
    }
}
