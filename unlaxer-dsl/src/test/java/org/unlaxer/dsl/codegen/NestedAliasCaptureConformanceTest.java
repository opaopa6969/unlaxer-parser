package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import java.net.URLClassLoader;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;

/**
 * Nested captures through non-recursive transparent rule aliases (#583).
 *
 * <p>The values, CP spans, preferred candidates and leaves are written by hand; fixtures marked
 * {@code mirrors} reuse the independent oracle of the equivalent in-place form, because an alias
 * must be observationally identical to writing its body at the reference.
 */
public class NestedAliasCaptureConformanceTest extends NestedMappedCaptureConformanceTest {
    @Override protected String corpusPath() { return "spec-corpus/nested-alias-captures/cases.json"; }
    @Override protected String reportName() { return "nested-alias-captures.tsv"; }
    @Override protected String invalidPath() { return "spec-corpus/nested-alias-captures/invalid.json"; }
    @Override protected String failuresPath() { return "spec-corpus/nested-alias-captures/failures.json"; }
    @Override protected java.nio.file.Path nativeTarget() { return java.nio.file.Path.of("target/nested-alias-native").toAbsolutePath(); }
    @Override protected boolean javaCliParity() { return true; }

    /** Every memoization / diagnostics setting maps the same value from a fresh parse of the generated parser. */
    @Override protected void observeJavaMapped(JsonObject fixture, JsonObject row, GrammarDecl grammar, URLClassLoader loader, Object ast, Object mapped) throws Exception {
        super.observeJavaMapped(fixture, row, grammar, loader, ast, mapped);
        var parser=(org.unlaxer.parser.Parser)loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Parsers").getMethod("getRootParser").invoke(null);
        var mapper=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Mapper");
        var probe=loader.loadClass("org.example.nestedcaptures.NestedMappedProbe");
        var type=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"AST");
        String input=row.get("input").getAsString();
        for(var memo:org.unlaxer.context.Memoization.values())for(var diagnostics:org.unlaxer.context.ParseOptions.Diagnostics.values()) {
            try(var context=org.unlaxer.context.ParseContext.withOptions(org.unlaxer.StringSource.createRootSource(input),org.unlaxer.context.ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                var parsed=parser.parse(context);
                assertTrue(grammar.name()+" "+memo+" "+diagnostics,parsed.isSucceeded());
                assertEquals(input.codePointCount(0,input.length()),context.position());
                Object view=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,parsed.getRootToken());
                Object value=view.getClass().getMethod("ast").invoke(view);
                assertEquals(row.get("ast"),canonical(value,view));
                assertEquals(row.get("structure").getAsString(),probe.getMethod("eval",type).invoke(probe.getConstructor().newInstance(),value));
            }
        }
    }
    @Override protected String extraRustObservation(JsonObject fixture) {
        String once=super.extraRustObservation(fixture);
        return once+"for memo in [unlaxer_runtime::Memoization::Off,unlaxer_runtime::Memoization::SafeFailures]{for diagnostics in [unlaxer_runtime::Diagnostics::Auto,unlaxer_runtime::Diagnostics::Detailed,unlaxer_runtime::Diagnostics::DetailedOnFailure]{"
            +"let options=unlaxer_runtime::ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);let mut c=unlaxer_runtime::ParseContext::with_options(&input,options);let parsed=generated::parser::parse_context(&mut c).unwrap();"
            +"assert_eq!((c.position(),c.matched_position()),(input.chars().count(),input.chars().count()));let tree=c.tree(parsed.nodes[0]).unwrap();let ast=generated::mapper::map(&tree).unwrap();"
            +once+"}}";
    }

    /** Changing only the alias body changes the owner's public field type on both hosts. */
    @Override @org.junit.Test public void apiImpactReportsActualRecursiveTypesOnBothHosts() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        String before="grammar Impact { @package: org.example.nestedcaptures @root @nestedCaptures @mapping(Root,params=[value]) Root ::= Items @value; Items ::= [ Many ]; Many ::= {'a'}; }";
        String after=before.replace("Items ::= [ Many ];","Items ::= { Many };");
        for(String target:List.of("java","rust")) {
            var impact=org.unlaxer.dsl.impact.ApiImpact.compare(target,before,after);
            assertTrue(impact.toJson(),impact.ok());
            assertTrue(impact.toJson(),impact.changes().stream().anyMatch(c->c.kind().equals("FIELD_TYPE_CHANGED")&&c.subject().equals("Root.value")));
            assertTrue(impact.toJson(),impact.changes().stream().anyMatch(c->c.kind().equals("FIELD_CARDINALITY_CHANGED")&&c.subject().equals("Root.value")));
        }
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var directory=temporary.newFolder().toPath();java.nio.file.Files.writeString(directory.resolve("before.ubnf"),before);java.nio.file.Files.writeString(directory.resolve("after.ubnf"),after);
        var result=run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"impact","--target","rust","--before",directory.resolve("before.ubnf").toString(),"--after",directory.resolve("after.ubnf").toString()),"",true);
        success(result);
        assertEquals(com.google.gson.JsonParser.parseString(org.unlaxer.dsl.impact.ApiImpact.compare("rust",before,after).toJson()),com.google.gson.JsonParser.parseString(result.output()));
    }

    /** Alias expansion counts toward the same schema depth limit (32) on both hosts. */
    @org.junit.Test public void aliasExpansionSharesTheSchemaDepthLimit() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var corpus=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/nested-alias-captures/depth-boundaries.json"))).getAsJsonArray();
        boolean sawAccepted=false,sawRejected=false;
        for(var entry:corpus) {
            var row=entry.getAsJsonObject();String source=row.get("grammar").getAsString();
            var snapshot=org.unlaxer.dsl.bootstrap.UBNFMapper.parseWithSource(source);
            var grammar=snapshot.ast().grammars().get(0);
            var directory=temporary.newFolder().toPath();var input=directory.resolve("fixture.ubnf");var output=directory.resolve("generated");java.nio.file.Files.writeString(input,source);
            var nativeResult=run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",output.toString()),"",true);
            boolean accepted=row.get("accepted").getAsBoolean();
            assertEquals("native depth "+row.get("depth")+": "+nativeResult.output(),accepted,nativeResult.code()==0);
            assertEquals("Java depth "+row.get("depth"),accepted,NestedCaptures.problems(grammar).isEmpty());
            if(accepted) {
                sawAccepted=true;
                String type="String";for(int i=0;i<row.get("optionals").getAsInt();i++)type="Optional<"+type+">";
                assertEquals(type,NestedCaptures.javaType(NestedCaptures.field(grammar,grammar.rules().get(0),"value")));
                for(var file:new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar))assertEquals(file.content(),java.nio.file.Files.readString(output.resolve(file.relativePath())));
                for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator()))assertFalse(generator.generate(grammar).source().isEmpty());
            } else {
                sawRejected=true;
                var diagnostic=GrammarValidator.validateWithoutClassLoading(grammar).stream().filter(d->d.code().equals("E-NESTED-SHAPE")).findFirst().orElseThrow();
                assertEquals("Root",diagnostic.rule());
                assertTrue(diagnostic.message(),diagnostic.message().contains("schema depth exceeds 32"));
                var position=snapshot.spanOf(grammar.rules().get(0)).orElseThrow();
                assertEquals(row.get("startCP").getAsInt(),position.start());
                assertEquals(row.get("endCP").getAsInt(),position.end());
                for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator()))
                    assertTrue(assertThrows(IllegalArgumentException.class,()->generator.generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
                assertTrue(assertThrows(IllegalArgumentException.class,()->new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
                assertTrue(nativeResult.output(),nativeResult.output().contains("schema depth exceeds 32 at CP "+row.get("startCP")+".."+row.get("endCP")));
                assertFalse(java.nio.file.Files.exists(output));
            }
        }
        assertTrue(sawAccepted && sawRejected);
    }
}
