package org.unlaxer.dsl.codegen;
import static org.junit.Assert.*;
import com.google.gson.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.context.*;

/** Independent callback/value/short-circuit oracle, executed by both generated language targets. */
public class EvaluationConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary=new org.junit.rules.TemporaryFolder();
    static final Path FIXTURES=Path.of("../spec-corpus/evaluation");
    @Before public void baseline(){Assume.assumeTrue("Java21 baseline",Runtime.version().feature()>=21);}
    @Test public void javaEvaluationOracle()throws Exception{javaOracle("AutoEval","autoeval","probe.java.txt","cases.json");javaOracle("ValueEval","valueeval","value-probe.java.txt","value-cases.json");}
    private void javaOracle(String grammarName,String packageName,String probeFile,String casesFile)throws Exception{
        var grammar=UBNFMapper.parse(Files.readString(FIXTURES.resolve(grammarName+".ubnf"))).grammars().get(0);
        var units=new ArrayList<JavaFileObject>();
        for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator(),new EvaluatorGenerator(21))){var file=generator.generate(grammar);units.add(source(file.packageName()+"."+file.className(),file.source()));}
        units.add(source("org.example."+packageName+".Probe",Files.readString(FIXTURES.resolve(probeFile))));
        Path classes=temporary.newFolder().toPath();var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)){assertTrue(diagnostics.getDiagnostics().toString(),compiler.getTask(null,manager,diagnostics,List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,units).call());}
        if(grammarName.equals("AutoEval")) {
            for(String obligation:List.of("convertValue", "evalManual", "evalUnknown", "evalLeaf")) {
                String incomplete=Files.readString(FIXTURES.resolve(probeFile));
                if(obligation.equals("convertValue")) incomplete=incomplete.replaceAll("(?s)    @Override protected Object convertValue\\(Object value\\) \\{.*?\\n    \\}\\n", "");
                else if(obligation.equals("evalManual")) incomplete=incomplete.replaceAll("(?s)    @Override protected Object evalManual\\(AutoEvalAST.Manual node\\) \\{.*?\\n    \\}\\n", "");
                else incomplete=incomplete.replaceAll("(?m)^    @Override protected Object "+obligation+".*\\n", "");
                var missing=new ArrayList<>(units.subList(0,units.size()-1));missing.add(source("org.example.autoeval.Probe",incomplete));
                var problems=new DiagnosticCollector<JavaFileObject>();
                try(var manager=compiler.getStandardFileManager(problems,null,null)) {
                    assertFalse("missing "+obligation,compiler.getTask(null,manager,problems,List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",temporary.newFolder().toString()),null,missing).call());
                    assertTrue(problems.getDiagnostics().toString(),problems.getDiagnostics().stream().anyMatch(d->d.getMessage(java.util.Locale.ROOT).contains(obligation)));
                }
            }
        }
        var reports=new ArrayList<String>(List.of("input\tmemo\tdiagnostics\tresult"));
        try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())){
            var mapper=loader.loadClass("org.example."+packageName+"."+grammarName+"Mapper");
            var parser=(org.unlaxer.parser.Parser)loader.loadClass("org.example."+packageName+"."+grammarName+"Parsers").getMethod("getRootParser").invoke(null);
            var probe=loader.loadClass("org.example."+packageName+".Probe").getMethod("run",loader.loadClass("org.example."+packageName+"."+grammarName+"AST"));
            if(grammarName.equals("AutoEval")) assertEquals(JsonParser.parseString("{\"error\":\"binary operator/operand count mismatch\",\"events\":[]}"),loader.loadClass("org.example.autoeval.Probe").getMethod("invalidBinary").invoke(null));
            for(var item:JsonParser.parseString(Files.readString(FIXTURES.resolve(casesFile))).getAsJsonArray()){
                var row=item.getAsJsonObject();String input=row.get("input").getAsString();
                for(var memo:Memoization.values())for(var diagnosticsMode:ParseOptions.Diagnostics.values()){
                    try(var context=ParseContext.withOptions(org.unlaxer.StringSource.createRootSource(input),ParseOptions.withMemoization(memo).withDiagnostics(diagnosticsMode))){
                        assertTrue(input,parser.parse(context).isSucceeded());assertEquals(input.codePointCount(0,input.length()),context.getConsumedPosition().value());
                        var token=context.getCurrent().getTokens().get(0);
                        var mapped=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,token);
                        Object ast=mapped.getClass().getMethod("ast").invoke(mapped);
                        var actual=(JsonObject)probe.invoke(null,ast);var expected=row.deepCopy();expected.remove("input");expected.remove("span");assertEquals(input,expected,actual);
                        int[] span=(int[])((Optional<?>)mapped.getClass().getMethod("sourceSpanOf",Object.class).invoke(mapped,ast)).orElseThrow();assertEquals(input,row.get("span"),new Gson().toJsonTree(span));
                        reports.add(row.get("input")+"\t"+memo+"\t"+diagnosticsMode+"\tchecked");
                    }
                }
            }
        }
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/evaluation-"+grammarName+"-java.tsv"),reports);
    }
    @Test public void nativeAndJavaRustHostsExecuteOracle()throws Exception{
        rustOracle("AutoEval","probe.rs.txt","cases.json");rustOracle("ValueEval","value-probe.rs.txt","value-cases.json");
    }
    private void rustOracle(String grammarName,String probeFile,String casesFile)throws Exception{
        Assume.assumeTrue("requires -DrustConformance=true",Boolean.getBoolean("rustConformance"));
        Path repo=Path.of("..").toAbsolutePath().normalize(),dir=temporary.newFolder().toPath(),nativeOut=dir.resolve("native");
        run(List.of("cargo","run","--quiet","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--bin","unlaxer","--","generate","--target","rust","--grammar",FIXTURES.resolve(grammarName+".ubnf").toAbsolutePath().normalize().toString(),"--output",nativeOut.toString()),dir);
        var grammar=UBNFMapper.parse(Files.readString(FIXTURES.resolve(grammarName+".ubnf"))).grammars().get(0);Path generated=Files.createDirectory(dir.resolve("generated"));
        for(var file:new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)){assertEquals(file.relativePath(),file.content(),Files.readString(nativeOut.resolve(file.relativePath())));Files.writeString(generated.resolve(file.relativePath()),file.content());}
        String grammarText=Files.readString(FIXTURES.resolve(grammarName+".ubnf"));
        var javaReport=org.unlaxer.dsl.impact.ApiImpact.compare("java",grammarText,grammarText);
        assertTrue(javaReport.toJson(),javaReport.ok()); assertFalse(javaReport.hasChanges());
        String nativeImpact=run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(),"impact","--target","rust","--before",FIXTURES.resolve(grammarName+".ubnf").toAbsolutePath().normalize().toString(),"--after",FIXTURES.resolve(grammarName+".ubnf").toAbsolutePath().normalize().toString()),dir);
        assertEquals("actual API schema",JsonParser.parseString(org.unlaxer.dsl.impact.ApiImpact.compare("rust",grammarText,grammarText).toJson()),JsonParser.parseString(nativeImpact));
        var api=org.unlaxer.dsl.impact.ApiImpact.schema("rust",grammarText);
        assertTrue(api.methods().stream().anyMatch(m->m.name().equals("convert_value")&&m.required()));
        assertTrue(api.methods().stream().anyMatch(m->m.name().equals(grammarName.equals("AutoEval")?"eval_leaf":"eval_single")&&!m.required()));
        Files.writeString(dir.resolve("main.rs"),Files.readString(FIXTURES.resolve(probeFile)));
        Files.writeString(dir.resolve("Cargo.toml"),"[package]\nname=\"evaluation-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[dependencies]\nunlaxer-runtime={path=\""+repo.resolve("rust/unlaxer-runtime")+"\"}\nserde_json=\"1\"\n");
        run(List.of("cargo","run","--quiet","--manifest-path",dir.resolve("Cargo.toml").toString(),"--target-dir",repo.resolve("rust/target/evaluation-probe").toString(),"--",FIXTURES.resolve(casesFile).toAbsolutePath().normalize().toString()),dir);
        if(grammarName.equals("AutoEval")) {
            for(String obligation:List.of("convert_value", "eval_manual", "eval_unknown", "evaluate_literal")) {
                String incomplete=Files.readString(FIXTURES.resolve(probeFile)).replaceAll("(?m)^    fn "+obligation+".*\\n", "");Files.writeString(dir.resolve("main.rs"),incomplete);
                Path log=temporary.newFile().toPath();
                var process=org.unlaxer.dsl.TestProcesses.builder("cargo","check","--quiet","--manifest-path",dir.resolve("Cargo.toml").toString(),"--target-dir",repo.resolve("rust/target/evaluation-probe").toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try{assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS));assertNotEquals(Files.readString(log),0,process.exitValue());assertTrue(Files.readString(log),Files.readString(log).contains("E0046")&&Files.readString(log).contains(obligation));}finally{if(process.isAlive())process.destroyForcibly();}
            }
        }
        Files.writeString(Path.of("target/evaluation-"+grammarName+"-rust.tsv"),"grammar\tinputs\tconfigurations\tresult\n"+grammarName+"\t"+JsonParser.parseString(Files.readString(FIXTURES.resolve(casesFile))).getAsJsonArray().size()+"\t6\tchecked\n");
    }
    @Test public void invalidAutomaticShapesAreRejectedByBothHosts()throws Exception{
        Path repo=Path.of("..").toAbsolutePath().normalize(),dir=temporary.newFolder().toPath();
        boolean rust=Boolean.getBoolean("rustConformance");
        if(rust)run(List.of("cargo","build","--quiet","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--bin","unlaxer"),dir);
        for(var item:JsonParser.parseString(Files.readString(FIXTURES.resolve("invalid.json"))).getAsJsonArray()){
            var row=item.getAsJsonObject();String text=row.get("grammar").getAsString();var grammar=UBNFMapper.parse(text).grammars().get(0);
            var javaError=assertThrows(IllegalArgumentException.class,()->new EvaluatorGenerator().generate(grammar));assertTrue(javaError.toString(),javaError.getMessage().contains(row.get("code").getAsString()));
            assertThrows(IllegalArgumentException.class,()->new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar));
            if(rust){Path file=dir.resolve("invalid.ubnf"),out=dir.resolve(row.get("name").getAsString()),log=temporary.newFile().toPath();Files.writeString(file,text);
                var process=org.unlaxer.dsl.TestProcesses.builder(repo.resolve("rust/target/debug/unlaxer").toString(),"generate","--target","rust","--grammar",file.toString(),"--output",out.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try{assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertNotEquals(Files.readString(log),0,process.exitValue());assertTrue(Files.readString(log),Files.readString(log).contains(row.get("code").getAsString()));assertFalse(Files.exists(out));}finally{if(process.isAlive())process.destroyForcibly();}
            }
        }
    }
    private String run(List<String> args,Path directory)throws Exception{Path log=temporary.newFile().toPath();var process=org.unlaxer.dsl.TestProcesses.builder(args).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();try{assertTrue("timeout "+args,process.waitFor(120,java.util.concurrent.TimeUnit.SECONDS));assertEquals(args+"\n"+Files.readString(log),0,process.exitValue());return Files.readString(log);}finally{if(process.isAlive())process.destroyForcibly();}}
    private static JavaFileObject source(String name,String text){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return text;}};}
}
