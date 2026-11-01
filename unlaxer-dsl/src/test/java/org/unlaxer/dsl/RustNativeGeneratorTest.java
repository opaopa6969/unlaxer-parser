package org.unlaxer.dsl;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.RecursiveSemanticShapes;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Java is an oracle only: every generation below uses the JVM-free native executable. */
public class RustNativeGeneratorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void nativeFrontendLoweringAndEmissionMatchTheSupportedCorpus() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        assertEquals(0, command(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path binary = repo.resolve("rust/target/debug/unlaxer");
        List<String> grammars = new ArrayList<>();
        for (String corpus : List.of("primitives", "lexical")) {
            for (var row : JsonParser.parseString(Files.readString(Path.of("src/test/resources/" + corpus + "/corpus.json"))).getAsJsonArray()) {
                var fixture = row.getAsJsonObject();
                grammars.add("grammar Primitive { @package: org.example.primitive "
                    + (fixture.has("whitespace") ? "@whitespace: javaStyle" : "")
                    + " token T = " + fixture.get("token").getAsString() + "\n token END = EOF\n "
                    + (fixture.has("extra") ? fixture.get("extra").getAsString() : "")
                    + "\n @root @mapping(Value,params=[value]) Root ::= " + fixture.get("body").getAsString() + "; }");
            }
        }
        String cardinality = Files.readString(Path.of("src/test/resources/cardinality/Cardinality.ubnf"));
        for (String quantifier : List.of("{ Item }", "Item+", "Item{1,2}", "Item{1,}", "Item % ','")) {
            grammars.add(cardinality.replace("{ Item }", quantifier));
        }
        for (int stage = 0; stage < 4; stage++) grammars.add(Files.readString(Path.of("src/test/resources/evolution/" + stage + "/Evolution.ubnf")));
        grammars.add(Files.readString(Path.of("src/test/resources/associative/Operators.ubnf")));
        for (String atom : List.of("Atom ::= NUMBER;", "@mapping(Number,params=[value]) Atom ::= (NUMBER) @value;")) {
            grammars.add("grammar Power { @whitespace: javaStyle token NUMBER = org.unlaxer.parser.elementary.NumberParser\n"
                + "@root @rightAssoc @precedence(level=10) @mapping(Power,params=[left,op,right]) Expr ::= Atom @left { '^' @op Expr @right }; " + atom + " }");
        }
        String power = Files.readString(Path.of("src/test/resources/right-associative/Power.ubnf"));
        grammars.add(power.replace("Atom ::= NUMBER;", "@mapping(Number,params=[value]) Atom ::= ['😀'] (NUMBER) @value;"));
        grammars.add(power.replace("Atom ::= NUMBER;", "@mapping(Number,params=[value]) Atom ::= (NUMBER) @value;")
            .replace("Expr ::= Atom @left", "Expr ::= Base @left").replace("  @root", "  Base ::= Atom | '(' Expr ')';\n  @root"));
        String mixed = Files.readString(Path.of("src/test/resources/mixed-values/Mixed.ubnf"));
        grammars.add(mixed);
        grammars.add(mixed.replace(" | OtherRule", "").replace("  @mapping(OtherLeaf, params=[value]) OtherRule ::= 'y' @value;", ""));
        grammars.add(mixed.replace("{ Factor @items }", "{ Factor } @items"));
        grammars.add(mixed.replace("{ Factor @items }", "Items @items").replace("  Factor ::=", "  Items ::= { Factor };\n  Factor ::="));
        grammars.add(mixed.replace("{ Factor @items }", "{ ('a' | LeafRule) @items }"));
        grammars.add(mixed.replace("Document ::= Factor @head ':' [ Factor @maybe ] ':' { Factor @items };",
            "Document ::= Outer @head ':' [ Outer @maybe ] ':' { Outer @items }; Outer ::= '(' Factor ')';"));
        for (boolean reversed : List.of(false, true)) {
            String text = "@mapping(Shared,params=[value]) Text ::= 'a' @value; ";
            String node = "@mapping(Shared,params=[value]) Node ::= Leaf @value; ";
            grammars.add("grammar Shared { @root Entry ::= Text | Node; "
                + (reversed ? node + text : text + node)
                + "@mapping(Leaf,params=[value]) Leaf ::= 'x' @value; }");
        }
        for (String assoc : List.of("leftAssoc", "rightAssoc")) {
            grammars.add("grammar MixedAssoc { @whitespace: javaStyle @root @" + assoc
                + " @precedence(level=10) @mapping(Binary,params=[left,op,right]) "
                + "Expr ::= Factor @left { '+' @op " + (assoc.equals("leftAssoc") ? "Factor" : "Expr") + " @right }; "
                + "Factor ::= 'a' | Leaf; @mapping(Leaf,params=[value]) Leaf ::= 'x' @value; }");
        }
        for (String body : List.of("Leaf Leaf", "Factor Factor", "{ Factor }", "[ Factor ]",
                "{ Outer }", "{ '(' Factor ')' }", "Outer % ','", "('(' Factor ')') % ','",
                "Outer Outer", "[ Outer ] [ Outer ]", "[ '(' Factor ')' ] [ '<' Factor '>' ]", "'(' [ Factor ] ')'")) {
            grammars.add("grammar Cardinality { @root @mapping(Collection,params=[values]) Document ::= Items @values; "
                + "Items ::= " + body + "; Outer ::= '(' Factor ')'; Factor ::= 'a' | '😀' | Leaf; "
                + "@mapping(Leaf,params=[text]) Leaf ::= ('x' | 'y') @text; }");
        }
        for (String ruleName : List.of("_Root", "self")) {
            grammars.add("grammar G { @whitespace: none @root @mapping(Item,params=[value]) " + ruleName + " ::= 'x' @value; }");
        }
        String aliasTemplate = Files.readString(Path.of("src/test/resources/semantic-cardinality/Grammar.ubnf.txt"));
        for (var entry : JsonParser.parseString(Files.readString(Path.of("src/test/resources/semantic-cardinality/pure-alias.json"))).getAsJsonArray()) {
            var fixture = entry.getAsJsonObject();
            grammars.add(aliasTemplate.replace("ROOT_BODY", fixture.has("root") ? fixture.get("root").getAsString() : "Helper @values")
                .replace("HELPER_BODY", fixture.get("body").getAsString())
                .replace("EXTRA_RULES", fixture.has("extra") ? fixture.get("extra").getAsString() : ""));
        }
        var report = new ArrayList<>(List.of("grammar_index\tstatus\tfiles_identical"));
        int index = 0;
        for (String source : grammars) {
            assertTrue(source + "\n" + PortabilityCheck.check(source), PortabilityCheck.check(source).portable());
            Path grammar = temporary.newFile().toPath();
            Files.writeString(grammar, source);
            var portability = command(List.of(binary.toString(), "check", "--target", "rust", "--grammar", grammar.toString()), true);
            assertEquals(source + "\n" + portability.output(), 0, portability.code());
            Path output = temporary.getRoot().toPath().resolve("generated-" + index);
            var generated = command(List.of(binary.toString(), "generate", "--grammar", grammar.toString(), "--output", output.toString()), true);
            assertEquals(source + "\n" + generated.output(), 0, generated.code());
            var expected = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
            assertEquals(5, expected.size());
            for (var file : expected) assertEquals(source + " / " + file.relativePath(), file.content(), Files.readString(output.resolve(file.relativePath())));
            var checked = command(List.of(binary.toString(), "generate", "--grammar", grammar.toString(), "--output", output.toString(), "--check"), true);
            assertEquals(checked.output(), 0, checked.code());
            report.add(index++ + "\taccepted\t5");
        }
        Files.write(Path.of("target/rust-native-generator.tsv"), report, StandardCharsets.UTF_8);
    }

    @Test public void preferredShapeLimitsMatchJavaWithoutEagerFixedPoint() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        assertEquals(0, command(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path binary = repo.resolve("rust/target/debug/unlaxer");
        var report = new ArrayList<>(List.of("case\tfiles_identical\tpreferred_projection"));
        // 非再帰では固定点の上限に関係なく preferred projection を保持する (#737)。
        for (int depth = 84; depth <= 90; depth++) {
            assertGeneratedBytes(binary, "acyclic-plus-" + depth, nestedHelper(depth, "(", ")+"), report);
        }
        // mapped 値のない再帰では、他の規則が固定点の depth 上限を超えても解析を開始しない。
        for (int depth : List.of(60, 86)) {
            assertGeneratedBytes(binary, "text-recursive-plus-" + depth,
                recursiveNestedHelper(depth, "(", ")+", false), report);
        }
        // #737 の旧境界は両側とも受理し、preferred projection を保持する (#754)。
        for (String[] wrapper : List.of(new String[] {"(", ")", "84"},
                new String[] {"(", ")+", "63"}, new String[] {"(", ")?", "50"},
                new String[] {"(", ")*", "50"}, new String[] {"[", "]", "84"},
                new String[] {"{", "}", "84"})) {
            int limit = Integer.parseInt(wrapper[2]);
            for (int depth = limit; depth <= limit + 1; depth++) {
                String source = nestedHelper(depth, wrapper[0], wrapper[1]);
                var grammar = UBNFMapper.parse(source).grammars().get(0);
                var shapes = new RecursiveSemanticShapes(grammar, true);
                assertEquals("#node", shapes.rule("H").kind());
                assertGeneratedBytes(binary, "boundary-" + report.size(), source, report);
            }
        }
        for (boolean textRecursive : List.of(false, true)) {
            String source = workHelper(textRecursive);
            var workGrammar = UBNFMapper.parse(source).grammars().get(0);
            var workError = assertThrows(IllegalArgumentException.class,
                () -> new RecursiveSemanticShapes(workGrammar, true).rule("C0"));
            assertEquals("E-RECURSIVE-SHAPE: analysis work exceeds 1000000", workError.getMessage());
            assertGeneratedBytes(binary, (textRecursive ? "text-recursive" : "acyclic") + "-work-80-700",
                source, report);
        }
        Files.write(Path.of("target/rust-preferred-shape-limits.tsv"), report, StandardCharsets.UTF_8);
    }

    @Test public void preferredShapeLimitsMatchJavaWithMappedRecursion() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        assertEquals(0, command(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path binary = repo.resolve("rust/target/debug/unlaxer");
        var report = new ArrayList<>(List.of("case\tfiles_identical\tpreferred_projection"));
        // T が mapped 値を含む再帰なので、両 host の生成経路で固定点の上限を検証する。
        for (String[] wrapper : List.of(new String[] {"(", ")", "84", "paren"},
                new String[] {"(", ")+", "63", "plus"}, new String[] {"(", ")?", "50", "optional"},
                new String[] {"(", ")*", "50", "star"}, new String[] {"[", "]", "84", "bracket"},
                new String[] {"{", "}", "84", "brace"})) {
            int limit = Integer.parseInt(wrapper[2]);
            for (int depth = limit; depth <= limit + 1; depth++) {
                assertGeneratedBytes(binary, "mapped-recursive-" + wrapper[3] + "-" + depth,
                    recursiveNestedHelper(depth, wrapper[0], wrapper[1], true), true, report);
            }
        }
        // #754 review: the new boundary, where generation still succeeds but the projection flips.
        // Java-target generation succeeds exactly when both Rust hosts keep the projection.
        for (String[] wrapper : List.of(new String[] {"(", ")+", "85", "plus"},
                new String[] {"(", ")?", "63", "optional"}, new String[] {"(", ")*", "63", "star"},
                new String[] {"(", ")", "127", "paren"}, new String[] {"[", "]", "127", "bracket"},
                new String[] {"{", "}", "127", "brace"})) {
            int limit = Integer.parseInt(wrapper[2]);
            // Plain groups keep the projection up to the native frontend's last accepted depth.
            int last = limit == 127 ? limit : limit + 1;
            for (int depth = limit; depth <= last; depth++) {
                String source = recursiveNestedHelper(depth, wrapper[0], wrapper[1], true);
                assertGeneratedBytes(binary, "mapped-recursive-new-" + wrapper[3] + "-" + depth,
                    source, depth == limit, report);
                var grammar = UBNFMapper.parse(source).grammars().get(0);
                var shapes = new RecursiveSemanticShapes(grammar, true);
                if (depth == limit) {
                    assertEquals("#node", shapes.rule("H").kind());
                    new org.unlaxer.dsl.codegen.ParserGenerator().generate(grammar);
                    new org.unlaxer.dsl.codegen.MapperGenerator().generate(grammar);
                } else {
                    assertEquals("E-RECURSIVE-SHAPE: expression depth exceeds 256",
                        assertThrows(IllegalArgumentException.class, () -> shapes.rule("H")).getMessage());
                    // ( )+ reaches the left-recursion analysis limit first; Java generation fails either way.
                    assertThrows(IllegalArgumentException.class,
                        () -> new org.unlaxer.dsl.codegen.ParserGenerator().generate(grammar));
                }
            }
        }
        Files.write(Path.of("target/rust-preferred-recursive-shape-limits.tsv"), report, StandardCharsets.UTF_8);
    }

    /** #754: a singleton choice consumes work, but adds no expression depth. */
    @Test public void semanticShapeDepthStillRejectsDeepExpressions() {
        // Real choices inside and outside the groups leave the native frontend below 128 body levels.
        // These same seven boundaries are checked in preferred_plan.rs.
        for (String[] wrapper : List.of(new String[] {"(", ")", "126"},
                new String[] {"(", ")+", "84"}, new String[] {"(", ")?", "63"},
                new String[] {"(", ")*", "63"}, new String[] {"[", "]", "126"},
                new String[] {"{", "}", "126"}, new String[] {"('b' | ", ")", "84"})) {
            int limit = Integer.parseInt(wrapper[2]);
            for (int depth = limit; depth <= limit + 1; depth++) {
                var grammar = UBNFMapper.parse(nestedHelper(depth, wrapper[0], wrapper[1])
                    .replace("'a' M", "'a' M | 'b'")
                    .replace("; @mapping(NM)", " | 'c'; @mapping(NM)")).grammars().get(0);
                var shapes = new RecursiveSemanticShapes(grammar, true);
                if (depth == limit) assertEquals("#join", shapes.rule("H").kind());
                else assertEquals("E-RECURSIVE-SHAPE: expression depth exceeds 256",
                    assertThrows(IllegalArgumentException.class, () -> shapes.rule("H")).getMessage());
            }
        }
    }

    @Test public void javaGenerationDepthIsBoundedOnAColdOneMegabyteStack() throws Exception {
        var result = command(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xss1m", "-Xint", "-cp", System.getProperty("java.class.path"),
            RustNativeGeneratorTest.class.getName()), false);
        assertEquals(result.output(), 0, result.code());
        assertTrue(result.output(), result.output().contains("java-generation-depth: accepted=126 rejected=127"));
    }

    /** Separate cold VM: the semantic traversal and fixed point share a bounded native stack. */
    public static void main(String[] args) {
        var generators = List.of(new org.unlaxer.dsl.codegen.ParserGenerator(),
            new org.unlaxer.dsl.codegen.ASTGenerator(), new org.unlaxer.dsl.codegen.MapperGenerator());
        for (int depth : List.of(84, 85, 121, 122, 126, 127)) {
            var grammar = UBNFMapper.parse(NestingLimitGrammars.recursivePlus(depth)).grammars().get(0);
            for (var generator : generators) {
                if (depth <= 126) generator.generate(grammar);
                else assertEquals("E-RECURSIVE-SHAPE: expression depth exceeds 256",
                    assertThrows(IllegalArgumentException.class, () -> generator.generate(grammar)).getMessage());
            }
        }
        // Include the caller's rule-reference traversal beneath the fixed-point traversal (#740).
        var combined = UBNFMapper.parse(NestingLimitGrammars.chainBesideNestedRecursion(82, 126)).grammars().get(0);
        for (var generator : generators) generator.generate(combined);
        System.out.println("java-generation-depth: accepted=126 rejected=127");
    }

    /**
     * #740: the 84..130 sweep of recursive helpers in groups. Both hosts accept up to 122 groups
     * with identical files and reject 123 with the same structural depth diagnostic. From the
     * native frontend's 128 body levels on, native rejects before lowering, while the Java
     * frontend (no body counter, #741) still reaches the lowering depth diagnostic.
     */
    @Test public void recursiveNestingLimitsMatchJavaHost() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        assertEquals(0, command(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path binary = repo.resolve("rust/target/debug/unlaxer");
        var report = new ArrayList<>(List.of("case\tjava\tnative"));
        // The separated operand of T is one body level deeper than the groups of H.
        record Shape(String name, java.util.function.IntFunction<String> grammar, int frontendLimit) {}
        for (var shape : List.of(new Shape("plus", NestingLimitGrammars::recursivePlus, NestingLimitGrammars.NATIVE_BODY_LEVELS),
                new Shape("separator", NestingLimitGrammars::recursiveSeparator, NestingLimitGrammars.NATIVE_BODY_LEVELS - 1),
                new Shape("number", NestingLimitGrammars::recursivePlusWithNumber, NestingLimitGrammars.NATIVE_BODY_LEVELS))) {
            for (int depth = 84; depth <= 130; depth++) {
                String name = shape.name() + "-" + depth, source = shape.grammar().apply(depth);
                Path grammar = temporary.newFile(name + ".ubnf").toPath();
                Files.writeString(grammar, source);
                Path output = temporary.getRoot().toPath().resolve(name);
                var nativeRun = command(List.of(binary.toString(), "generate", "--grammar", grammar.toString(),
                    "--output", output.toString()), true);
                String javaError = null;
                List<RustBackend.GeneratedFile> expected = List.of();
                try {
                    expected = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
                } catch (IllegalArgumentException error) {
                    javaError = error.getMessage();
                }
                if (depth <= NestingLimitGrammars.LOWERED) {
                    assertNull(name, javaError);
                    // #754: Java-target generation must accept every grammar accepted by Rust.
                    var javaGrammar = UBNFMapper.parse(source).grammars().get(0);
                    new org.unlaxer.dsl.codegen.ParserGenerator().generate(javaGrammar);
                    new org.unlaxer.dsl.codegen.ASTGenerator().generate(javaGrammar);
                    new org.unlaxer.dsl.codegen.MapperGenerator().generate(javaGrammar);
                    assertEquals(name + "\n" + nativeRun.output(), 0, nativeRun.code());
                    assertEquals(name, 5, expected.size());
                    for (var file : expected) assertArrayEquals(name + " / " + file.relativePath(),
                        file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output.resolve(file.relativePath())));
                    assertTrue(name + ": preferred projection", expected.stream()
                        .filter(file -> file.relativePath().equals("parser.rs"))
                        .anyMatch(file -> file.content().contains("Some(__preferred_cst::grammar())")));
                    report.add(name + "\taccepted\taccepted");
                    continue;
                }
                assertEquals(name, "Rust subset: structural analysis depth exceeds 256", javaError);
                assertNotEquals(name, 0, nativeRun.code());
                String expectedNative = depth < shape.frontendLimit() ? "structural analysis depth exceeds 256"
                    : "UBNF nesting limit exceeded";
                assertTrue(name + ": " + nativeRun.output(), nativeRun.output().strip().endsWith(expectedNative));
                report.add(name + "\t" + javaError + "\t" + nativeRun.output().strip());
            }
        }
        // #740 review: the Java host accepted these before #740; native now accepts them too.
        var accepted = new java.util.LinkedHashMap<>(NestingLimitGrammars.acceptedBefore740());
        accepted.put("deepest-analysis", NestingLimitGrammars.chainBesideNestedRecursion(82, NestingLimitGrammars.LOWERED));
        for (var entry : accepted.entrySet()) {
            String name = "accepted-" + entry.getKey();
            Path grammar = temporary.newFile(name + ".ubnf").toPath();
            Files.writeString(grammar, entry.getValue());
            Path output = temporary.getRoot().toPath().resolve(name);
            var nativeRun = command(List.of(binary.toString(), "generate", "--grammar", grammar.toString(),
                "--output", output.toString()), true);
            assertEquals(name + "\n" + nativeRun.output(), 0, nativeRun.code());
            var expected = new RustBackend().generate(UBNFMapper.parse(entry.getValue()).grammars().get(0));
            assertEquals(name, 5, expected.size());
            for (var file : expected) assertArrayEquals(name + " / " + file.relativePath(),
                file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output.resolve(file.relativePath())));
            report.add(name + "\taccepted\taccepted");
        }
        Files.write(Path.of("target/rust-recursive-nesting-limits.tsv"), report, StandardCharsets.UTF_8);
    }

    /** #805: debug CLI on a 2 MiB main stack, using Java's existing byte oracle. */
    @Test public void nativeGenerationFitsTwoMegabyteStack() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        assumeTrue("requires bash ulimit", Files.isExecutable(Path.of("/bin/bash")));
        assertEquals(0, command(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        // Positional arguments keep file names out of shell source. PATH remains empty.
        var generator = List.of("/bin/bash", "-c", "set -e; ulimit -c 0; ulimit -s 2048; exec \"$@\"",
            "stack-805", repo.resolve("rust/target/debug/unlaxer").toString());
        var report = new ArrayList<>(List.of("case\tfiles_identical\tpreferred"));
        for (String[] wrapper : List.of(new String[] {"[", "]", "127", "bracket"},
                new String[] {"(", ")?", "63", "optional"}, new String[] {"(", ")", "127", "group"},
                new String[] {"(", ")+", "85", "plus"}, new String[] {"(", ")*", "63", "star"},
                new String[] {"{", "}", "127", "brace"})) {
            for (int depth : List.of(0, 62, 63, 64, 84, 85, 86, 93, 94, 95, 126, 127)) {
                if (wrapper[2].equals("63") && depth > 84) {
                    String source = recursiveNestedHelper(depth, wrapper[0], wrapper[1], true);
                    var parsed = UBNFMapper.parse(source).grammars().get(0);
                    assertEquals("Rust subset: structural analysis depth exceeds 256",
                        assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(parsed)).getMessage());
                    Path grammar = temporary.newFile().toPath();
                    Files.writeString(grammar, source);
                    Path output = temporary.getRoot().toPath().resolve("rejected-" + wrapper[3] + "-" + depth);
                    var args = new ArrayList<>(generator);
                    args.addAll(List.of("generate", "--grammar", grammar.toString(), "--output", output.toString()));
                    var rejected = command(args, true);
                    assertEquals(rejected.output(), 3, rejected.code());
                    assertEquals("structural analysis depth exceeds 256", rejected.output().strip());
                    assertFalse("no artifacts after a resource limit", Files.exists(output));
                    report.add("small-stack-" + wrapper[3] + "-" + depth + "\t0\tstructural analysis depth exceeds 256");
                    continue;
                }
                assertGeneratedBytes(generator, "small-stack-" + wrapper[3] + "-" + depth,
                    recursiveNestedHelper(depth, wrapper[0], wrapper[1], true),
                    depth <= Integer.parseInt(wrapper[2]), report);
            }
            Path grammar = temporary.newFile().toPath();
            Files.writeString(grammar, recursiveNestedHelper(128, wrapper[0], wrapper[1], true));
            Path output = temporary.getRoot().toPath().resolve("rejected-" + wrapper[3]);
            var args = new ArrayList<>(generator);
            args.addAll(List.of("generate", "--grammar", grammar.toString(), "--output", output.toString()));
            var rejected = command(args, true);
            assertEquals(rejected.output(), 3, rejected.code());
            assertTrue(rejected.output(), rejected.output().strip().endsWith("UBNF nesting limit exceeded"));
            assertFalse("no artifacts after a resource limit", Files.exists(output));
            report.add("small-stack-" + wrapper[3] + "-128\t0\tUBNF nesting limit exceeded");
        }
        Files.write(Path.of("target/rust-native-small-stack.tsv"), report, StandardCharsets.UTF_8);
    }

    private static String workHelper(boolean textRecursive) {
        var source = new StringBuilder("grammar Pbt { @package: pbt.corpus @root @mapping(N0, params=[x"
            + (textRecursive ? ", g]) R0 ::= (NM2 NM2) @g T 'x' C0 @x" : "]) R0 ::= 'x' C0 @x"));
        for (int i = 0; i < 700; i++) source.append(" | 'z' F").append(i);
        source.append("; ");
        for (int i = 0; i < 80; i++) source.append("C").append(i).append(" ::= ")
            .append(textRecursive ? "'a' C" + (i + 1) : i == 79 ? "M" : "C" + (i + 1)).append("; ");
        source.append(textRecursive ? "C80 ::= 'm'; " : "@mapping(NM) M ::= 'm'; ");
        for (int i = 0; i < 700; i++) {
            source.append("F").append(i).append(" ::= ");
            for (char c = 'a'; c <= 't'; c++) source.append("'").append(c).append("' ");
            source.append("; ");
        }
        if (textRecursive) source.append("T ::= 'n' T | 'a'; @mapping(NN) NM2 ::= 'k'; ");
        return source.append("}").toString();
    }

    private static String nestedHelper(int depth, String open, String close) {
        return "grammar Pbt { @package: pbt.corpus @root @mapping(N0, params=[x]) "
            + "R0 ::= 'x' H @x; H ::= " + open.repeat(depth) + "'a' M" + close.repeat(depth)
            + "; @mapping(NM) M ::= 'm'; }";
    }

    private static String recursiveNestedHelper(int depth, String open, String close, boolean mapped) {
        return nestedHelper(depth, open, close).replace("R0 ::= 'x' H @x;",
            "R0 ::= (M M) @x T H; T ::= 'n' T" + (mapped ? " M" : "") + " | 'a';");
    }

    private void assertGeneratedBytes(Path binary, String name, String source, List<String> report) throws Exception {
        assertGeneratedBytes(binary, name, source, true, report);
    }

    /** #758: irrelevant rules must not make integer refinement run Java's AST fixed point. */
    @Test public void numericAnalysisWorkMatchesNative() throws Exception {
        assumeTrue("enable with -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        assertEquals(0, command(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path binary = repo.resolve("rust/target/debug/unlaxer");
        var report = new ArrayList<>(List.of("case\toutcome\tfiles_identical"));
        assertAnalysisWorkMatchesNative(binary, "numeric-work-", NestingLimitGrammars::numericBesideAnalysisWork, report);
        // @typeof refines its captures with the same rule, so it must not run the AST fixed point either.
        assertAnalysisWorkMatchesNative(binary, "typeof-work-", NestingLimitGrammars::typeofBesideAnalysisWork, report);
        Files.write(Path.of("target/rust-numeric-analysis-work.tsv"), report, StandardCharsets.UTF_8);
    }

    private void assertAnalysisWorkMatchesNative(Path binary, String prefix,
            java.util.function.IntFunction<String> grammars, List<String> report) throws Exception {
        for (int fillers : List.of(0, 500, 550, 560, 575, 580, 600, 700)) {
            String name = prefix + fillers;
            String source = grammars.apply(fillers);
            Path grammar = temporary.newFile(name + ".ubnf").toPath();
            Files.writeString(grammar, source);
            Path output = temporary.getRoot().toPath().resolve(name);
            var nativeRun = command(List.of(binary.toString(), "generate", "--grammar", grammar.toString(),
                "--output", output.toString()), true);
            var parsed = UBNFMapper.parse(source).grammars().get(0);
            if (fillers >= 580) {
                // The shared lowered-IR work limit still rejects genuinely excessive analysis.
                var error = assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(parsed));
                assertEquals("E-RECURSIVE-SHAPE: analysis work exceeds 1000000", error.getMessage());
                assertNotEquals(name, 0, nativeRun.code());
                assertEquals(name, error.getMessage(), nativeRun.output().strip());
                report.add(name + "\trejected\t0");
                continue;
            }
            assertEquals(name + "\n" + nativeRun.output(), 0, nativeRun.code());
            var expected = new RustBackend().generate(parsed);
            assertEquals(name, 5, expected.size());
            for (var file : expected) assertArrayEquals(name + " / " + file.relativePath(),
                file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output.resolve(file.relativePath())));
            String ast = Files.readString(output.resolve("ast.rs"));
            assertTrue(name + "\n" + ast, ast.contains("r#n: i32"));
            report.add(name + "\taccepted\t5");
        }
    }

    private void assertGeneratedBytes(Path binary, String name, String source, boolean preferred, List<String> report) throws Exception {
        assertGeneratedBytes(List.of(binary.toString()), name, source, preferred, report);
    }

    private void assertGeneratedBytes(List<String> generator, String name, String source, boolean preferred, List<String> report) throws Exception {
        Path grammar = temporary.newFile(name + ".ubnf").toPath();
        Files.writeString(grammar, source);
        Path output = temporary.getRoot().toPath().resolve(name);
        var args = new ArrayList<>(generator);
        args.addAll(List.of("generate", "--grammar", grammar.toString(), "--output", output.toString()));
        var generated = command(args, true);
        assertEquals(name + "\n" + generated.output(), 0, generated.code());
        var expected = new RustBackend().generate(UBNFMapper.parse(source).grammars().get(0));
        assertEquals(name, 5, expected.size());
        for (var file : expected) assertArrayEquals(name + " / " + file.relativePath(),
            file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output.resolve(file.relativePath())));
        var parser = expected.stream().filter(file -> file.relativePath().equals("parser.rs")).findFirst().orElseThrow();
        assertEquals(name + ": preferred projection", preferred, parser.content().contains("#preferred-depth:"));
        assertEquals(name + ": mapping_grammar Some", preferred, parser.content().contains("Some(__preferred_cst::grammar())"));
        assertEquals(name + ": mapping_grammar None", !preferred,
            parser.content().contains("pub fn mapping_grammar() -> Option<&'static SharedGrammar> { None }"));
        report.add(name + "\t5\t" + (preferred ? "Some" : "None"));
    }

    private record Result(int code, String output) {}
    private Result command(List<String> args, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = org.unlaxer.dsl.TestProcesses.builder(args).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        var process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("process timed out: " + args); }
        return new Result(process.exitValue(), Files.readString(log));
    }
}
