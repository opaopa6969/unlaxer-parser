package org.unlaxer.dsl.codegen.propertybased;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.TokenPrinter;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.AutoCommit;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.ASTGenerator;
import org.unlaxer.dsl.codegen.CodeGenerator;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.MapperGenerator;
import org.unlaxer.dsl.codegen.ParserGenerator;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/**
 * Differential fuzz of {@code @autoCommit} under the built-in {@code @whitespace: javaStyle} (#728).
 *
 * <p>Each seed of {@link GrammarGenerator} is rendered with {@code @whitespace: javaStyle}, once as
 * written ("plain") and once with {@code @autoCommit} on every rule ("auto"). Seeds whose rewrite
 * inserts no {@code COMMIT} are skipped until {@code acfuzz.grammars} grammars with insertions are
 * collected. Inputs are the generator's derivations and mutations, plus variants with spaces,
 * newlines, line and block comments, and lone {@code /} inserted at random code points.
 *
 * <p>For every input and the 6 memo × diagnostics options, the plain and the auto grammar must
 * agree on acceptance, and accepted inputs on the CST and the AST (Java parsers; with
 * {@code -DrustConformance=true} also native Rust and the Java-host Rust backend, whose generated
 * files must be byte-identical). The Rust part also compares the lint and warnings of the Java and
 * native frontends on every grammar. Larger runs:
 * {@code -Dacfuzz.seed=1000 -Dacfuzz.grammars=200 -Dacfuzz.variants=6}.
 */
public class AutoCommitWhitespaceFuzzTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    static final long CI_FIRST_SEED = 1;
    /** Grammars with at least one inserted commit in a CI run. */
    static final int CI_GRAMMARS = 24;
    static final int CI_VARIANTS = 3;
    static final int DERIVATIONS = 4, MUTATIONS = 7, RESERVE = 6;
    /** Trivia of javaStyle, and fragments that look like it but are not. */
    static final String[] TRIVIA = {" ", "\n", "\t", "\r\n", "  ", "/* c */", "/**/", "// c\n", "//", "/", "/*", "*/"};
    static final List<Memoization> MEMO = List.of(Memoization.OFF, Memoization.SAFE_FAILURES);
    static final List<ParseOptions.Diagnostics> DIAGNOSTICS = List.of(ParseOptions.Diagnostics.values());

    private static long longProperty(String name, long fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.strip());
    }

    /** One seed: the plain and the annotated grammar, their inputs and the inserted commit count. */
    record Pair(long seed, String plain, String auto, GrammarDecl plainGrammar, GrammarDecl autoGrammar,
            List<String> inputs, int commits) {}

    static String spaced(String rendered) {
        String text = rendered.replace("  @whitespace: none\n", "");
        int at = text.indexOf('\n', text.indexOf("@package:")) + 1;
        return text.substring(0, at) + "  @whitespace: javaStyle\n" + text.substring(at);
    }

    /**
     * The generator's literals never start trivia. Two thirds of the seeds rename code points so
     * that literals, lookaheads and inputs also contain {@code /}, {@code *} and a space, the code
     * points that a bare terminal can take from a comment or from skippable space.
     */
    static String alphabet(long seed, String text, boolean grammar) {
        String[][] renames = seed % 3 == 1 ? new String[][] {{"y", "/"}, {",", "*"}}
            : seed % 3 == 2 ? new String[][] {{"y", "/"}, {"x", " "}} : new String[0][];
        String out = text;
        for (String[] rename : renames) {
            out = grammar ? out.replace("'" + rename[0] + "'", "'" + rename[1] + "'") : out.replace(rename[0], rename[1]);
        }
        return out;
    }

    static String annotated(String plain) {
        return plain.replaceAll("\\bR(\\d+) ::=", "@autoCommit R$1 ::=");
    }

    static List<String> inputs(long seed, GrammarGenerator generator, GrammarModel model, int variants) {
        var bases = new ArrayList<String>();
        for (String input : generator.inputs(model, DERIVATIONS, MUTATIONS)) bases.add(alphabet(seed, input, false));
        for (List<String> group : generator.reserve(model, RESERVE, 1)) {
            for (String input : group) bases.add(alphabet(seed, input, false));
        }
        var random = new Random(seed * 7_281L + 728L);
        var out = new LinkedHashSet<String>(bases);
        for (String base : bases) {
            for (int v = 0; v < variants; v++) {
                List<String> cps = new ArrayList<>();
                base.codePoints().forEach(cp -> cps.add(new String(Character.toChars(cp))));
                int insertions = 1 + random.nextInt(3);
                for (int i = 0; i < insertions; i++) {
                    cps.add(random.nextInt(cps.size() + 1), TRIVIA[random.nextInt(TRIVIA.length)]);
                }
                out.add(String.join("", cps));
            }
            out.add(" " + base + "\n");
        }
        return List.copyOf(out);
    }

    static int insertedCommits(String source) {
        return AutoCommit.lint(source).stream().filter(AutoCommit.Finding::inserted).mapToInt(f -> f.commits().size()).sum();
    }

    private static boolean accepted(GrammarDecl grammar) {
        try {
            if (GrammarValidator.validateWithoutClassLoading(grammar).stream().anyMatch(i -> "ERROR".equals(i.severity()))) return false;
            new RustBackend().generate(grammar);
            for (CodeGenerator g : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) g.generate(grammar);
            return true;
        } catch (RuntimeException | AssertionError error) {
            return false;
        }
    }

    /** Collects the seeds whose annotated grammar inserts at least one commit. */
    static final class Corpus {
        final List<Pair> pairs = new ArrayList<>();
        int seeds, rejected, withoutCommits, commits, inputs;
    }

    static Corpus corpus(long first, int grammars, int variants) {
        var corpus = new Corpus();
        for (long seed = first; corpus.pairs.size() < grammars && seed < first + 20L * grammars; seed++) {
            corpus.seeds++;
            var generator = new GrammarGenerator(seed, GrammarGenerator.Limits.CI);
            GrammarModel model = generator.grammar();
            String plain = alphabet(seed, spaced(model.render("P" + seed, "acfuzz.p" + seed)), true);
            String auto = annotated(alphabet(seed, spaced(model.render("A" + seed, "acfuzz.a" + seed)), true));
            GrammarDecl plainGrammar, autoGrammar;
            try {
                plainGrammar = UBNFMapper.parse(plain).grammars().get(0);
            } catch (RuntimeException error) {
                corpus.rejected++;
                continue;
            }
            if (!accepted(plainGrammar)) {
                corpus.rejected++;
                continue;
            }
            // The rewrite never makes a grammar the frontend and the generators accept invalid.
            autoGrammar = UBNFMapper.parse(auto).grammars().get(0);
            assertTrue("seed " + seed + " auto grammar rejected:\n" + auto, accepted(autoGrammar));
            assertTrue("seed " + seed + " no annotation, no rewrite", AutoCommit.apply(plainGrammar) == plainGrammar);
            int commits = insertedCommits(auto);
            if (commits == 0) {
                corpus.withoutCommits++;
                continue;
            }
            var list = inputs(seed, generator, model, variants);
            corpus.pairs.add(new Pair(seed, plain, auto, plainGrammar, autoGrammar, list, commits));
            corpus.commits += commits;
            corpus.inputs += list.size();
        }
        assertEquals("grammars with inserted commits", grammars, corpus.pairs.size());
        return corpus;
    }

    /** acceptance, CST and AST of accepted inputs; kind and offset of rejected ones. */
    static final class Tally {
        long observations, accepted, acceptedWithTrivia, commitRejections;
        final List<String> differences = new ArrayList<>();

        void compare(String label, Pair pair, List<String> plain, List<String> auto) {
            assertEquals(label + " seed " + pair.seed(), plain.size(), auto.size());
            for (int i = 0; i < plain.size(); i++) {
                String p = plain.get(i), a = auto.get(i);
                String input = pair.inputs().get(i / (MEMO.size() * DIAGNOSTICS.size()));
                observations++;
                boolean ok = p.startsWith("ok\t");
                if (ok != a.startsWith("ok\t") || ok && !p.equals(a)) {
                    if (differences.size() < 20) {
                        differences.add(label + " seed " + pair.seed() + " input " + quote(input) + " option " + i % 6
                            + "\n  plain: " + p + "\n  auto:  " + a + "\n" + pair.auto());
                    }
                }
                if (ok) accepted++;
                if (ok && hasTrivia(input)) acceptedWithTrivia++;
                if (a.startsWith("fail\tcommit\t") && !p.startsWith("fail\tcommit\t")) commitRejections++;
            }
        }

        @Override public String toString() {
            return "observations=" + observations + " accepted=" + accepted + " acceptedWithTrivia=" + acceptedWithTrivia
                + " commitRejections=" + commitRejections
                + " differences=" + differences.size();
        }
    }

    /** Whether the input has javaStyle trivia: a space character or the start of a comment (a lone {@code /} is not). */
    static boolean hasTrivia(String input) {
        return input.chars().anyMatch(c -> " \t\n\u000B\u000C\r".indexOf(c) >= 0) || input.contains("//") || input.contains("/*");
    }

    static String quote(String input) {
        return '"' + input.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + '"';
    }

    @Test public void annotatedGrammarsAcceptTheSameInputsWithTheSameTreesInJava() throws Exception {
        var corpus = corpus(longProperty("acfuzz.seed", CI_FIRST_SEED), (int) longProperty("acfuzz.grammars", CI_GRAMMARS),
            (int) longProperty("acfuzz.variants", CI_VARIANTS));
        var units = new ArrayList<JavaFileObject>();
        for (Pair pair : corpus.pairs) {
            for (GrammarDecl grammar : List.of(pair.plainGrammar(), pair.autoGrammar())) {
                for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
                    var generated = generator.generate(grammar);
                    units.add(source(generated.packageName() + "." + generated.className(), generated.source()));
                }
            }
        }
        Path classes = temporary.newFolder().toPath();
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "21", "-nowarn", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                null, units).call());
        }
        var tally = new Tally();
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (Pair pair : corpus.pairs) {
                var plain = runJava(loader, "acfuzz.p" + pair.seed(), "P" + pair.seed(), pair.inputs());
                var auto = runJava(loader, "acfuzz.a" + pair.seed(), "A" + pair.seed(), pair.inputs());
                tally.compare("Java", pair, plain, auto);
            }
        }
        System.out.println("[acfuzz] Java seeds=" + corpus.seeds + " grammars=" + corpus.pairs.size() + " rejected=" + corpus.rejected
            + " withoutCommits=" + corpus.withoutCommits + " commits=" + corpus.commits + " inputs=" + corpus.inputs + " " + tally);
        assertTrue(String.join("\n", tally.differences), tally.differences.isEmpty());
        assertTrue("accepted inputs with trivia must be exercised", tally.acceptedWithTrivia > 0);
        assertTrue("inserted commits must reject some inputs", tally.commitRejections > 0);
    }

    private static List<String> runJava(ClassLoader loader, String pkg, String name, List<String> inputs) throws Exception {
        var parser = (Parser) loader.loadClass(pkg + "." + name + "Parsers").getMethod("getRootParser").invoke(null);
        var mapper = loader.loadClass(pkg + "." + name + "Mapper");
        var diagnose = mapper.getMethod("diagnose", String.class, ParseOptions.class);
        var parse = mapper.getMethod("parseWithOptions", String.class, ParseOptions.class);
        List<String> lines = new ArrayList<>();
        for (String input : inputs) {
            for (var memo : MEMO) for (var mode : DIAGNOSTICS) {
                var options = ParseOptions.withMemoization(memo).withDiagnostics(mode);
                var diagnostic = (Optional<?>) diagnose.invoke(null, input, options);
                if (diagnostic.isPresent()) {
                    Object record = diagnostic.get();
                    lines.add("fail\t" + record.getClass().getMethod("kind").invoke(record) + "\t"
                        + record.getClass().getMethod("offset").invoke(record));
                    continue;
                }
                String cst;
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                    var parsed = parser.parse(context);
                    cst = parsed.isSucceeded() ? TokenPrinter.get(parsed.getRootToken()).replace("\n", "|") : "?";
                }
                // Class names carry the grammar name; the two variants differ only there.
                lines.add(("ok\t" + cst + "\t" + parse.invoke(null, input, options)).replace(pkg + ".", "").replace(name, "G"));
            }
        }
        return lines;
    }

    @Test public void annotatedGrammarsAgreeOnBothRustHostsAndTheLint() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] AutoCommitWhitespaceFuzzTest Rust hosts require -DrustConformance=true");
        }
        assumeTrue(Boolean.getBoolean("rustConformance"));
        var corpus = corpus(longProperty("acfuzz.seed", CI_FIRST_SEED), (int) longProperty("acfuzz.grammars", CI_GRAMMARS),
            (int) longProperty("acfuzz.variants", CI_VARIANTS));
        Path work = temporary.newFolder().toPath();
        run(List.of("cargo", "build", "--locked", "--quiet", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), "", 600);
        Path generator = repo.resolve("rust/target/debug/unlaxer");
        Path runtime = work.resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()), "", 600);

        // 1. Both frontends report the same lint and warnings on every grammar, annotated or not.
        Path ubnf = work.resolve("libunlaxer_ubnf.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_ubnf",
            repo.resolve("rust/unlaxer-ubnf/src/lib.rs").toString(), "-o", ubnf.toString()), "", 600);
        Path lintProbe = work.resolve("lint-probe.rs");
        Files.writeString(lintProbe, LINT_PROBE);
        run(List.of("rustc", "--edition=2021", "-A", "warnings", lintProbe.toString(), "--extern", "unlaxer_ubnf=" + ubnf,
            "-o", work.resolve("lint-probe").toString()), "", 600);
        var lintInput = new StringBuilder();
        var javaLint = new StringBuilder();
        for (Pair pair : corpus.pairs) {
            for (String source : List.of(pair.plain(), pair.auto())) {
                lintInput.append(HexFormat.of().formatHex(source.getBytes(StandardCharsets.UTF_8))).append('\n');
                javaLint.append(javaLint(source));
            }
        }
        assertEquals("lint and warnings of the Java and native frontends", javaLint.toString(),
            run(List.of(work.resolve("lint-probe").toString()), lintInput.toString(), 300));

        // 2. Native and Java-host generation are byte-identical; one crate holds every grammar.
        var main = new StringBuilder(RUST_PROBE);
        var dispatch = new StringBuilder("fn dispatch(index: usize, input: &str) {\n    match index {\n");
        int index = 0;
        for (Pair pair : corpus.pairs) {
            for (String variant : List.of("p", "a")) {
                String source = variant.equals("p") ? pair.plain() : pair.auto();
                GrammarDecl grammar = variant.equals("p") ? pair.plainGrammar() : pair.autoGrammar();
                Path dir = Files.createDirectories(work.resolve(variant + pair.seed()));
                Files.writeString(dir.resolve("grammar.ubnf"), source);
                run(List.of(generator.toString(), "generate", "--grammar", dir.resolve("grammar.ubnf").toString(),
                    "--output", dir.resolve("native").toString()), "", 120);
                for (var file : new RustBackend().generate(grammar)) {
                    assertEquals("seed " + pair.seed() + " " + variant + " " + file.relativePath(), file.content(),
                        Files.readString(dir.resolve("native").resolve(file.relativePath())));
                }
                main.append("#[path=\"").append(variant).append(pair.seed()).append("/native/mod.rs\"] mod g").append(index).append(";\n");
                dispatch.append("        ").append(index).append(" => observe(input, g").append(index).append("::parser::parse_tree_detailed_with_options, |t| g")
                    .append(index).append("::mapper::map(t).map(|a| a.canonical_json()).map_err(|e| e.to_string())),\n");
                index++;
            }
        }
        main.append(dispatch).append("        _ => panic!(\"unknown grammar\"),\n    }\n}\n");
        Path probe = work.resolve("probe.rs");
        Files.writeString(probe, main);
        run(List.of("rustc", "--edition=2021", "-A", "warnings", "--extern", "unlaxer_runtime=" + runtime, probe.toString(),
            "-o", work.resolve("probe").toString()), "", 900);

        // 3. Same comparison as the Java test, on the native Rust parsers.
        var rows = new StringBuilder();
        index = 0;
        for (Pair pair : corpus.pairs) {
            for (int variant = 0; variant < 2; variant++, index++) {
                for (String input : pair.inputs()) {
                    rows.append(index).append('\t').append(HexFormat.of().formatHex(input.getBytes(StandardCharsets.UTF_8))).append('\n');
                }
            }
        }
        List<String> output = run(List.of(work.resolve("probe").toString()), rows.toString(), 900).lines().toList();
        var tally = new Tally();
        int at = 0;
        for (Pair pair : corpus.pairs) {
            int count = pair.inputs().size() * MEMO.size() * DIAGNOSTICS.size();
            List<String> plain = output.subList(at, at + count), auto = output.subList(at + count, at + 2 * count);
            at += 2 * count;
            tally.compare("Rust", pair, plain, auto);
        }
        assertEquals("probe lines", output.size(), at);
        System.out.println("[acfuzz] Rust grammars=" + corpus.pairs.size() + " commits=" + corpus.commits + " inputs=" + corpus.inputs + " " + tally);
        assertTrue(String.join("\n", tally.differences), tally.differences.isEmpty());
        assertTrue("accepted inputs with trivia must be exercised", tally.acceptedWithTrivia > 0);
        assertTrue("inserted commits must reject some inputs", tally.commitRejections > 0);
    }

    /** The lint of one source in the format {@link #LINT_PROBE} prints. */
    static String javaLint(String source) {
        var out = new StringBuilder();
        for (var finding : AutoCommit.lint(source)) {
            var span = finding.span().orElseThrow();
            out.append("F\t").append(finding.rule()).append('\t').append(span.start()).append("..").append(span.end())
                .append('\t').append(finding.cut()).append('\t');
            var commits = new ArrayList<String>();
            for (var commit : finding.commits()) {
                var at = commit.span().orElseThrow();
                commits.add(commit.name() + "@" + at.start() + ".." + at.end());
            }
            out.append(String.join(",", commits)).append('\t').append(finding.inserted()).append('\n');
        }
        for (var warning : AutoCommit.lintWarnings(source)) {
            var span = warning.span().orElseThrow();
            out.append("W\t").append(warning.rule()).append('\t').append(span.start()).append("..").append(span.end())
                .append('\t').append(warning.reason()).append('\n');
        }
        return out.append("END\n").toString();
    }

    static final String LINT_PROBE = """
        use std::io::{self, BufRead};
        use unlaxer_ubnf::auto_commit;
        fn main() {
            for row in io::stdin().lock().lines() {
                let hex = row.unwrap();
                let bytes: Vec<u8> = (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap()).collect();
                let source = String::from_utf8(bytes).unwrap();
                for f in auto_commit::lint(&source).unwrap() {
                    let commits: Vec<String> = f.commits.iter()
                        .map(|(name, span)| format!("{}@{}..{}", name, span.codepoint_start, span.codepoint_end)).collect();
                    println!("F\\t{}\\t{}..{}\\t{}\\t{}\\t{}", f.rule, f.span.codepoint_start, f.span.codepoint_end, f.cut, commits.join(","), f.inserted);
                }
                for w in auto_commit::lint_warnings(&source).unwrap() {
                    println!("W\\t{}\\t{}..{}\\t{}", w.rule, w.span.codepoint_start, w.span.codepoint_end, w.reason);
                }
                println!("END");
            }
        }
        """;

    static final String RUST_PROBE = """
        use std::io::{self, BufRead};
        use unlaxer_runtime::{Diagnostics, Memoization, ParseDiagnostic, ParseOptions, Tree};
        fn observe(input: &str, detailed: fn(&str, ParseOptions) -> Result<Tree, ParseDiagnostic>, map: fn(&Tree) -> Result<String, String>) {
            for memo in [Memoization::Off, Memoization::SafeFailures] {
                for mode in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                    let options = ParseOptions::with_memoization(memo).with_diagnostics(mode);
                    match detailed(input, options) {
                        Ok(tree) => println!("ok\\t{}|{:?}\\t{}", tree.root, tree.nodes, map(&tree).unwrap_or_else(|e| format!("map-error {e}"))),
                        Err(error) => println!("fail\\t{}\\t{}", error.kind, error.offset),
                    }
                }
            }
        }
        fn main() {
            for row in io::stdin().lock().lines() {
                let row = row.unwrap();
                let (index, hex) = row.split_once('\\t').unwrap();
                let bytes: Vec<u8> = (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap()).collect();
                dispatch(index.parse().unwrap(), &String::from_utf8(bytes).unwrap());
            }
        }
        """;

    private String run(List<String> command, String input, int seconds) throws Exception {
        Path log = temporary.newFile().toPath(), errors = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectOutput(log.toFile()).redirectError(errors.toFile());
        builder.environment().put("CARGO_INCREMENTAL", "0");
        var process = builder.start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("timeout " + command);
        }
        String output = Files.readString(log);
        assertEquals(command + "\n" + Files.readString(errors), 0, process.exitValue());
        return output;
    }

    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }
}
