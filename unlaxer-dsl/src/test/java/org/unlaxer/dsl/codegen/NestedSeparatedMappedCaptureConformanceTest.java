package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import java.net.URLClassLoader;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;

/** Node/enum source identity, separator isolation, preferred snapshots and shared nominal types. */
public class NestedSeparatedMappedCaptureConformanceTest extends NestedMappedCaptureConformanceTest {
    @Override protected boolean javaCliParity(){return true;}
    @Override protected String corpusPath(){return "spec-corpus/nested-separated-captures/mapped.json";}
    @Override protected String reportName(){return "nested-separated-mapped.tsv";}
    @Override protected java.nio.file.Path nativeTarget(){return java.nio.file.Path.of("target/nested-separated-native").toAbsolutePath();}
    @Override protected void observeJavaMapped(JsonObject fixture,JsonObject row,GrammarDecl grammar,URLClassLoader loader,Object ast,Object mapped)throws Exception {
        super.observeJavaMapped(fixture,row,grammar,loader,ast,mapped);
        if(fixture.has("distinctItems")){
            var values=(List<?>)ast.getClass().getMethod("value").invoke(ast);
            assertNotSame("same-CP repeated nodes must remain separate original occurrences",values.get(0),values.get(1));
        }
        String input=row.get("input").getAsString();
        var parser=(org.unlaxer.parser.Parser)loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Parsers").getMethod("getRootParser").invoke(null);
        var mapper=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Mapper");
        var evaluator=loader.loadClass("org.example.nestedcaptures.NestedMappedProbe");
        var type=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"AST");
        for(var memo:org.unlaxer.context.Memoization.values())for(var diagnostics:org.unlaxer.context.ParseOptions.Diagnostics.values()) {
            try(var context=org.unlaxer.context.ParseContext.withOptions(org.unlaxer.StringSource.createRootSource(input),org.unlaxer.context.ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                var parsed=parser.parse(context);assertTrue(parsed.isSucceeded());assertEquals(input.codePointCount(0,input.length()),context.position());assertEquals(context.position(),context.matchedPosition());
                Object snapshot=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,parsed.getRootToken());
                Object value=snapshot.getClass().getMethod("ast").invoke(snapshot);
                assertEquals(row.get("ast"),canonical(value,snapshot));
                assertEquals(row.get("structure").getAsString(),evaluator.getMethod("eval",type).invoke(evaluator.getConstructor().newInstance(),value));
                assertEquals(1,context.getTokenStack().size());
            }
        }
    }
    @Override protected String extraRustBeforeDrop(JsonObject fixture){
        String leaves=super.extraRustBeforeDrop(fixture);
        var expected=new StringBuilder("match input.as_str(){");
        for(var value:fixture.getAsJsonArray("cases")){var row=value.getAsJsonObject();expected.append(row.get("input")).append("=>").append(new com.google.gson.Gson().toJson(row.get("ast").toString())).append(',');}
        expected.append("_=>panic!(\"missing oracle input\")}");
        return leaves+"for memo in [unlaxer_runtime::Memoization::Off,unlaxer_runtime::Memoization::SafeFailures]{for diagnostics in [unlaxer_runtime::Diagnostics::Auto,unlaxer_runtime::Diagnostics::Detailed,unlaxer_runtime::Diagnostics::DetailedOnFailure]{let options=unlaxer_runtime::ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);let mut c=unlaxer_runtime::ParseContext::with_options(&input,options);let parsed=generated::parser::parse_context(&mut c).unwrap();assert_eq!((c.position(),c.matched_position()),(input.chars().count(),input.chars().count()));let tree=c.tree(parsed.nodes[0]).unwrap();let ast=generated::mapper::map(&tree).unwrap();assert_eq!(ast.canonical_json(),"+expected+");"+leaves+super.extraRustObservation(fixture)+"}}";
    }
    @Override protected String extraRustObservation(JsonObject fixture){
        return super.extraRustObservation(fixture)+(fixture.has("distinctItems")?"if let generated::ast::Ast::Root{value,..}=&ast{assert!(!std::ptr::eq(value[0].as_ref(),value[1].as_ref()));}":"");
    }
    @Override @org.junit.Test public void apiImpactReportsActualRecursiveTypesOnBothHosts()throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        String before="grammar Impact { @package: org.example.nestedcaptures @root @nestedCaptures @mapping(Root,params=[value]) Root ::= ['a'] % ',' @value; }";
        String after=before.replace("['a'] %", "'a' %");
        for(String target:List.of("java","rust")){
            var impact=org.unlaxer.dsl.impact.ApiImpact.compare(target,before,after);
            assertTrue(impact.toJson(),impact.ok());
            assertTrue(impact.changes().stream().anyMatch(c->c.kind().equals("FIELD_TYPE_CHANGED")&&c.subject().equals("Root.value")));
            assertFalse("outer List cardinality is unchanged",impact.changes().stream().anyMatch(c->c.kind().equals("FIELD_CARDINALITY_CHANGED")));
        }
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var directory=temporary.newFolder().toPath();java.nio.file.Files.writeString(directory.resolve("before.ubnf"),before);java.nio.file.Files.writeString(directory.resolve("after.ubnf"),after);
        var result=run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"impact","--target","rust","--before",directory.resolve("before.ubnf").toString(),"--after",directory.resolve("after.ubnf").toString()),"",true);success(result);
        assertEquals(com.google.gson.JsonParser.parseString(org.unlaxer.dsl.impact.ApiImpact.compare("rust",before,after).toJson()),com.google.gson.JsonParser.parseString(result.output()));
    }
}
