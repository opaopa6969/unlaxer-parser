package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;

/** Mapped leaves are read from their original CST and retained as typed owned values. */
public class NestedMappedCaptureConformanceTest extends NestedCaptureConformanceTest {
    @Override protected String corpusPath() { return "spec-corpus/nested-mapped-leaves/cases.json"; }
    @Override protected String reportName() { return "nested-mapped-leaves.tsv"; }
    @Override protected String javaRelease() { return "21"; }
    @Override protected java.nio.file.Path nativeTarget() { return java.nio.file.Path.of("target/nested-mapped-native").toAbsolutePath(); }
    @Override protected List<GeneratedSource> extraJavaSources(GrammarDecl grammar) {
        var evaluator=new EvaluatorGenerator(21).generate(grammar);
        boolean rootValue=!grammar.name().equals("SamePositionNodes");
        var methods=java.util.regex.Pattern.compile("protected abstract T (\\w+)\\(([^)]*)\\);").matcher(evaluator.source());
        var body=new StringBuilder("package org.example.nestedcaptures; public class NestedMappedProbe extends "+grammar.name()+"Evaluator<String> {");
        while(methods.find()) {
            String parameters=methods.group(2),name=parameters.substring(parameters.lastIndexOf(' ')+1);
            body.append("protected String ").append(methods.group(1)).append('(').append(parameters).append("){return structure(").append(name).append(methods.group(1).equals("evalRoot")&&rootValue?".value()":"").append(");}");
        }
        body.append("""
            private static String structure(Object value) {
                if(value instanceof java.util.Optional<?> optional) return optional.isEmpty()?"none":"some("+structure(optional.get())+")";
                if(value instanceof java.util.List<?> values) return "list["+values.stream().map(NestedMappedProbe::structure).collect(java.util.stream.Collectors.joining(","))+"]";
                if(value instanceof Enum<?> item) return "enum("+item.getDeclaringClass().getSimpleName()+","+item.name()+")";
                if(value instanceof Integer number) return "int("+number+")";
                if(value instanceof String text) return "text("+java.util.HexFormat.of().formatHex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))+")";
                var fields=new java.util.ArrayList<String>();
                try { for(var field:value.getClass().getRecordComponents()) fields.add(structure(field.getAccessor().invoke(value))); }
                catch(ReflectiveOperationException error) {throw new AssertionError(error);}
                Class<?> type=value.getClass();
                String name=type.getEnclosingClass().getSimpleName().endsWith("AST")?type.getSimpleName():type.getEnclosingClass().getSimpleName()+"__"+type.getSimpleName();
                return "node("+name+","+String.join(",",fields)+")";
            }
        }
        """);
        return List.of(evaluator,new GeneratedSource("org.example.nestedcaptures","NestedMappedProbe",body.toString()));
    }
    @Override protected void observeJavaMapped(JsonObject fixture,JsonObject row,GrammarDecl grammar,URLClassLoader loader,Object ast,Object mapped) throws Exception {
        var source=org.unlaxer.dsl.bootstrap.UBNFMapper.parseWithSource(fixture.get("grammar").getAsString());
        var javaSchema=org.unlaxer.dsl.impact.JavaApiSchema.build(source);
        var rustSchema=org.unlaxer.dsl.codegen.rust.RustApiSchema.build(source);
        for(String name:fixture.getAsJsonObject("javaFieldTypes").getAsJsonObject("Root").keySet()) {
            var javaField=javaSchema.nodes().stream().filter(n->n.name().equals("Root")).findFirst().orElseThrow().fields().stream().filter(f->f.name().equals(name)).findFirst().orElseThrow();
            var rustField=rustSchema.nodes().stream().filter(n->n.name().equals("Root")).findFirst().orElseThrow().fields().stream().filter(f->f.name().equals(name)).findFirst().orElseThrow();
            assertEquals(fixture.getAsJsonObject("javaFieldTypes").getAsJsonObject("Root").get(name).getAsString(),javaField.type().replace(grammar.name()+"AST.",""));
            assertEquals(fixture.getAsJsonObject("rustFieldTypes").getAsJsonObject("Root").get(name).getAsString(),rustField.type());
            assertEquals(javaField.cardinality(),rustField.cardinality());
        }
        var evaluator=loader.loadClass("org.example.nestedcaptures.NestedMappedProbe");
        var astType=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"AST");
        Object evaluation=evaluator.getMethod("eval",astType).invoke(evaluator.getConstructor().newInstance(),ast);
        assertEquals(row.get("structure").getAsString(),evaluation);
        if(fixture.has("distinctFields")) {
            var fields=fixture.getAsJsonArray("distinctFields");
            Object first=((java.util.Optional<?>)ast.getClass().getMethod(fields.get(0).getAsString()).invoke(ast)).orElseThrow();
            Object second=((java.util.Optional<?>)ast.getClass().getMethod(fields.get(1).getAsString()).invoke(ast)).orElseThrow();
            assertNotSame("equal CP ranges must not merge separate CST leaves",first,second);
        }
        if(fixture.has("commonField")) {
            var values=(List<?>)((java.util.Optional<?>)ast.getClass().getMethod("value").invoke(ast)).orElseThrow();
            var family=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"AST$Node");
            assertEquals(List.of("a","😀"),values.stream().map(v->{try{return family.getMethod("text").invoke(v);}catch(Exception e){throw new AssertionError(e);}}).toList());
        }
        // Retained SourceMappedAst must outlive both the parse context and a later mapping call.
        var mapper=loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Mapper");
        mapper.getMethod("parse",String.class).invoke(null,fixture.getAsJsonArray("cases").get(0).getAsJsonObject().get("input").getAsString());
        assertEquals(evaluation,evaluator.getMethod("eval",astType).invoke(evaluator.getConstructor().newInstance(),ast));
        var parser=(org.unlaxer.parser.Parser)loader.loadClass("org.example.nestedcaptures."+grammar.name()+"Parsers").getMethod("getRootParser").invoke(null);
        try(var context=new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(row.get("input").getAsString()))) {
            assertTrue(parser.parse(context).isSucceeded());
            var token=context.getCurrent().getTokens().stream().filter(t->t.parser==parser).findFirst().orElseThrow();
            var leaves=new com.google.gson.JsonArray();
            collectTokenLeaves(token,leaves);
            assertEquals(grammar.name()+" original nested leaves",row.get("leaves"),leaves);
            Object snapshot=mapper.getMethod("mapParsedTree",org.unlaxer.Token.class).invoke(null,token);
            var actual=(List<?>)snapshot.getClass().getMethod("candidates").invoke(snapshot);
            var expected=row.getAsJsonArray("preferred");assertEquals(expected.size(),actual.size());
            for(int i=0;i<actual.size();i++) {
                var candidate=actual.get(i);var type=candidate.getClass();var want=expected.get(i).getAsJsonArray();
                assertEquals(want.get(0).getAsString(),type.getMethod("simpleName").invoke(candidate));
                assertEquals(grammar.name(),want.get(1).getAsInt(),type.getMethod("depth").invoke(candidate));
                assertEquals(want.get(2).getAsInt(),type.getMethod("startOffset").invoke(candidate));
                Object candidateMap=type.getMethod("sourceMap").invoke(candidate),candidateAst=candidateMap.getClass().getMethod("ast").invoke(candidateMap);
                assertArrayEquals(new int[]{want.get(2).getAsInt(),want.get(3).getAsInt()},(int[])((java.util.Optional<?>)candidateMap.getClass().getMethod("sourceSpanOf",Object.class).invoke(candidateMap,candidateAst)).orElseThrow());
            }
        }
    }
    private static void collectTokenLeaves(org.unlaxer.Token token,com.google.gson.JsonArray output) {
        if(token.parser instanceof org.unlaxer.parser.combinator.CaptureBoundaryParser) collectLeaves(org.unlaxer.capture.CaptureSnapshot.from(token),output);
        else for(var child:token.getOriginalChildren())collectTokenLeaves(child,output);
    }
    private static void collectLeaves(org.unlaxer.capture.CaptureSnapshot.Element item,com.google.gson.JsonArray output) {
        if(item.kind()==org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.VALUE) {
            var row=new com.google.gson.JsonObject();var span=new com.google.gson.JsonArray();span.add(item.startCP());span.add(item.endCP());row.add("span",span);row.addProperty("text",item.text());output.add(row);
        } else for(var child:item.children())collectLeaves(child,output);
    }
    @Override protected String extraRustBeforeDrop(JsonObject fixture) {
        var expected=new StringBuilder("match input.as_str(){");
        for(var entry:fixture.getAsJsonArray("cases")) {
            var row=entry.getAsJsonObject();expected.append(row.get("input")).append("=>vec![");
            for(var leaf:row.getAsJsonArray("leaves")){var c=leaf.getAsJsonObject();var span=c.getAsJsonArray("span");expected.append('(').append(span.get(0)).append(',').append(span.get(1)).append(',').append(c.get("text")).append(".to_string()),");}
            expected.append("],");
        }
        expected.append("_=>panic!(\"missing oracle input\")}");
        return "let mut leaves=Vec::new();collect_tree_leaves(&tree,tree.root,&mut leaves);assert_eq!(leaves,"+expected+");";
    }
    @Override protected String canonicalType(Object value) {
        Class<?> type=value.getClass();
        return type.getEnclosingClass().getSimpleName().endsWith("AST")?type.getSimpleName():type.getEnclosingClass().getSimpleName()+"__"+type.getSimpleName();
    }
    private static String borrowed(String type,boolean nested) {
        if(nested)return "&"+type;
        if(type.equals("i32"))return "&i32";
        if(type.equals("String"))return "&str";
        if(type.equals("Option<String>"))return "Option<&str>";
        if(type.equals("Option<Box<Ast>>"))return "Option<&Ast>";
        throw new AssertionError("add explicit independent borrowed type: "+type);
    }
    @Override protected String extraRustSource(JsonObject fixture) {
        var out=new StringBuilder("""
            use generated::ast::*;
            fn collect_tree_leaves(tree:&Tree,id:usize,out:&mut Vec<(usize,usize,String)>) {
                if unlaxer_runtime::capture_tree::Kind::of_rule(tree.nodes[id].rule).is_some(){collect_leaves(&unlaxer_runtime::capture_tree::snapshot(tree,id).unwrap(),out);}
                else{for child in &tree.nodes[id].children{collect_tree_leaves(tree,*child,out);}}
            }
            fn collect_leaves(item:&unlaxer_runtime::capture_tree::Element,out:&mut Vec<(usize,usize,String)>) {
                if item.kind()==unlaxer_runtime::capture_tree::Kind::Value {out.push((item.span().start,item.span().end,item.text().to_owned()));}
                else{for child in item.children(){collect_leaves(child,out);}}
            }
            trait Structure {fn structure(&self)->String;}
            impl Structure for str {fn structure(&self)->String{use std::fmt::Write;let mut hex=String::new();for byte in self.bytes(){write!(hex,"{byte:02x}").unwrap();}format!("text({hex})")}}
            impl Structure for i32 {fn structure(&self)->String{format!("int({self})")}}
            impl Structure for String {fn structure(&self)->String{self.as_str().structure()}}
            impl<T:Structure+?Sized> Structure for &T {fn structure(&self)->String{(**self).structure()}}
            impl<T:Structure> Structure for Box<T> {fn structure(&self)->String{(**self).structure()}}
            impl<T:Structure> Structure for Option<T>{fn structure(&self)->String{self.as_ref().map_or_else(||"none".into(),|v|format!("some({})",v.structure()))}}
            impl<T:Structure> Structure for Vec<T>{fn structure(&self)->String{format!("list[{}]",self.iter().map(Structure::structure).collect::<Vec<_>>().join(","))}}
            impl Structure for generated::ast::Ast {fn structure(&self)->String{match self {
            """);
        var types=fixture.getAsJsonObject("rustFieldTypes");
        for(var variant:types.entrySet()) {
            String names=String.join(",",variant.getValue().getAsJsonObject().keySet());
            String values=variant.getValue().getAsJsonObject().keySet().stream().map(n->n+".structure()").collect(java.util.stream.Collectors.joining(","));
            out.append("generated::ast::Ast::r#").append(variant.getKey()).append(" {").append(names).append(",..}=>format!(\"node(").append(variant.getKey()).append(",{})\",vec![").append(values).append("].join(\",\")),");
        }
        out.append("}}}\n");
        if(fixture.has("enumTypes"))for(var entry:fixture.getAsJsonArray("enumTypes")) {
            String name=entry.getAsString();out.append("impl Structure for generated::ast::").append(name).append("{fn structure(&self)->String{format!(\"enum(").append(name).append(",{})\",self.canonical_json().trim_matches('\"'))}}\n");
        }
        out.append("struct NestedMappedSemantics;impl generated::evaluator::Semantics for NestedMappedSemantics{type Output=String;");
        for(var variant:types.entrySet()) {
            var fields=variant.getValue().getAsJsonObject();
            out.append("fn eval_").append(variant.getKey().toLowerCase(java.util.Locale.ROOT)).append("(&mut self,");
            for(var field:fields.entrySet())out.append(field.getKey()).append(':').append(borrowed(field.getValue().getAsString(),variant.getKey().equals("Root"))).append(',');
            out.append("_span:unlaxer_runtime::Span)->String{");
            if(variant.getKey().equals("Root")&&fields.size()==1&&fields.has("value"))out.append("value.structure()");
            else out.append("format!(\"node(").append(variant.getKey()).append(",{})\",vec![").append(fields.keySet().stream().map(n->n+".structure()").collect(java.util.stream.Collectors.joining(","))).append("].join(\",\"))");
            out.append('}');
        }
        out.append('}');return out.toString();
    }
    @Override protected String extraRustObservation(JsonObject fixture) {
        var expected=new StringBuilder("match input.as_str(){");
        for(var entry:fixture.getAsJsonArray("cases")){var row=entry.getAsJsonObject();expected.append(row.get("input")).append("=>").append(row.get("structure")).append(',');}
        expected.append("_=>panic!(\"missing oracle input\")}");
        var preferred=new StringBuilder("match input.as_str(){");
        for(var entry:fixture.getAsJsonArray("cases")) {
            var row=entry.getAsJsonObject();preferred.append(row.get("input")).append("=>vec![");
            for(var candidate:row.getAsJsonArray("preferred")){var c=candidate.getAsJsonArray();preferred.append('(').append(c.get(0)).append(',').append(c.get(1)).append(',').append(c.get(2)).append(',').append(c.get(3)).append("),");}
            preferred.append("],");
        }
        preferred.append("_=>panic!(\"missing oracle input\")}");
        String extra="";
        if(fixture.has("distinctFields"))extra+="if let Ast::Root{first,second,..}=&ast {assert!(!std::ptr::eq(first.as_ref().unwrap().as_ref(),second.as_ref().unwrap().as_ref()));}";
        if(fixture.has("commonField"))extra+="if let Ast::Root{value,..}=&ast {assert_eq!(value.as_ref().unwrap().iter().map(|v|v.common_node().unwrap().text).collect::<Vec<_>>(),vec![\"a\",\"😀\"]);}";
        return extra+"assert_eq!(generated::evaluator::evaluate(&ast,&mut NestedMappedSemantics),"+expected+");let preferred_tree=generated::parser::parse_tree_for_mapping(&input).unwrap();let snapshot=generated::mapper::map_parsed_tree(&preferred_tree).unwrap();drop(preferred_tree);assert_eq!(snapshot.candidates().iter().map(|c|(c.simple_name(),c.depth(),c.span().start,c.span().end)).collect::<Vec<_>>(),"+preferred+");";
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

    @org.junit.Test public void unsupportedShapesFailBeforeEmissionWithSourceOrigins() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget().toString()),"",false));
        var corpus=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/nested-mapped-leaves/invalid.json"))).getAsJsonArray();
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
    @org.junit.Test public void mappingFailuresAndParseRollbackKeepLaterConsumersUsable() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var fixture=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(repo.resolve("spec-corpus/nested-mapped-leaves/failures.json"))).getAsJsonObject();
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
            var parsers=loader.loadClass("org.example.nestedcaptures.NumericNodeParsers");
            var parser=(org.unlaxer.parser.Parser)parsers.getMethod("getRootParser").invoke(null);
            var mapper=loader.loadClass("org.example.nestedcaptures.NumericNodeMapper");
            var astType=loader.loadClass("org.example.nestedcaptures.NumericNodeAST");
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

}
