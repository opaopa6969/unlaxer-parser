package org.unlaxer.dsl.init;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * `unlaxer init` の実行本体。
 *
 * 1. テンプレート変数を構築
 * 2. 内蔵テンプレートを classpath から読み込み
 * 3. {@link TemplateRenderer} で展開
 * 4. {@link TmLanguageEmitter} で構文ハイライト用 JSON を生成
 * 5. 出力先ディレクトリに書き出し
 */
public final class InitRunner {

    private InitRunner() {}

    /** ファイル相対パス → classpath リソース名のマッピング。 */
    private static final List<TemplateMapping> TEMPLATES = List.of(
        new TemplateMapping("scaffold/pom.xml.template",                        "pom.xml"),
        new TemplateMapping("scaffold/Makefile.template",                       "Makefile"),
        new TemplateMapping("scaffold/.gitignore.template",                     ".gitignore"),
        new TemplateMapping("scaffold/README.md.template",                      "README.md"),
        new TemplateMapping("scaffold/README.ja.md.template",                   "README.ja.md"),
        new TemplateMapping("scaffold/IMPLEMENTATION.md.template",              "IMPLEMENTATION.md"),
        new TemplateMapping("scaffold/IMPLEMENTATION.ja.md.template",           "IMPLEMENTATION.ja.md"),
        new TemplateMapping("scaffold/grammar/sample.ubnf.template",            "grammar/{{name}}.ubnf"),
        new TemplateMapping("scaffold/vscode-extension/package.json.template",  "vscode-extension/package.json"),
        new TemplateMapping("scaffold/vscode-extension/tsconfig.json.template", "vscode-extension/tsconfig.json"),
        new TemplateMapping("scaffold/vscode-extension/language-configuration.json.template",
                                                                                "vscode-extension/language-configuration.json"),
        new TemplateMapping("scaffold/vscode-extension/src/extension.ts.template",
                                                                                "vscode-extension/src/extension.ts"),
        new TemplateMapping("scaffold/vscode-extension/.gitkeep-server-dist.template",
                                                                                "vscode-extension/server-dist/.gitkeep")
    );

    public static int run(InitCliParser.InitOptions opts, PrintStream out, PrintStream err) {
        try {
            return runOrThrow(opts, out, err);
        } catch (InitFailure f) {
            err.println("init: " + f.getMessage());
            return f.exitCode();
        } catch (IOException e) {
            err.println("init: I/O error: " + e.getMessage());
            return 4;
        } catch (RuntimeException e) {
            err.println("init: failed: " + e.getMessage());
            return 4;
        }
    }

