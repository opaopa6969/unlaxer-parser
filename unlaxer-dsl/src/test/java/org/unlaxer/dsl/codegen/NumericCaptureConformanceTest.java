package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.*;

/** Handwritten integer values and parse/map failure stages, independently shared by both hosts. */
public class NumericCaptureConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(
                        Files.readString(repo.resolve("spec-corpus/numeric-captures/cases.json")))
                .getAsJsonArray();
    }

    @Test
    public void javaTypedMappingAndFailureSnapshots() throws Exception {
        for (var entry : corpus()) {
            var fixture = entry.getAsJsonObject();
            var grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            try (var loader = compile(grammar, fixture.get("java").getAsString())) {
                var parser =
                        (Parser)
                                loader.loadClass("numericprobe." + grammar.name() + "Parsers")
                                        .getMethod("getRootParser")
                                        .invoke(null);
                var mapper = loader.loadClass("numericprobe." + grammar.name() + "Mapper");
                var astType = loader.loadClass("numericprobe." + grammar.name() + "AST");
                var itemType = loader.loadClass("numericprobe." + grammar.name() + "AST$Item");
                assertEquals(
                        fixture.get("type").getAsString(),
                        itemType.getMethod("value").getReturnType().getName());
                var evaluator =
                        loader.loadClass("numericprobe.Probe").getConstructor().newInstance();
                var eval = evaluator.getClass().getMethod("eval", astType);
                var retained = new ArrayList<Runnable>();
                for (var raw : fixture.getAsJsonArray("cases")) {
                    var row = raw.getAsJsonArray();
                    String input = row.get(0).getAsString();
                    String stage = row.get(1).getAsString();
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        var parsed = parser.parse(context);
                        boolean complete =
                                parsed.isSucceeded()
                                        && context.getConsumedPosition().value()
                                                == input.codePointCount(0, input.length());
                        if (stage.equals("parse")) {
                            assertFalse(input, complete);
                            continue;
                        }
                        assertTrue(input, complete);
                        var token =
                                context.getCurrent().getTokens().stream()
                                        .filter(t -> t.parser == parser)
                                        .findFirst()
                                        .orElseThrow();
                        var map =
                                mapper.getMethod(
                                        "mapParsedTokenWithSourceMap", org.unlaxer.Token.class);
                        if (stage.equals("map")) {
                            assertThrows(
                                    input,
                                    java.lang.reflect.InvocationTargetException.class,
                                    () -> map.invoke(null, token));
                            continue;
                        }
                        var mapped = map.invoke(null, token);
                        var ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        assertEquals(input, row.get(2).getAsString(), eval.invoke(evaluator, ast));
                        retained.add(
                                () -> {
                                    try {
                                        assertEquals(
                                                input,
                                                row.get(2).getAsString(),
                                                eval.invoke(evaluator, ast));
                                        int[] span =
                                                (int[])
                                                        ((Optional<?>)
                                                                        mapped.getClass()
                                                                                .getMethod(
                                                                                        "sourceSpanOf",
                                                                                        Object
                                                                                                .class)
                                                                                .invoke(
                                                                                        mapped,
                                                                                        ast))
                                                                .orElseThrow();
                                        assertArrayEquals(
                                                input,
                                                new int[] {
                                                    row.get(3).getAsJsonArray().get(0).getAsInt(),
                                                    row.get(3).getAsJsonArray().get(1).getAsInt()
                                                },
                                                span);
                                        assertEquals(input, token.source.toString());
                                    } catch (ReflectiveOperationException error) {
                                        throw new AssertionError(error);
                                    }
                                });
                    }
                }
                retained.forEach(Runnable::run);
            }
        }
    }

    @Test
    public void bothHostsCompileEvaluateAndReject() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] NumericCaptureConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_runtime",
                        repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                        "-o",
                        runtime.toString()),
                "",
                false,
                true);
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator"),
                "",
                false,
                true);
        Path generator = repo.resolve("rust/target/debug/unlaxer");
        for (var entry : corpus()) {
            var fixture = entry.getAsJsonObject();
            String source = fixture.get("grammar").getAsString();
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            Path dir = temporary.newFolder().toPath(),
                    input = dir.resolve("grammar.ubnf"),
                    output = dir.resolve("generated");
            Files.writeString(input, source);
            run(
                    List.of(
                            generator.toString(),
                            "generate",
                            "--grammar",
                            input.toString(),
                            "--output",
                            output.toString()),
                    "",
                    true,
                    true);
            for (var file : new RustBackend().generate(grammar))
                assertEquals(
                        grammar.name() + "/" + file.relativePath(),
                        file.content(),
                        Files.readString(output.resolve(file.relativePath())));
            String selfImpact =
                    org.unlaxer.dsl.impact.ApiImpact.compare("rust", source, source).toJson();
            String nativeImpact =
                    run(
                            List.of(
                                    generator.toString(),
                                    "impact",
                                    "--target",
                                    "rust",
                                    "--before",
                                    input.toString(),
                                    "--after",
                                    input.toString()),
                            "",
                            true,
                            true);
            assertEquals(JsonParser.parseString(selfImpact), JsonParser.parseString(nativeImpact));
            StringBuilder code =
                    new StringBuilder(
                                    "#[path=\"generated/mod.rs\"] mod generated;\n"
                                        + "use generated::{ast::Ast,evaluator::{Semantics,evaluate}};\n"
                                        + "struct Eval; impl Semantics for Eval {type"
                                        + " Output=String; fn eval_item(&mut self,")
                            .append(fixture.get("rustArgs").getAsString())
                            .append(",_span:unlaxer_runtime::Span)->String{")
                            .append(fixture.get("rust").getAsString())
                            .append("}}\nfn main(){ let mut retained=Vec::new();\n");
            for (var raw : fixture.getAsJsonArray("cases")) {
                var row = raw.getAsJsonArray();
                String stage = row.get(1).getAsString();
                code.append("{let source=")
                        .append(rustString(row.get(0).getAsString()))
                        .append(";let tree=generated::parser::parse_tree(source);");
                if (stage.equals("parse")) code.append("assert!(tree.is_err(),\"{}\",source);");
                else {
                    code.append("let tree=tree.unwrap();let ast=generated::mapper::map(&tree);");
                    if (stage.equals("map")) code.append("assert!(ast.is_err(),\"{}\",source);");
                    else
                        code.append("let ast=ast.unwrap();assert_eq!(evaluate(&ast,&mut Eval),")
                                .append(rustString(row.get(2).getAsString()))
                                .append(");assert_eq!([ast.span().start,ast.span().end],")
                                .append(row.get(3))
                                .append(");assert_eq!(ast.canonical_json(),")
                                .append(
                                        rustString(
                                                "{\"type\":\"Item\",\"span\":"
                                                        + row.get(3)
                                                        + ",\"fields\":{\"value\":"
                                                        + (row.get(2).getAsString().equals("none")
                                                                ? "null"
                                                                : row.get(2)
                                                                        .getAsString()
                                                                        .replace(" ", ""))
                                                        + "}}"))
                                .append(
                                        ");assert_eq!(tree.text(ast.span()),source);retained.push((ast,")
                                .append(rustString(row.get(2).getAsString()))
                                .append("));");
                }
                code.append("}\n");
            }
            code.append(
                    "for(ast,value) in retained{assert_eq!(evaluate(&ast,&mut Eval),value);}\n}");
            Path probe = dir.resolve("main.rs"), exe = dir.resolve("probe");
            Files.writeString(probe, code);
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            probe.toString(),
                            "--extern",
                            "unlaxer_runtime=" + runtime,
                            "-o",
                            exe.toString()),
                    "",
                    false,
                    true);
            run(List.of(exe.toString()), "", true, true);
        }
    }

    private String rustString(String value) {
        return new GsonBuilder().disableHtmlEscaping().create().toJson(value);
    }

    private URLClassLoader compile(GrammarDecl grammar, String expression) throws Exception {
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator :
                List.of(
                        new ParserGenerator(),
                        new ASTGenerator(),
                        new MapperGenerator(),
                        new EvaluatorGenerator(21))) {
            var source = generator.generate(grammar);
            units.add(unit(source.packageName() + "." + source.className(), source.source()));
        }
        units.add(
                unit(
                        "numericprobe.Probe",
                        "package numericprobe; public class Probe extends "
                                + grammar.name()
                                + "Evaluator<String> { protected String evalItem("
                                + grammar.name()
                                + "AST.Item node) { return "
                                + expression
                                + "; } }"));
        Path output = temporary.newFolder().toPath();
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled =
                    compiler.getTask(
                                    null,
                                    manager,
                                    diagnostics,
                                    List.of(
                                            "--release",
                                            "21",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            output.toString()),
                                    null,
                                    units)
                            .call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JavaFileObject unit(String name, String source) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return source;
            }
        };
    }

    private String run(List<String> command, String input, boolean withoutJava, boolean success)
            throws Exception {
        Path log = temporary.newFile().toPath();
        var builder =
                org.unlaxer.dsl.TestProcesses.builder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/missing-java");
        }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue("timeout: " + command, process.waitFor(60, TimeUnit.SECONDS));
            String result = Files.readString(log);
            if (success) assertEquals(result, 0, process.exitValue());
            else assertNotEquals(result, 0, process.exitValue());
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
