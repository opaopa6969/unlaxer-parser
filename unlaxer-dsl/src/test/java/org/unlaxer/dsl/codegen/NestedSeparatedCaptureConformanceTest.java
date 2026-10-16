package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.util.List;

/** Shared independently specified item/container values and original CP boundaries. */
public class NestedSeparatedCaptureConformanceTest extends TypedNestedCaptureConformanceTest {
    @Override protected boolean javaCliParity(){return true;}
    @Override protected String corpusPath() { return "spec-corpus/nested-separated-captures/cases.json"; }
    @Override protected String reportName() { return "nested-separated-captures.tsv"; }
    @Override protected java.nio.file.Path nativeTarget() { return java.nio.file.Path.of("target/nested-separated-native").toAbsolutePath(); }

    @Override protected void observeJava(com.google.gson.JsonObject fixture,com.google.gson.JsonObject row,
            org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar,java.net.URLClassLoader loader,Object ast) throws Exception {
        super.observeJava(fixture,row,grammar,loader,ast);
        var parser=(org.unlaxer.parser.Parser)loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Parsers").getMethod("getRootParser").invoke(null);
        var mapper=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Mapper");
        var probe=loader.loadClass("org.example.nestedcaptures.NestedProbe");
        var type=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"AST");
        String input=row.get("input").getAsString();
        for(var memo:org.unlaxer.context.Memoization.values())for(var diagnostics:org.unlaxer.context.ParseOptions.Diagnostics.values()) {
            try(var context=org.unlaxer.context.ParseContext.withOptions(org.unlaxer.StringSource.createRootSource(input),org.unlaxer.context.ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                var parsed=parser.parse(context);org.junit.Assert.assertTrue(parsed.isSucceeded());
                org.junit.Assert.assertEquals(input.codePointCount(0,input.length()),context.position());
                org.junit.Assert.assertEquals(context.position(),context.matchedPosition());
                var roots=new java.util.ArrayList<org.unlaxer.Token>();boundaryRoots(parsed.getRootToken(),roots);
                org.junit.Assert.assertEquals(1,roots.size());
                org.junit.Assert.assertEquals(row.get("boundary"),boundaryJson(org.unlaxer.capture.CaptureSnapshot.from(roots.get(0))));
                Object mapped=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,parsed.getRootToken());
                Object value=mapped.getClass().getMethod("ast").invoke(mapped);
                assertEquals(row.get("ast"),canonical(value,mapped));
                org.junit.Assert.assertEquals(row.get("structure").getAsString(),probe.getMethod("eval",type).invoke(probe.getConstructor().newInstance(),value));
                org.junit.Assert.assertEquals(1,context.getTokenStack().size());
            }
        }
    }
    @Override protected String extraRustSource(com.google.gson.JsonObject fixture) {
        return super.extraRustSource(fixture)+(fixture.has("generatedSeparator")?"""
            fn generated_token(c:&mut unlaxer_runtime::ParseContext<'_>)->unlaxer_runtime::ParseResult{
                c.declare_complete_token_trace();let at=c.position();
                let id=c.add_token_node(unlaxer_runtime::TOKEN_LEAF_RULE,unlaxer_runtime::token::TokenKind::VirtualConsumed,unlaxer_runtime::token::TokenSource::Generated{anchor:at,text:"bb".into()}).unwrap();
                Ok(unlaxer_runtime::ParseMatch{span:unlaxer_runtime::Span{start:at,end:at},nodes:vec![id.index()],captures:vec![]})
            }
            """:"");
    }
    @Override protected String extraRustBeforeDrop(com.google.gson.JsonObject fixture) {
        String snapshot=super.extraRustBeforeDrop(fixture);
        return snapshot+"for memo in [unlaxer_runtime::Memoization::Off,unlaxer_runtime::Memoization::SafeFailures]{for diagnostics in [unlaxer_runtime::Diagnostics::Auto,unlaxer_runtime::Diagnostics::Detailed,unlaxer_runtime::Diagnostics::DetailedOnFailure]{"
            +"let options=unlaxer_runtime::ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);let mut c=unlaxer_runtime::ParseContext::with_options(&input,options);let parsed=generated::parser::parse_context(&mut c).unwrap();assert_eq!((c.position(),c.matched_position()),(input.chars().count(),input.chars().count()));let tree=c.tree(parsed.nodes[0]).unwrap();let ast=generated::mapper::map(&tree).unwrap();"
            +snapshot+super.extraRustObservation(fixture)+"}}";
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
