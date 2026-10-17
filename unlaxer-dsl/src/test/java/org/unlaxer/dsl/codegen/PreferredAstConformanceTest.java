package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
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

/** Independent candidate order, source spans and snapshot retention oracle. */
public class PreferredAstConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    protected JsonArray corpus() throws Exception {
        return JsonParser.parseString(
                        Files.readString(repo.resolve("spec-corpus/preferred-ast/cases.json")))
                .getAsJsonArray();
    }

    @Test
    public void javaSnapshots() throws Exception {
        for (var entry : corpus()) {
            var fixture = entry.getAsJsonObject();
            var grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            try (var loader = compile(grammar, fixture.get("java").getAsString())) {
                var parser =
                        (Parser)
                                loader.loadClass("preferredprobe." + grammar.name() + "Parsers")
                                        .getMethod("getRootParser")
                                        .invoke(null);
                Parser syntax =
                        fixture.has("bindings")
                                ? (Parser)
                                        loader.loadClass(
                                                        "preferredprobe."
                                                                + grammar.name()
                                                                + "Parsers")
                                                .getMethod("getRootSyntaxParser")
                                                .invoke(null)
                                : parser;
                var mapper = loader.loadClass("preferredprobe." + grammar.name() + "Mapper");
                var astType = loader.loadClass("preferredprobe." + grammar.name() + "AST");
                var evaluator =
                        loader.loadClass("preferredprobe.Probe").getConstructor().newInstance();
                var eval = evaluator.getClass().getMethod("eval", astType);
                var retained = new ArrayList<Runnable>();
                for (var raw : fixture.getAsJsonArray("cases")) {
                    var row = raw.getAsJsonObject();
                    String input = row.get("input").getAsString();
                    try (var context =
                            ParseContext.withBindings(
                                    StringSource.createRootSource(input),
                                    fixture.has("bindings")
                                            ? Map.of(
                                                    "ubnf.name.names.version",
                                                    List.of("v1"),
                                                    "ubnf.name.names.type",
                                                    List.of("T"))
                                            : Map.of(),
                                    org.unlaxer.context.ParseOptions.DEFAULT)) {
                        boolean parsed =
                                parser.parse(context).isSucceeded()
                                        && input.codePointCount(0, input.length())
                                                == context.getConsumedPosition().value();
                        if (row.has("stage") && row.get("stage").getAsString().equals("parse")) {
                            assertFalse(input, parsed);
                            continue;
                        }
                        assertTrue(input, parsed);
                        Token token =
                                context.getCurrent().getTokens().stream()
                                        .map(t -> findToken(t, syntax))
                                        .filter(Objects::nonNull)
                                        .findFirst()
                                        .orElseThrow();
                        if (row.has("stage")) {
                            var failure = assertThrows(
                                    java.lang.reflect.InvocationTargetException.class,
                                    () ->
                                            mapper.getMethod("mapParsedTree", Token.class)
                                                    .invoke(null, token));
                            if (row.has("errorCp")) {
                                String at = "at CP " + row.getAsJsonArray("errorCp").get(0).getAsInt() + ":" + row.getAsJsonArray("errorCp").get(1).getAsInt();
                                assertTrue(failure.getCause().toString(),failure.getCause().toString().contains(at));
                            }
                            continue;
                        }
                        var snapshot =
                                mapper.getMethod("mapParsedTree", Token.class).invoke(null, token);
                        var concurrent =
                                java.util.concurrent.CompletableFuture.runAsync(
                                        () -> {
                                            try {
                                                mapper.getMethod("mapParsedTree", Token.class)
                                                        .invoke(null, token);
                                            } catch (ReflectiveOperationException e) {
                                                throw new AssertionError(e);
                                            }
                                        });
                        var candidates =
                                (List<?>)
                                        snapshot.getClass()
                                                .getMethod("candidates")
                                                .invoke(snapshot);
                        var expected = row.getAsJsonArray("candidates");
                        assertEquals(grammar.name(), expected.size(), candidates.size());
                        assertThrows(UnsupportedOperationException.class, () -> candidates.clear());
                        for (int i = 0; i < candidates.size(); i++) {
                            var candidate = candidates.get(i);
                            var want = expected.get(i).getAsJsonArray();
                            var type = candidate.getClass();
                            var sourceMap = type.getMethod("sourceMap").invoke(candidate);
                            var ast = sourceMap.getClass().getMethod("ast").invoke(sourceMap);
                            var selectedToken = (Token) type.getMethod("token").invoke(candidate);
                            if (i == 0 && fixture.has("ordinary")) {
                                var ordinary = mapper.getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, token);
                                assertEquals(ast, ordinary.getClass().getMethod("ast").invoke(ordinary));
                            }
                            assertEquals(
                                    grammar.name(),
                                    want.get(0).getAsString(),
                                    type.getMethod("simpleName").invoke(candidate));
                            assertEquals(
                                    grammar.name(),
                                    want.get(1).getAsInt(),
                                    type.getMethod("depth").invoke(candidate));
                            assertEquals(
                                    grammar.name(),
                                    want.get(2).getAsInt(),
                                    type.getMethod("startOffset").invoke(candidate));
                            String slice =
                                    input.substring(
                                            input.offsetByCodePoints(0, want.get(2).getAsInt()),
                                            input.offsetByCodePoints(0, want.get(3).getAsInt()));
                            assertEquals(slice, selectedToken.source.toString());
                            retained.add(
                                    () -> {
                                        try {
                                            assertEquals(
                                                    want.get(4).getAsString(),
                                                    eval.invoke(evaluator, ast));
                                            var span =
                                                    (int[])
                                                            ((Optional<?>)
                                                                            sourceMap
                                                                                    .getClass()
                                                                                    .getMethod(
                                                                                            "sourceSpanOf",
                                                                                            Object
                                                                                                    .class)
                                                                                    .invoke(
                                                                                            sourceMap,
                                                                                            ast))
                                                                    .orElseThrow();
                                            assertArrayEquals(
                                                    new int[] {
                                                        want.get(2).getAsInt(),
                                                        want.get(3).getAsInt()
                                                    },
                                                    span);
                                        } catch (ReflectiveOperationException e) {
                                            throw new AssertionError(e);
                                        }
                                    });
                        }
                        for (String preferred :
                                new String[] {null, "", " ", "\u2003", "Missing", "Pair", "Item"}) {
                            var selected =
                                    snapshot.getClass()
                                            .getMethod("select", String.class)
                                            .invoke(snapshot, preferred);
                            int index =
                                    "Item".equals(preferred)
                                            ? row.get("select").getAsInt()
                                            : (row.has("default")
                                                    ? row.get("default").getAsInt()
                                                    : 0);
                            var candidate = candidates.get(index);
                            assertSame(
                                    candidate.getClass().getMethod("token").invoke(candidate),
                                    selected.getClass().getMethod("token").invoke(selected));
                            var again =
                                    snapshot.getClass()
                                            .getMethod("select", String.class)
                                            .invoke(snapshot, preferred);
                            var one = selected.getClass().getMethod("sourceMap").invoke(selected);
                            var two = again.getClass().getMethod("sourceMap").invoke(again);
                            assertSame(
                                    one.getClass().getMethod("ast").invoke(one),
                                    two.getClass().getMethod("ast").invoke(two));
                        }
                        if (candidates.size() > 1 || fixture.has("unmapped")) {
                            var candidate = candidates.get(candidates.size() - 1);
                            var child =
                                    (Token)
                                            candidate
                                                    .getClass()
                                                    .getMethod("token")
                                                    .invoke(candidate);
                            var subtree =
                                    mapper.getMethod("mapSubtreeTree", Token.class)
                                            .invoke(null, child);
                            var children =
                                    (List<?>)
                                            subtree.getClass()
                                                    .getMethod("candidates")
                                                    .invoke(subtree);
                            assertEquals(
                                    0,
                                    children.get(0)
                                            .getClass()
                                            .getMethod("depth")
                                            .invoke(children.get(0)));
                            if (!fixture.has("unmapped") && child.parser.getClass() != token.parser.getClass())
                                assertThrows(
                                        java.lang.reflect.InvocationTargetException.class,
                                        () ->
                                                mapper.getMethod("mapParsedTree", Token.class)
                                                        .invoke(null, child));
                        }
                        concurrent.join();
                    }
                }
                retained.forEach(Runnable::run);
            }
        }
    }

    @Test
    public void bothHostsSnapshots() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] PreferredAstConformanceTest requires -DrustConformance=true");
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
            var code =
                    new StringBuilder(
                                    "#[path=\"generated/mod.rs\"] mod generated; use"
                                        + " generated::{ast::Ast,evaluator::{Semantics,evaluate}};"
                                        + " struct Eval; impl Semantics for Eval { type"
                                        + " Output=String; ")
                            .append(fixture.get("rust").getAsString())
                            .append("} fn main(){let mut retained:Vec<generated::mapper::MappedTree>=Vec::new();\n");
            for (var raw : fixture.getAsJsonArray("cases")) {
                var row = raw.getAsJsonObject();
                String value = row.get("input").getAsString();
                if (row.has("stage")) {
                    if (row.get("stage").getAsString().equals("parse")) {
                        code.append("assert!(generated::parser::parse_tree_for_mapping(")
                                .append(rustString(value))
                                .append(").is_err());\n");
                    } else if (row.has("errorCp")) {
                        String at = "at CP " + row.getAsJsonArray("errorCp").get(0).getAsInt() + ":" + row.getAsJsonArray("errorCp").get(1).getAsInt();
                        code.append("{let tree=generated::parser::parse_tree_for_mapping(").append(rustString(value)).append(").unwrap();let error=generated::mapper::map_parsed_tree(&tree).err().expect(\"mapping failure\");assert!(error.contains(").append(rustString(at)).append("),\"{}\",error);}");
                    } else {
                        code.append("{let tree=generated::parser::parse_tree_for_mapping(")
                                .append(rustString(value))
                                .append(
                                        ").unwrap();assert!(generated::mapper::map_parsed_tree(&tree).is_err());}\n");
                    }
                    continue;
                }
                code.append("{let source=")
                        .append(rustString(value))
                        .append(
                                ";let tree=generated::parser::parse_tree_for_mapping(source).unwrap();let"
                                    + " snapshot=generated::mapper::map_parsed_tree(&tree).unwrap();assert_eq!(snapshot.source(),source);let"
                                    + " expected=&[");
                for (var want : row.getAsJsonArray("candidates")) {
                    var c = want.getAsJsonArray();
                    code.append("(")
                            .append(rustString(c.get(0).getAsString()))
                            .append(",")
                            .append(c.get(1))
                            .append(",")
                            .append(c.get(2))
                            .append(",")
                            .append(c.get(3))
                            .append(",")
                            .append(rustString(c.get(4).getAsString()))
                            .append("),");
                }
                code.append(
                        "];assert_eq!(snapshot.candidates().len(),expected.len());for(c,e) in"
                            + " snapshot.candidates().iter().zip(expected){assert_eq!((c.simple_name(),c.depth(),c.span().start,c.span().end,evaluate(c.ast(),&mut"
                            + " Eval).as_str()),(e.0,e.1,e.2,e.3,e.4));assert_eq!(snapshot.source_text(c.span()),Some(tree.text(c.span())));}");
                if (fixture.has("rustCheck")) code.append(fixture.get("rustCheck").getAsString());
                if (fixture.has("ordinary")) code.append("assert_eq!(generated::mapper::map(&generated::parser::parse_tree(source).unwrap()).unwrap().canonical_json(),snapshot.select_default().ast().canonical_json());");
                code.append(
                        "for name in [None,Some(\"\"),Some(\""
                            + " \"),Some(\"Missing\"),Some(\"Pair\")]{assert!(std::ptr::eq(snapshot.select(name),&snapshot.candidates()["
                                + (row.has("default") ? row.get("default").getAsInt() : 0)
                                + "]));}");
                code.append(
                                "assert!(std::ptr::eq(snapshot.select(Some(\"Item\")),&snapshot.candidates()[")
                        .append(row.get("select"))
                        .append(
                                "]));assert!(generated::mapper::map_parsed_tree(&generated::parser::parse_tree(source).unwrap()).is_err());");
                code.append(
                        "assert!(generated::mapper::map_subtree_tree_with_limits(&tree,tree.root,generated::mapper::MappingLimits{candidates:0,projection_work:100}).is_err());assert!(generated::mapper::map_subtree_tree_with_limits(&tree,tree.root,generated::mapper::MappingLimits{candidates:100,projection_work:0}).is_err());");
                code.append(
                        "if snapshot.candidates().len()>1 ||"
                            + " snapshot.candidates()[0].node_id()!=tree.root {let"
                            + " id=snapshot.candidates().last().unwrap().node_id();let"
                            + " sub=generated::mapper::map_subtree_tree(&tree,id).unwrap();assert_eq!(sub.candidates()[0].depth(),0);let"
                            + " mut wrong=tree.clone();wrong.root=id;if tree.nodes[id].rule!=tree.nodes[tree.root].rule{assert!(generated::mapper::map_parsed_tree(&wrong).is_err());}else{assert!(generated::mapper::map_parsed_tree(&wrong).is_ok());}}");
                code.append(
                        "let mut"
                            + " broken=tree.clone();broken.root=usize::MAX;assert!(generated::mapper::map_parsed_tree(&broken).is_err());let"
                            + " mut broken=tree.clone();broken.source.push('x');assert!(generated::mapper::map_parsed_tree(&broken).is_err());let"
                            + " mut broken=tree.clone();broken.nodes[tree.root].children.push(tree.root);assert!(generated::mapper::map_parsed_tree(&broken).is_err());let"
                            + " mut broken=tree.clone();broken.nodes[tree.root].span.end=usize::MAX;assert!(generated::mapper::map_parsed_tree(&broken).is_err());retained.push(snapshot);"
                            + " }\n");
            }
            if (fixture.has("foreignGrammar")) {
                String foreignSource = fixture.get("foreignGrammar").getAsString();
                Path foreignInput = dir.resolve("foreign.ubnf"), foreignOutput = dir.resolve("foreign");
                Files.writeString(foreignInput, foreignSource);
                run(List.of(generator.toString(), "generate", "--grammar", foreignInput.toString(),
                    "--output", foreignOutput.toString()), "", true, true);
                var foreignGrammar = UBNFMapper.parse(foreignSource).grammars().get(0);
                for (var file : new RustBackend().generate(foreignGrammar))
                    assertEquals(file.relativePath(), file.content(), Files.readString(foreignOutput.resolve(file.relativePath())));
                code.insert(0, "#[path=\"foreign/mod.rs\"] mod foreign; ");
                code.append("let foreign=foreign::parser::parse_tree_for_mapping(")
                    .append(rustString(fixture.getAsJsonArray("cases").get(0).getAsJsonObject().get("input").getAsString()))
                    .append(").unwrap();assert!(generated::mapper::map_parsed_tree(&foreign).is_err());\n");
            }
            code.append(
                    "for snapshot in retained"
                        + " {std::thread::scope(|s|{s.spawn(||{assert!(std::ptr::eq(snapshot.select_default(),snapshot.select(None)));});});}}\n");
            if (fixture.has("bindings")) {
                String snapshots =
                        "&[unlaxer_runtime::names::Snapshot::new(\"names\",\"v1\",[(\"T\".to_owned(),unlaxer_runtime::names::Kind::Type)].into()).unwrap()]";
                String changed =
                        code.toString()
                                .replace(
                                        "generated::parser::parse_tree_for_mapping(source)",
                                        "generated::parser::parse_tree_for_mapping_with_name_snapshots(source,"
                                                + snapshots
                                                + ",unlaxer_runtime::ParseOptions::default())")
                                .replace(
                                        "generated::parser::parse_tree(source)",
                                        "generated::parser::parse_tree_detailed_with_name_snapshots(source,"
                                                + snapshots
                                                + ",unlaxer_runtime::ParseOptions::default())");
                code = new StringBuilder(changed);
            }
            if (fixture.has("unmapped"))
                code =
                        new StringBuilder(
                                code.toString()
                                        .replace(
                                                "map_parsed_tree(&wrong).is_err()",
                                                "map_parsed_tree(&wrong).is_ok()"));
            if (fixture.has("unsupported")) {
                code =
                        new StringBuilder(
                                "#[path=\"generated/mod.rs\"] mod generated; fn"
                                    + " main(){assert!(generated::parser::mapping_grammar().is_none());");
                for (var row : fixture.getAsJsonArray("cases")) {
                    String value = rustString(row.getAsJsonObject().get("input").getAsString());
                    code.append("assert!(generated::parser::parse_tree(")
                            .append(value)
                            .append(").is_ok());assert!(generated::parser::parse_tree_for_mapping(")
                            .append(value)
                            .append(").is_err());");
                }
                code.append("}");
            }
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

    private Token findToken(Token token, Parser parser) {
        if (token.parser == parser) return token;
        for (Token child : token.filteredChildren) {
            Token found = findToken(child, parser);
            if (found != null) return found;
        }
        return null;
    }

    private String rustString(String value) {
        return new GsonBuilder().disableHtmlEscaping().create().toJson(value);
    }

    private URLClassLoader compile(GrammarDecl grammar, String methods) throws Exception {
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
                        "preferredprobe.Probe",
                        "package preferredprobe; public class Probe extends "
                                + grammar.name()
                                + "Evaluator<String> { "
                                + methods
                                + " }"));
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

    protected String run(List<String> command, String input, boolean withoutJava, boolean success)
            throws Exception {
        Path log = temporary.newFile().toPath();
        var builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
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
