package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import java.net.URLClassLoader;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;

/** Independent typed consumer: optional depth must survive even where JSON null is ambiguous. */
public class TypedNestedCaptureConformanceTest extends NestedCaptureConformanceTest {
    @Override protected String corpusPath() { return "spec-corpus/typed-nested-captures/cases.json"; }
    @Override protected String reportName() { return "typed-nested-captures.tsv"; }
    @Override protected java.nio.file.Path nativeTarget() { return java.nio.file.Path.of("target/typed-nested-native").toAbsolutePath(); }
    @Override protected String javaRelease() { return "21"; }
    @Override protected List<GeneratedSource> extraJavaSources(GrammarDecl grammar) {
        String name=grammar.name();
        String probe="""
            package org.example.nestedcaptures;
            public class NestedProbe extends __NAME__Evaluator<String> {
                protected String evalRoot(__NAME__AST.Root root) { return structure(root.value()); }
                private static String structure(Object value) {
                    if(value instanceof java.util.Optional<?> optional) return optional.isEmpty()?"none":"some("+structure(optional.get())+")";
                    if(value instanceof java.util.List<?> values) return "list["+values.stream().map(NestedProbe::structure).collect(java.util.stream.Collectors.joining(","))+"]";
                    if(value instanceof Integer number) return "int("+number+")";
                    return "text("+java.util.HexFormat.of().formatHex(((String)value).getBytes(java.nio.charset.StandardCharsets.UTF_8))+")";
                }
            }
            """.replace("__NAME__",name);
        return List.of(new EvaluatorGenerator(21).generate(grammar), new GeneratedSource("org.example.nestedcaptures","NestedProbe",probe));
    }
    @Override protected void observeJava(JsonObject fixture,JsonObject row,GrammarDecl grammar,URLClassLoader loader,Object ast) throws Exception {
        var probe=loader.loadClass("org.example.nestedcaptures.NestedProbe");
        var astType=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"AST");
        assertEquals(row.get("structure").getAsString(),probe.getMethod("eval",astType).invoke(probe.getConstructor().newInstance(),ast));
        var parser=(org.unlaxer.parser.Parser)loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Parsers").getMethod("getRootParser").invoke(null);
        try(var context=new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(row.get("input").getAsString()))) {
            var parsed=parser.parse(context);assertTrue(parsed.isSucceeded());
            var roots=new java.util.ArrayList<org.unlaxer.Token>();boundaryRoots(parsed.getRootToken(),roots);
            assertEquals(1,roots.size());
            assertEquals(row.get("boundary"),boundaryJson(org.unlaxer.capture.CaptureSnapshot.from(roots.get(0))));
        }
        var source=org.unlaxer.dsl.bootstrap.UBNFMapper.parseWithSource(fixture.get("grammar").getAsString());
        var javaSchema=org.unlaxer.dsl.impact.JavaApiSchema.build(source);
        var rustSchema=org.unlaxer.dsl.codegen.rust.RustApiSchema.build(source);
        var javaField=javaSchema.nodes().stream().filter(n->n.name().equals("Root")).findFirst().orElseThrow().fields().get(0);
        var rustField=rustSchema.nodes().stream().filter(n->n.name().equals("Root")).findFirst().orElseThrow().fields().get(0);
        assertEquals(fixture.getAsJsonObject("javaFieldTypes").getAsJsonObject("Root").get("value").getAsString(),javaField.type());
        assertEquals(fixture.getAsJsonObject("rustFieldTypes").getAsJsonObject("Root").get("value").getAsString(),rustField.type());
        assertEquals(javaField.cardinality(),rustField.cardinality());
    }
    private static void boundaryRoots(org.unlaxer.Token token,java.util.List<org.unlaxer.Token> out) {
        if(token.parser instanceof org.unlaxer.parser.combinator.CaptureBoundaryParser)out.add(token);
        else for(var child:token.getOriginalChildren())boundaryRoots(child,out);
    }
    private static com.google.gson.JsonObject boundaryJson(org.unlaxer.capture.CaptureSnapshot.Element value) {
        var result=new com.google.gson.JsonObject();result.addProperty("kind",value.kind().name().toLowerCase(java.util.Locale.ROOT));
        var span=new com.google.gson.JsonArray();span.add(value.startCP());span.add(value.endCP());result.add("span",span);result.addProperty("text",value.text());
        var children=new com.google.gson.JsonArray();for(var child:value.children())children.add(boundaryJson(child));result.add("children",children);return result;
    }
    @Override protected String extraRustBeforeDrop(JsonObject fixture) {
        var expected=new StringBuilder("match input.as_str() {");
        var gson=new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        for(var entry:fixture.getAsJsonArray("cases")){var row=entry.getAsJsonObject();expected.append(gson.toJson(row.get("input").getAsString())).append("=>").append(gson.toJson(gson.toJson(row.get("boundary")))).append(',');}
        expected.append("_=>panic!(\"missing independent input\")} ");
        return "let capture=tree.nodes.iter().flat_map(|n|n.captures.iter()).find(|c|c.name==\"value\").unwrap(); let snapshot=unlaxer_runtime::capture_tree::snapshot(&tree,capture.nodes[0]).unwrap(); assert_eq!(boundary_json(&snapshot),"+expected+");";
    }
    @Override protected String extraRustSource(JsonObject fixture) {
        String type=fixture.getAsJsonObject("rustFieldTypes").getAsJsonObject("Root").get("value").getAsString();
        return """
            fn boundary_json(value:&unlaxer_runtime::capture_tree::Element)->String {
                let kind=match value.kind(){unlaxer_runtime::capture_tree::Kind::Value=>"value",unlaxer_runtime::capture_tree::Kind::Optional=>"optional",unlaxer_runtime::capture_tree::Kind::List=>"list"};
                format!(r#"{{"kind":{},"span":[{},{}],"text":{},"children":[{}]}}"#,unlaxer_runtime::json_string(kind),value.span().start,value.span().end,unlaxer_runtime::json_string(value.text()),value.children().iter().map(boundary_json).collect::<Vec<_>>().join(","))
            }
            trait Structure { fn structure(&self) -> String; }
            impl Structure for String { fn structure(&self)->String { use std::fmt::Write; let mut hex=String::new();for byte in self.bytes(){write!(hex,"{byte:02x}").unwrap();}format!("text({hex})") } }
            impl Structure for i32 { fn structure(&self)->String {format!("int({self})")} }
            impl<T:Structure> Structure for Option<T> {fn structure(&self)->String {self.as_ref().map_or_else(||"none".into(),|value|format!("some({})",value.structure()))} }
            impl<T:Structure> Structure for Vec<T> {fn structure(&self)->String {format!("list[{}]",self.iter().map(Structure::structure).collect::<Vec<_>>().join(","))} }
            struct NestedSemantics;
            impl generated::evaluator::Semantics for NestedSemantics {
                type Output=String;
                fn eval_root(&mut self,value:&__TYPE__,_span:unlaxer_runtime::Span)->String {value.structure()}
            }
            """.replace("__TYPE__",type);
    }
    @Override protected String extraRustObservation(JsonObject fixture) {
        var cases=fixture.getAsJsonArray("cases");
        var expected=new StringBuilder("match input.as_str() {");
        for(var value:cases){var row=value.getAsJsonObject();expected.append(new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(row.get("input").getAsString())).append(" => ").append(new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(row.get("structure").getAsString())).append(',');}
        expected.append("_=>panic!(\"missing independent input\") }");
        return "assert_eq!(generated::evaluator::evaluate(&ast,&mut NestedSemantics),"+expected+");";
    }

    @org.junit.Test public void mappingFailuresAndParseRollbackKeepLaterConsumersUsable() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var fixture=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/typed-nested-captures/failures.json"))).getAsJsonObject();
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
            var parsers=loader.loadClass("org.example.nestedcaptures.NestedFailuresParsers");
            var parser=(org.unlaxer.parser.Parser)parsers.getMethod("getRootParser").invoke(null);
            var mapper=loader.loadClass("org.example.nestedcaptures.NestedFailuresMapper");
            var astType=loader.loadClass("org.example.nestedcaptures.NestedFailuresAST");
            var evaluator=loader.loadClass("org.example.nestedcaptures.NestedProbe");
            for(var entry:fixture.getAsJsonArray("cases")) {
                var row=entry.getAsJsonObject();String input=row.get("input").getAsString();
                rust.append("{let input=").append(row.get("input")).append(';');
                if(row.get("parse").getAsBoolean()) {
                    String span=row.getAsJsonArray("errorSpan").get(0).getAsInt()+".."+row.getAsJsonArray("errorSpan").get(1).getAsInt();
                    try(var context=new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                        assertTrue(row.get("id").getAsString(),parser.parse(context).isSucceeded());
                        assertEquals(input.codePointCount(0,input.length()),context.getConsumedPosition().value());
                    }
                    var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->mapper.getMethod("parse",String.class).invoke(null,input));
                    assertTrue(error.getCause().toString(),error.getCause().getMessage().contains("invalid i32 nested capture at "+span));
                    rust.append("let tree=generated::parser::parse_tree(input).unwrap(); let error=generated::mapper::map(&tree).unwrap_err(); assert!(error.contains(\"invalid i32 nested capture at ").append(span).append("\"),\"{error}\");");
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
                rust.append("let tree=generated::parser::parse_tree(").append(fixture.get("validInput")).append(").unwrap(); let ast=generated::mapper::map(&tree).unwrap(); assert!(ast.canonical_json().contains(\"\\\"value\\\":7\"));}\n");
            }
        }
        rust.append('}');java.nio.file.Files.writeString(directory.resolve("main.rs"),rust);
        success(run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,directory.resolve("main.rs").toString(),"-o",directory.resolve("probe").toString()),"",false));
        success(run(List.of(directory.resolve("probe").toString()),"",true));
    }

    @org.junit.Test public void apiImpactReportsActualRecursiveTypesOnBothHosts() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var corpus=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve(corpusPath()))).getAsJsonArray();
        String before=corpus.get(0).getAsJsonObject().get("grammar").getAsString();
        String after=before.replace("[ {'a'} ]","{ {'a'} }");
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

    @org.junit.Test public void preparatoryDepthCountsLogicalChoiceBodiesOnBothHosts() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var corpus=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/typed-nested-captures/depth-boundaries.json"))).getAsJsonArray();
        for(var entry:corpus) {
            var row=entry.getAsJsonObject();String source=row.get("grammar").getAsString();
            var snapshot=org.unlaxer.dsl.bootstrap.UBNFMapper.parseWithSource(source);
            var grammar=snapshot.ast().grammars().get(0);
            var directory=temporary.newFolder().toPath();var input=directory.resolve("fixture.ubnf");var output=directory.resolve("generated");java.nio.file.Files.writeString(input,source);
            var nativeResult=run(List.of(nativeTarget().resolve("debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",output.toString()),"",true);
            boolean nativeAccepted=nativeResult.code()==0;
            var issues=NestedCaptures.problems(grammar);
            assertEquals("native depth "+row.get("depth")+": "+nativeResult.output(),row.get("accepted").getAsBoolean(),nativeAccepted);
            assertEquals("Java depth "+row.get("depth")+": "+issues,row.get("accepted").getAsBoolean(),issues.isEmpty());
            if(nativeAccepted) {
                for(var file:new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar))assertEquals(file.content(),java.nio.file.Files.readString(output.resolve(file.relativePath())));
                for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator()))assertFalse(generator.generate(grammar).source().isEmpty());
            } else {
                var diagnostic=GrammarValidator.validateWithoutClassLoading(grammar).stream().filter(d->d.code().equals("E-NESTED-SHAPE")).findFirst().orElseThrow();
                assertEquals("Root",diagnostic.rule());
                var rule=grammar.rules().stream().filter(r->r.name().equals(diagnostic.rule())).findFirst().orElseThrow();
                var position=snapshot.spanOf(rule).orElseThrow();
                assertEquals(row.get("startCP").getAsInt(),position.start());
                assertEquals(row.get("endCP").getAsInt(),position.end());
                for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator())) {
                    assertTrue(assertThrows(IllegalArgumentException.class,()->generator.generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
                }
                assertTrue(assertThrows(IllegalArgumentException.class,()->new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
                assertTrue(nativeResult.output(),nativeResult.output().contains("E-NESTED-SHAPE"));
                assertTrue(nativeResult.output(),nativeResult.output().contains("at CP "+row.get("startCP")+".."+row.get("endCP")));
                assertFalse(java.nio.file.Files.exists(output));
            }
        }
    }

    @org.junit.Test public void unsupportedShapesFailBeforeEmissionWithSourceOrigins() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var corpus=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/typed-nested-captures/invalid.json"))).getAsJsonArray();
        for(var entry:corpus) {
            var row=entry.getAsJsonObject();String text=row.get("grammar").getAsString();
            org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot source;
            try { source=org.unlaxer.dsl.bootstrap.UBNFMapper.parseWithSource(text); } catch(IllegalArgumentException error) { throw new AssertionError(row.get("id").getAsString()+": "+error.getMessage(),error); }
            var grammar=source.ast().grammars().get(0);
            var diagnostic=GrammarValidator.validateWithoutClassLoading(grammar).stream().filter(d->d.code().equals("E-NESTED-SHAPE")).findFirst().orElseThrow();
            assertEquals("Root",diagnostic.rule());
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
}
