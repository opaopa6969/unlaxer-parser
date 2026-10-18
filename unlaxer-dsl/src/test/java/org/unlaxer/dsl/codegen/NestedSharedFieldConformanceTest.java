package org.unlaxer.dsl.codegen;

import com.google.gson.JsonObject;
import static org.junit.Assert.*;
import java.net.URLClassLoader;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;

/** One exact recursive record contract shared by different source rules. */
public class NestedSharedFieldConformanceTest extends NestedCaptureConformanceTest {
    private final NestedMappedCaptureConformanceTest mapped = new NestedMappedCaptureConformanceTest();
    @Override protected String corpusPath() { return "spec-corpus/nested-shared-fields/cases.json"; }
    @Override protected String reportName() { return "nested-shared-fields.tsv"; }
    @Override protected String javaRelease() { return "21"; }
    @Override protected java.nio.file.Path nativeTarget() { return java.nio.file.Path.of("target/nested-shared-native").toAbsolutePath(); }
    @Override protected List<GeneratedSource> extraJavaSources(GrammarDecl grammar) { return mapped.extraJavaSources(grammar); }
    @Override protected String extraRustSource(JsonObject fixture) { return mapped.extraRustSource(fixture); }
    @Override protected String extraRustBeforeDrop(JsonObject fixture) { return mapped.extraRustBeforeDrop(fixture); }
    @Override protected String extraRustObservation(JsonObject fixture) {
        String type=fixture.getAsJsonObject("rustFieldTypes").getAsJsonObject("Root").get("value").getAsString();
        return mapped.extraRustObservation(fixture)+"let common: &"+type+"=ast.common_family().unwrap().value;assert_eq!(common.structure(),generated::evaluator::evaluate(&ast,&mut NestedMappedSemantics));";
    }
    @Override protected void observeJavaMapped(JsonObject fixture,JsonObject row,GrammarDecl grammar,URLClassLoader loader,Object ast,Object snapshot)throws Exception {
        mapped.observeJavaMapped(fixture,row,grammar,loader,ast,snapshot);
        Class<?> family=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"AST$Family");
        org.junit.Assert.assertSame(ast.getClass().getMethod("value").invoke(ast),family.getMethod("value").invoke(ast));
    }
    @org.junit.Test public void unsupportedShapesFailBeforeEmissionWithSourceOrigins() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var corpus=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/nested-shared-fields/invalid.json"))).getAsJsonArray();
        for(var entry:corpus) {
            var row=entry.getAsJsonObject();String text=row.get("grammar").getAsString();
            org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot source;
            try { source=org.unlaxer.dsl.bootstrap.UBNFMapper.parseWithSource(text); } catch(IllegalArgumentException error) { throw new AssertionError(row.get("id").getAsString()+": "+error.getMessage(),error); }
            var grammar=source.ast().grammars().get(0);
            var diagnostic=GrammarValidator.validateWithoutClassLoading(grammar).stream().filter(d->d.code().equals("E-NESTED-SHAPE")).findFirst().orElseThrow();
            assertEquals(row.get("rule").getAsString(),diagnostic.rule());
            var rule=grammar.rules().stream().filter(r->r.name().equals(diagnostic.rule())).findFirst().orElseThrow();
            var position=source.spanOf(rule).orElseThrow();
            assertEquals(row.get("startCP").getAsInt(),position.start());assertEquals(row.get("endCP").getAsInt(),position.end());
            for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator())) {
                assertTrue(assertThrows(IllegalArgumentException.class,()->generator.generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
            }
            assertTrue(assertThrows(IllegalArgumentException.class,()->new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
            var directory=temporary.newFolder().toPath();var input=directory.resolve("invalid.ubnf");var output=directory.resolve("output");java.nio.file.Files.writeString(input,text);
            var nativeResult=run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",output.toString()),"",true);
            assertNotEquals(row.get("id").getAsString()+nativeResult.output(),0,nativeResult.code());
            assertTrue(nativeResult.output(),nativeResult.output().contains("E-NESTED-SHAPE"));
            assertTrue(nativeResult.output(),nativeResult.output().contains("at CP "+position.start()+".."+position.end()));
            assertFalse(java.nio.file.Files.exists(output));
        }
    }

    @org.junit.Test public void nestedFamilyAccessorsUseTheActualRecursiveFieldTypes() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var probe=new CommonFieldConformanceTest() {
            @Override protected com.google.gson.JsonArray corpus(String name) throws Exception {
                return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(NestedSharedFieldConformanceTest.this.repo.resolve("spec-corpus/nested-shared-fields/family-"+name+".json"))).getAsJsonArray();
            }
        };
        probe.temporary.create();
        try { probe.javaTypedAccessorsAndFailures();probe.bothHostsCompileAndEvaluate(); }
        finally { probe.temporary.delete(); }
    }
    @org.junit.Test public void apiImpactReportsActualRecursiveTypesOnBothHosts() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var corpus=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve(corpusPath()))).getAsJsonArray();
        String before=corpus.get(0).getAsJsonObject().get("grammar").getAsString();
        String after=before.replace("[ {Leaf} ]","{ {Leaf} }");
        for(String target:List.of("java","rust")) {
            var impact=org.unlaxer.dsl.impact.ApiImpact.compare(target,before,after);
            assertTrue(impact.toJson(),impact.ok());
            assertTrue(impact.changes().stream().anyMatch(c->c.kind().equals("FIELD_TYPE_CHANGED")&&c.subject().equals("Root.value")));
            assertTrue(impact.changes().stream().anyMatch(c->c.kind().equals("FIELD_CARDINALITY_CHANGED")&&c.subject().equals("Root.value")));
        }
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var directory=temporary.newFolder().toPath();java.nio.file.Files.writeString(directory.resolve("before.ubnf"),before);java.nio.file.Files.writeString(directory.resolve("after.ubnf"),after);
        var nativeResult=run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"impact","--target","rust","--before",directory.resolve("before.ubnf").toString(),"--after",directory.resolve("after.ubnf").toString()),"",true);
        success(nativeResult);
        assertEquals(com.google.gson.JsonParser.parseString(org.unlaxer.dsl.impact.ApiImpact.compare("rust",before,after).toJson()),com.google.gson.JsonParser.parseString(nativeResult.output()));
    }

    @org.junit.Test public void mappingFailuresAndParseRollbackKeepLaterConsumersUsable() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var fixture=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/nested-shared-fields/failures.json"))).getAsJsonObject();
        var grammar=org.unlaxer.dsl.bootstrap.UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
        var directory=temporary.newFolder().toPath();
        var runtime=directory.resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc","--edition=2021","--crate-type=rlib","--crate-name=unlaxer_runtime",repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),"",false));
        var generated=java.nio.file.Files.createDirectory(directory.resolve("generated"));
        var ubnf=directory.resolve("fixture.ubnf");java.nio.file.Files.writeString(ubnf,fixture.get("grammar").getAsString());
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        success(run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"generate","--grammar",ubnf.toString(),"--output",generated.toString()),"",true));
        for(var file:new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar))assertEquals(file.content(),java.nio.file.Files.readString(generated.resolve(file.relativePath())));
        var rust=new StringBuilder("mod generated; fn main(){\n");
        try(var loader=compileJava(grammar)) {
            var parsers=loader.loadClass("org.example.nestedcaptures.SharedNumericNodeParsers");
            var parser=(org.unlaxer.parser.Parser)parsers.getMethod("getRootParser").invoke(null);
            var mapper=loader.loadClass("org.example.nestedcaptures.SharedNumericNodeMapper");
            var astType=loader.loadClass("org.example.nestedcaptures.SharedNumericNodeAST");
            var evaluator=loader.loadClass("org.example.nestedcaptures.NestedMappedProbe");
            for(var entry:fixture.getAsJsonArray("cases")) {
                var row=entry.getAsJsonObject();String input=row.get("input").getAsString();
                rust.append("{let input=").append(row.get("input")).append(';');
                if(row.get("parse").getAsBoolean()) {

                    try(var context=new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                        assertTrue(row.get("id").getAsString(),parser.parse(context).isSucceeded());
                        assertEquals(input.codePointCount(0,input.length()),context.getConsumedPosition().value());
                    }
                    var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->mapper.getMethod("parse",String.class).invoke(null,input));
                    assertTrue(error.getCause().toString(),error.getCause() instanceof IllegalArgumentException);
                    rust.append("let tree=generated::parser::parse_tree(input).unwrap(); assert!(generated::mapper::map(&tree).is_err());");
                } else {
                    try(var context=new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                        assertTrue(parser.parse(context).isFailed());
                        assertEquals(row.get("consumed").getAsInt(),context.getConsumedPosition().value());
                        assertEquals(row.get("matched").getAsInt(),context.getMatchedPosition().value());
                        assertTrue(new org.unlaxer.parser.elementary.WordParser(input).parse(context).isSucceeded());
                        assertEquals(input.codePointCount(0,input.length()),context.getConsumedPosition().value());
                    }
                    rust.append("let mut context=unlaxer_runtime::ParseContext::new(input); assert!(generated::parser::parse_context(&mut context).is_err()); assert_eq!((context.position(),context.matched_position()),(").append(row.get("consumed")).append(',').append(row.get("matched")).append(")); assert!(context.node(0).is_none()); assert!(context.capture_spans(\"value\").is_empty()); context.parse(&unlaxer_runtime::Expr::Literal(input)).unwrap(); assert_eq!(context.position(),input.chars().count());");
                }
                // The same generated parser/mapper remains usable after either failure phase.
                Object ast=mapper.getMethod("parse",String.class).invoke(null,fixture.get("validInput").getAsString());
                assertEquals(fixture.get("validStructure").getAsString(),evaluator.getMethod("eval",astType).invoke(evaluator.getConstructor().newInstance(),ast));
                rust.append("let tree=generated::parser::parse_tree(").append(fixture.get("validInput")).append(").unwrap(); let ast=generated::mapper::map(&tree).unwrap(); assert!(ast.canonical_json().contains(\"\\\"text\\\":7\"));}\n");
            }
        }
        rust.append('}');java.nio.file.Files.writeString(directory.resolve("main.rs"),rust);
        success(run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,directory.resolve("main.rs").toString(),"-o",directory.resolve("probe").toString()),"",false));
        success(run(List.of(directory.resolve("probe").toString()),"",true));
    }

    @org.junit.Test public void portabilityAgreesWithSupportedSchemasAndLocatedFailures() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        for(String corpus:List.of("cases", "family-generated", "invalid", "family-invalid")) {
            var rows=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/nested-shared-fields/"+corpus+".json"))).getAsJsonArray();
            for(var entry:rows) {
                var row=entry.getAsJsonObject();
                String source=row.get("grammar").getAsString();
                boolean expected=!corpus.endsWith("invalid");
                var actual=org.unlaxer.dsl.PortabilityCheck.check(source);
                assertEquals(source+"\n"+actual,expected,actual.portable());
                assertEquals(expected?"passed":"failed",actual.structure());
                assertEquals(expected?0:1,actual.diagnostics().size());
                if(!expected) {
                    var diagnostic=actual.diagnostics().get(0);
                    assertEquals(corpus.equals("invalid")?"P-NESTED-SHAPE":"P-COMMONFIELD",diagnostic.code());
                    int start=corpus.equals("invalid")?row.get("startCP").getAsInt():row.getAsJsonObject("error").getAsJsonArray("span").get(0).getAsInt();
                    int end=corpus.equals("invalid")?row.get("endCP").getAsInt():row.getAsJsonObject("error").getAsJsonArray("span").get(1).getAsInt();
                    assertEquals(source,start,diagnostic.span().start()); assertEquals(source,end,diagnostic.span().end());
                }
                var input=temporary.newFile().toPath();java.nio.file.Files.writeString(input,source);
                var nativeResult=run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"check","--target","rust","--grammar",input.toString(),"--format","json"),"",true);
                assertEquals(nativeResult.output(),expected?0:3,nativeResult.code());
                var report=com.google.gson.JsonParser.parseString(nativeResult.output()).getAsJsonObject();
                assertEquals(1,report.get("schemaVersion").getAsInt()); assertEquals("rust",report.get("target").getAsString());
                assertEquals(actual.portable(),report.get("portable").getAsBoolean());
                assertEquals(actual.structure(),report.get("structure").getAsString());
                var diagnostics=report.getAsJsonArray("diagnostics");assertEquals(actual.diagnostics().size(),diagnostics.size());
                for(int i=0;i<diagnostics.size();i++) {
                    var diagnostic=diagnostics.get(i).getAsJsonObject();var javaDiagnostic=actual.diagnostics().get(i);
                    assertEquals(javaDiagnostic.code(),diagnostic.get("code").getAsString());
                    assertEquals(javaDiagnostic.severity(),diagnostic.get("severity").getAsString());
                    assertEquals(javaDiagnostic.subject(),diagnostic.get("subject").getAsString());
                    assertEquals(javaDiagnostic.span().start(),diagnostic.getAsJsonObject("span").get("start").getAsInt());
                    assertEquals(javaDiagnostic.span().end(),diagnostic.getAsJsonObject("span").get("end").getAsInt());
                }
            }
        }
    }

}