    private static int runOrThrow(InitCliParser.InitOptions opts, PrintStream out, PrintStream err)
            throws IOException, InitFailure {

        NameDeriver nd = NameDeriver.of(opts.name());
        String packageName = opts.packageName() != null ? opts.packageName() : nd.defaultPackage();
        validatePackage(packageName);
        String groupId = opts.groupId() != null ? opts.groupId() : NameDeriver.groupIdFromPackage(packageName);
        String extension = opts.extension() != null
            ? NameDeriver.normalizeExtension(opts.extension())
            : "." + nd.lower();
        Path outputDir = Path.of(opts.outputDir() != null ? opts.outputDir() : nd.lower()).toAbsolutePath();
        boolean withDap = opts.withDap();

        // 1. 文法ソースを準備 (--from があれば読み込み、なければサンプルテンプレを展開)
        String grammarSource = loadGrammarSource(opts, nd, packageName);

        // 2. UBNF パース → tmLanguage 用に GrammarDecl を取得
        UBNFFile ubnf = UBNFMapper.parse(grammarSource);
        if (ubnf.grammars().isEmpty()) {
            throw new InitFailure(3, "input grammar contains no `grammar { ... }` block");
        }
        GrammarDecl grammar = ubnf.grammars().get(0);

        // 3. テンプレート変数
        Map<String, Object> vars = buildTemplateVars(nd, packageName, groupId, extension, withDap);

        // syntax-only: tmLanguage.json だけ書き出して終了
        if (opts.syntaxOnly()) {
            Path syntaxesDir = outputDir.resolve("vscode-extension/syntaxes");
            Files.createDirectories(syntaxesDir);
            Path tmFile = syntaxesDir.resolve(nd.lower() + ".tmLanguage.json");
            Files.writeString(tmFile, TmLanguageEmitter.emit(grammar, nd.lower()));
            out.println("Regenerated " + tmFile);
            return 0;
        }

        // 4. 出力先準備
        if (Files.exists(outputDir) && !opts.force()) {
            // 空ディレクトリなら許容、そうでなければエラー
            try (var stream = Files.list(outputDir)) {
                if (stream.findAny().isPresent()) {
                    throw new InitFailure(2,
                        "output directory is not empty: " + outputDir
                            + " (use --force to overwrite)");
                }
            }
        }
        Files.createDirectories(outputDir);

        // 5. テンプレートをレンダリングして書き出し
        TemplateRenderer renderer = new TemplateRenderer(vars);
        for (TemplateMapping m : TEMPLATES) {
            String content = renderer.render(loadResource(m.resource()));
            String relPath = renderer.render(m.relativePath());
            Path target = outputDir.resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        }

        // 6. UBNF 文法本体 (parse 済みのソースを保存)
        Path grammarFile = outputDir.resolve("grammar/" + nd.lower() + ".ubnf");
        Files.createDirectories(grammarFile.getParent());
        Files.writeString(grammarFile, grammarSource);

        // 7. tmLanguage を生成
        Path syntaxesDir = outputDir.resolve("vscode-extension/syntaxes");
        Files.createDirectories(syntaxesDir);
        Path tmFile = syntaxesDir.resolve(nd.lower() + ".tmLanguage.json");
        Files.writeString(tmFile, TmLanguageEmitter.emit(grammar, nd.lower()));

        out.println("Created " + outputDir);
        out.println("Next steps:");
        out.println("  cd " + outputDir.getFileName());
        out.println("  make all");
        out.println("  make install-vsix");
        return 0;
    }

    private static String loadGrammarSource(InitCliParser.InitOptions opts, NameDeriver nd,
                                            String packageName) throws IOException {
        if (opts.fromGrammar() != null) {
            return Files.readString(Path.of(opts.fromGrammar()));
        }
        // DAP 有効時は @mapping 込みのサンプル (Mapper/AST 生成が必要)
        // それ以外は最小サンプル (validator が安定して通る形)
        String resource = opts.withDap()
            ? "scaffold/grammar/sample-with-mapping.ubnf.template"
            : "scaffold/grammar/sample.ubnf.template";
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", nd.lower());
        vars.put("ClassName", nd.className());
        vars.put("UPPER_NAME", nd.upper());
        vars.put("packageName", packageName);
        return new TemplateRenderer(vars).render(loadResource(resource));
    }

    private static Map<String, Object> buildTemplateVars(
            NameDeriver nd, String packageName, String groupId, String extension, boolean withDap) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("name", nd.lower());
        vars.put("ClassName", nd.className());
        vars.put("UPPER_NAME", nd.upper());
        vars.put("packageName", packageName);
        vars.put("packagePath", NameDeriver.packageToPath(packageName));
        vars.put("groupId", groupId);
        vars.put("extension", extension);
        vars.put("unlaxerVersion", resolveToolVersion());
        vars.put("dap", withDap);
        vars.put("generators",
            withDap
                ? "AST,Parser,Mapper,LSP,Launcher,DAP,DAPLauncher"
                : "Parser,LSP,Launcher");
        return vars;
    }

    private static String loadResource(String path) throws IOException {
        ClassLoader cl = InitRunner.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("template resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void validatePackage(String pkg) throws InitFailure {
        if (pkg.isBlank()) {
            throw new InitFailure(2, "package name must be non-blank");
        }
        for (String part : pkg.split("\\.")) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                throw new InitFailure(2, "invalid package name: " + pkg);
            }
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                    throw new InitFailure(2, "invalid package name: " + pkg);
                }
            }
        }
    }

    private static String resolveToolVersion() {
        Package pkg = InitRunner.class.getPackage();
        if (pkg == null) return "2.8.0";
        String v = pkg.getImplementationVersion();
        return (v == null || v.isBlank()) ? "2.8.0" : v;
    }

    private record TemplateMapping(String resource, String relativePath) {}

    private static final class InitFailure extends Exception {
        private final int exitCode;
        InitFailure(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }
        int exitCode() { return exitCode; }
    }
}
